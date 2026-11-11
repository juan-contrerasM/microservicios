import amqp from 'amqplib';

export const CLAVES = ['empleado.creado', 'empleado.retirado'];

// Política común de reintento/DLQ (ver docs/eventos.md). Los cuatro consumidores usan los
// mismos nombres para que la topología sea idéntica en RabbitMQ:
//   <q>        → dead-letter a onboarding.dlx con routing key <q> (reject sin requeue = DLQ)
//   <q>.retry  → TTL 5 s y dead-letter al exchange por defecto ('') con routing key <q>,
//                así el reintento vuelve SOLO a la cola que falló y no a todos los consumidores.
//   <q>.dlq    → enlazada a onboarding.dlx con routing key <q>.
export const MAX_REINTENTOS = 3;
export const RETRY_TTL_MS = 5000;
export const DLX_EXCHANGE = 'onboarding.dlx';
export const HEADER_REINTENTOS = 'x-retry-count';
export const HEADER_ROUTING_ORIGINAL = 'x-original-routing-key';
const CAMPOS_ENVELOPE = ['id', 'type', 'version', 'occurredAt', 'producer', 'data'];
// Headers que gestiona el propio broker al hacer dead-letter; no se copian al republicar.
const HEADERS_DEL_BROKER = ['x-death', 'x-first-death-', 'x-last-death-'];
const PRECONDITION_FAILED = 406;

export class EnvelopeInvalido extends Error {}

/** Exige los seis campos de C1 y un type que esta cola consume. Lanza EnvelopeInvalido. */
export function validarEnvelope(envelope, tipos = CLAVES) {
	if (envelope === null || typeof envelope !== 'object' || Array.isArray(envelope)) {
		throw new EnvelopeInvalido('el cuerpo no es un objeto JSON');
	}
	const faltantes = CAMPOS_ENVELOPE.filter((campo) => envelope[campo] == null || envelope[campo] === '');
	if (faltantes.length > 0) {
		throw new EnvelopeInvalido(`faltan campos obligatorios: ${faltantes.join(', ')}`);
	}
	if (typeof envelope.id !== 'string') {
		throw new EnvelopeInvalido('id debe ser texto');
	}
	if (!tipos.includes(envelope.type)) {
		throw new EnvelopeInvalido(`type desconocido para esta cola: ${envelope.type}`);
	}
	if (!Number.isInteger(envelope.version) || envelope.version < 1) {
		throw new EnvelopeInvalido('version debe ser un entero >= 1');
	}
	if (typeof envelope.producer !== 'string') {
		throw new EnvelopeInvalido('producer debe ser texto');
	}
	if (typeof envelope.occurredAt !== 'string' || Number.isNaN(Date.parse(envelope.occurredAt))) {
		throw new EnvelopeInvalido('occurredAt no es ISO-8601');
	}
	if (typeof envelope.data !== 'object' || Array.isArray(envelope.data)) {
		throw new EnvelopeInvalido('data debe ser un objeto');
	}
	return envelope;
}

export function leerEnvelope(contenido, tipos = CLAVES) {
	let envelope;
	try {
		envelope = JSON.parse(contenido.toString('utf8'));
	} catch {
		throw new EnvelopeInvalido('el mensaje no es JSON');
	}
	return validarEnvelope(envelope, tipos);
}

export function argumentosPrincipal(queue) {
	return { 'x-dead-letter-exchange': DLX_EXCHANGE, 'x-dead-letter-routing-key': queue };
}

export function argumentosRetry(queue) {
	return {
		'x-message-ttl': RETRY_TTL_MS,
		'x-dead-letter-exchange': '',
		'x-dead-letter-routing-key': queue,
	};
}

/**
 * Declara exchange, cola principal, <q>.retry y <q>.dlq. Si una cola existe con otros
 * argumentos (PRECONDITION_FAILED) se recrea solo si está vacía; con mensajes dentro se
 * falla con un error claro (ver docs/eventos.md, sección Migración).
 */
export async function declararTopologia(conexion, { exchange, queue }) {
	let canal = await abrirCanal(conexion);
	await canal.assertExchange(exchange, 'topic', { durable: true });
	await canal.assertExchange(DLX_EXCHANGE, 'direct', { durable: true });
	canal = await declararCola(conexion, canal, queue, argumentosPrincipal(queue));
	canal = await declararCola(conexion, canal, `${queue}.retry`, argumentosRetry(queue));
	canal = await declararCola(conexion, canal, `${queue}.dlq`, {});
	await canal.bindQueue(`${queue}.dlq`, DLX_EXCHANGE, queue);
	for (const clave of CLAVES) {
		await canal.bindQueue(queue, exchange, clave);
	}
	await canal.close();
}

async function abrirCanal(conexion) {
	const canal = await conexion.createChannel();
	// Sin listener, el 'error' de un canal cerrado por el broker tumbaría el proceso.
	canal.on('error', (error) => console.error(`Canal de topología cerrado por el broker: ${error.message}`));
	return canal;
}

async function declararCola(conexion, canal, nombre, argumentos) {
	try {
		await canal.assertQueue(nombre, { durable: true, arguments: argumentos });
		return canal;
	} catch (error) {
		if (error?.code !== PRECONDITION_FAILED) {
			throw error;
		}
	}
	console.warn(`La cola ${nombre} existe con argumentos distintos; se recrea si está vacía`);
	const otro = await abrirCanal(conexion);
	try {
		await otro.deleteQueue(nombre, { ifEmpty: true });
	} catch (error) {
		throw new Error(
			`La cola ${nombre} tiene mensajes y argumentos antiguos; vacíala o bórrala desde la UI `
			+ `de RabbitMQ (ver docs/eventos.md, sección Migración): ${error.message}`,
		);
	}
	await otro.assertQueue(nombre, { durable: true, arguments: argumentos });
	return otro;
}

export function crearConsumidor({ config, pool, alCambiar }) {
	let conectado = false;
	let detenido = false;
	let conexion = null;

	async function ciclo() {
		while (!detenido) {
			try {
				conexion = await amqp.connect(config.amqpUrl);
				await declararTopologia(conexion, config);
				// Canal con confirmaciones: el original solo se confirma cuando el broker ya
				// aceptó la copia en <q>.retry.
				const canal = await conexion.createConfirmChannel();
				await canal.prefetch(1);
				const manejar = crearManejador({ canal, pool, queue: config.queue });
				conectado = true;
				alCambiar(true);
				console.log(`Consumidor escuchando ${config.queue}`);
				await new Promise((resolve, reject) => {
					conexion.on('close', resolve);
					conexion.on('error', reject);
					canal.on('error', reject);
					canal.on('close', resolve);
					canal.consume(config.queue, (mensaje) => manejar(mensaje), { noAck: false }).catch(reject);
				});
			} catch (error) {
				console.error(`Consumidor desconectado; reintento en 5s: ${error.message}`);
			} finally {
				conectado = false;
				alCambiar(false);
				if (conexion) {
					try {
						await conexion.close();
					} catch {
						// ya estaba cerrada
					}
					conexion = null;
				}
			}
			if (!detenido) {
				await new Promise((resolve) => setTimeout(resolve, 5000));
			}
		}
	}

	ciclo();

	return {
		estaConectado: () => conectado,
		detener: () => {
			detenido = true;
			if (conexion) {
				conexion.close().catch(() => {});
			}
		},
	};
}

/**
 * Manejador de un mensaje. Exportado para probarlo con dobles del canal y del pool.
 * Nunca deja un mensaje sin ack/reject: con prefetch 1 eso bloquearía la cola.
 */
export function crearManejador({ canal, pool, queue }) {
	return async function manejar(mensaje) {
		if (!mensaje) {
			return;
		}
		let envelope;
		try {
			envelope = leerEnvelope(mensaje.content);
		} catch (error) {
			// Poison: reintentarlo no lo arregla. Reject sin requeue → DLX → <q>.dlq.
			console.error(`Mensaje inválido; se envía a DLQ: ${error.message}`);
			canal.reject(mensaje, false);
			return;
		}
		let client = null;
		try {
			client = await pool.connect();
			await client.query('BEGIN');
			// La marca en eventos_procesados y la réplica van en la misma transacción.
			await procesarEvento(client, envelope);
			await client.query('COMMIT');
		} catch (error) {
			if (client) {
				await client.query('ROLLBACK').catch(() => {});
			}
			await reintentarODlq({ canal, mensaje, eventId: envelope.id, queue, error });
			return;
		} finally {
			client?.release();
		}
		canal.ack(mensaje);
		console.log(`Evento ${envelope.id} (${envelope.type}) procesado`);
	};
}

export async function reintentarODlq({ canal, mensaje, eventId, queue, error }) {
	const headers = Object.fromEntries(
		Object.entries(mensaje.properties.headers ?? {})
			.filter(([clave]) => !HEADERS_DEL_BROKER.some((prefijo) => clave.startsWith(prefijo))),
	);
	const intento = Number.parseInt(headers[HEADER_REINTENTOS] ?? 0, 10) || 0;
	if (intento >= MAX_REINTENTOS) {
		console.error(`Fallo definitivo procesando ${eventId} tras ${intento} reintentos; se envía a DLQ: ${error?.message}`);
		canal.reject(mensaje, false);
		return;
	}
	// Se conservan todos los headers del productor (traceparent, tracestate, baggage...).
	headers[HEADER_REINTENTOS] = intento + 1;
	headers[HEADER_ROUTING_ORIGINAL] ??= mensaje.fields.routingKey;
	try {
		await new Promise((resolve, reject) => {
			canal.publish('', `${queue}.retry`, mensaje.content, {
				persistent: true,
				contentType: mensaje.properties.contentType || 'application/json',
				messageId: mensaje.properties.messageId,
				correlationId: mensaje.properties.correlationId,
				type: mensaje.properties.type,
				headers,
			}, (falla) => (falla ? reject(falla) : resolve()));
		});
	} catch (falla) {
		console.error(`No se pudo programar el reintento de ${eventId}; se envía a DLQ: ${falla?.message ?? falla}`);
		canal.reject(mensaje, false);
		return;
	}
	canal.ack(mensaje);
	console.error(`Fallo procesando ${eventId}; reintento ${intento + 1}/${MAX_REINTENTOS} programado en ${queue}.retry: ${error?.message}`);
}

/** Deduplica por id y aplica el efecto. Debe correr dentro de una transacción abierta. */
export async function procesarEvento(client, envelope) {
	const eventId = String(envelope.id);
	const ya = await client.query('SELECT 1 FROM eventos_procesados WHERE id = $1', [eventId]);
	if (ya.rowCount > 0) {
		return false;
	}
	const data = envelope.data ?? {};
	if (envelope.type === 'empleado.creado') {
		await guardarAlta(client, data);
	} else if (envelope.type === 'empleado.retirado') {
		await marcarRetiro(client, data);
	}
	await client.query(
		'INSERT INTO eventos_procesados (id, procesado_en) VALUES ($1, now())',
		[eventId],
	);
	return true;
}

async function guardarAlta(client, data) {
	const empleadoId = texto(data.empleadoId);
	const email = texto(data.email);
	const estado = texto(data.estado) ?? 'ACTIVO';
	if (!empleadoId || !email) {
		console.error('empleado.creado incompleto; se confirma sin réplica');
		return;
	}
	await client.query(
		`INSERT INTO empleados_replica (empleado_id, email, estado) VALUES ($1, $2, $3)
		 ON CONFLICT (empleado_id) DO UPDATE SET email = EXCLUDED.email, estado = EXCLUDED.estado`,
		[empleadoId, email, estado],
	);
}

async function marcarRetiro(client, data) {
	const empleadoId = texto(data.empleadoId);
	if (!empleadoId) {
		console.error('empleado.retirado sin empleadoId; se confirma sin efecto');
		return;
	}
	const resultado = await client.query(
		`UPDATE empleados_replica SET estado = 'RETIRADO' WHERE empleado_id = $1`,
		[empleadoId],
	);
	if (resultado.rowCount === 0) {
		console.error(`empleado.retirado sin réplica para ${empleadoId}; se confirma sin fila`);
	}
}

function texto(valor) {
	if (valor == null) {
		return null;
	}
	const limpio = String(valor).trim();
	return limpio === '' ? null : limpio;
}

import amqp from 'amqplib';

const CLAVES = ['empleado.creado', 'empleado.retirado'];

export function crearConsumidor({ config, pool, alCambiar }) {
	let conectado = false;
	let detenido = false;
	let conexion = null;

	async function procesar(client, envelope) {
		const eventId = envelope?.id ? String(envelope.id) : '';
		if (!eventId) {
			console.error('Evento sin id; se confirma sin efecto');
			return;
		}
		const ya = await client.query('SELECT 1 FROM eventos_procesados WHERE id = $1', [eventId]);
		if (ya.rowCount > 0) {
			return;
		}
		const data = envelope.data ?? {};
		const tipo = envelope.type;
		if (tipo === 'empleado.creado') {
			await guardarAlta(client, data);
		} else if (tipo === 'empleado.retirado') {
			await marcarRetiro(client, data);
		}
		await client.query(
			'INSERT INTO eventos_procesados (id, procesado_en) VALUES ($1, now())',
			[eventId],
		);
	}

	async function ciclo() {
		while (!detenido) {
			try {
				conexion = await amqp.connect(config.amqpUrl);
				const canal = await conexion.createChannel();
				await canal.assertExchange(config.exchange, 'topic', { durable: true });
				await canal.assertQueue(config.queue, { durable: true });
				for (const clave of CLAVES) {
					await canal.bindQueue(config.queue, config.exchange, clave);
				}
				await canal.prefetch(1);
				conectado = true;
				alCambiar(true);
				console.log(`Consumidor escuchando ${config.queue}`);
				await new Promise((resolve, reject) => {
					conexion.on('close', resolve);
					conexion.on('error', reject);
					canal.consume(config.queue, (mensaje) => onMessage(canal, mensaje), { noAck: false });
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

	async function onMessage(canal, mensaje) {
		if (!mensaje) {
			return;
		}
		let envelope;
		try {
			envelope = JSON.parse(mensaje.content.toString('utf8'));
		} catch {
			console.error('Mensaje no es JSON; se confirma para no bloquear la cola');
			canal.ack(mensaje);
			return;
		}
		const client = await pool.connect();
		try {
			await client.query('BEGIN');
			await procesar(client, envelope);
			await client.query('COMMIT');
			canal.ack(mensaje);
		} catch (error) {
			await client.query('ROLLBACK');
			console.error(`Fallo procesando ${envelope?.id}; se reencola: ${error.message}`);
			canal.nack(mensaje, false, true);
		} finally {
			client.release();
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

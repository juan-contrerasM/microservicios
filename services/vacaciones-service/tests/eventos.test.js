import test from 'node:test';
import assert from 'node:assert/strict';
import {
	CLAVES,
	MAX_REINTENTOS,
	crearManejador,
	declararTopologia,
	reintentarODlq,
} from '../src/consumidor.js';
import { crearEnvelope, crearPublicador } from '../src/publicar.js';
import { crearServicio } from '../src/servicio.js';
import { crearTransiciones } from '../src/transiciones.js';

// Fase 4 (E11/E12) con dobles: sin RabbitMQ ni PostgreSQL.

const TRACEPARENT = '00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01';

function envelope(extra = {}) {
	return {
		id: 'evt-alta',
		type: 'empleado.creado',
		version: 1,
		occurredAt: '2026-11-10T22:08:00Z',
		producer: 'empleados-service',
		data: { empleadoId: 'E001', email: 'juan.perez@empresa.com', estado: 'ACTIVO' },
		...extra,
	};
}

function mensaje(cuerpo, headers = {}, routingKey = 'empleado.creado') {
	const content = Buffer.isBuffer(cuerpo) ? cuerpo : Buffer.from(typeof cuerpo === 'string' ? cuerpo : JSON.stringify(cuerpo));
	return {
		content,
		fields: { routingKey, deliveryTag: 1 },
		properties: { headers, contentType: 'application/json', messageId: 'evt-alta' },
	};
}

/** Canal de confirmaciones falso: registra ack/reject y publicaciones. */
function canalFalso({ fallaPublicar = false } = {}) {
	return {
		acks: [],
		rechazos: [],
		publicados: [],
		ack(m) { this.acks.push(m); },
		reject(m, requeue) { this.rechazos.push(requeue); },
		publish(exchange, routingKey, content, opciones, cb) {
			if (fallaPublicar) {
				cb(new Error('message nacked'));
				return false;
			}
			this.publicados.push({ exchange, routingKey, content, opciones });
			cb(null);
			return true;
		},
	};
}

/** Pool transaccional en memoria: lo escrito solo se ve después del COMMIT. */
function poolFalso({ fallaEn = null, fallaConectar = false } = {}) {
	const base = { procesados: new Set(), replica: new Map() };
	const bitacora = [];
	return {
		base,
		bitacora,
		connect: async () => {
			if (fallaConectar) {
				throw new Error('base caída');
			}
			let pendiente = null;
			return {
				async query(sql, params = []) {
					const sentencia = sql.trim().split(/\s+/).slice(0, 3).join(' ');
					bitacora.push(sentencia);
					if (fallaEn && sql.includes(fallaEn)) {
						throw new Error('fallo transitorio');
					}
					if (sql === 'BEGIN') {
						pendiente = { procesados: new Set(), replica: new Map() };
					} else if (sql === 'COMMIT') {
						pendiente.procesados.forEach((id) => base.procesados.add(id));
						pendiente.replica.forEach((v, k) => base.replica.set(k, v));
						pendiente = null;
					} else if (sql === 'ROLLBACK') {
						pendiente = null;
					} else if (sql.startsWith('SELECT 1 FROM eventos_procesados')) {
						return { rowCount: base.procesados.has(params[0]) ? 1 : 0 };
					} else if (sql.startsWith('INSERT INTO eventos_procesados')) {
						pendiente.procesados.add(params[0]);
					} else if (sql.includes('INSERT INTO empleados_replica')) {
						pendiente.replica.set(params[0], { email: params[1], estado: params[2] });
					}
					return { rowCount: 1 };
				},
				release() {},
			};
		},
	};
}

test('evento válido crea la réplica, marca el id y confirma', async () => {
	const canal = canalFalso();
	const pool = poolFalso();

	await crearManejador({ canal, pool, queue: 'q.vacaciones' })(mensaje(envelope()));

	assert.equal(canal.acks.length, 1);
	assert.deepEqual(pool.base.replica.get('E001'), { email: 'juan.perez@empresa.com', estado: 'ACTIVO' });
	assert.ok(pool.base.procesados.has('evt-alta'));
});

test('un duplicado se confirma sin repetir el efecto', async () => {
	const canal = canalFalso();
	const pool = poolFalso();
	const manejar = crearManejador({ canal, pool, queue: 'q.vacaciones' });

	await manejar(mensaje(envelope()));
	pool.bitacora.length = 0;
	await manejar(mensaje(envelope({ data: { empleadoId: 'E001', email: 'otro@empresa.com' } })));

	assert.equal(canal.acks.length, 2);
	assert.equal(pool.base.replica.get('E001').email, 'juan.perez@empresa.com');
	assert.ok(!pool.bitacora.some((s) => s.includes('empleados_replica')));
});

for (const [nombre, cuerpo] of [
	['no JSON', 'esto no es json'],
	['un arreglo', [1, 2]],
	['sin producer', (() => { const e = envelope(); delete e.producer; return e; })()],
	['sin occurredAt', (() => { const e = envelope(); delete e.occurredAt; return e; })()],
	['id vacío', envelope({ id: '' })],
	['type desconocido', envelope({ type: 'evento.desconocido' })],
	['type de otra cola', envelope({ type: 'usuario.creado' })],
	['version como texto', envelope({ version: '1' })],
	['version 0', envelope({ version: 0 })],
	['occurredAt inválido', envelope({ occurredAt: 'ayer' })],
	['data no objeto', envelope({ data: [1] })],
]) {
	test(`envelope inválido (${nombre}) se rechaza sin requeue y no abre la base`, async () => {
		const canal = canalFalso();
		const pool = poolFalso();

		await crearManejador({ canal, pool, queue: 'q.vacaciones' })(mensaje(cuerpo));

		assert.deepEqual(canal.rechazos, [false]);
		assert.equal(canal.acks.length, 0);
		assert.deepEqual(pool.bitacora, []);
	});
}

test('un fallo hace ROLLBACK y publica en la cola retry propia con contador y headers', async () => {
	const canal = canalFalso();
	const pool = poolFalso({ fallaEn: 'INSERT INTO eventos_procesados' });

	await crearManejador({ canal, pool, queue: 'q.vacaciones' })(mensaje(envelope(), {
		traceparent: TRACEPARENT,
		'x-death': [{ count: 1 }],
		'x-first-death-queue': 'q.vacaciones.retry',
	}));

	assert.ok(pool.bitacora.includes('ROLLBACK'));
	assert.equal(pool.base.replica.size, 0);
	assert.equal(canal.publicados.length, 1);
	const { exchange, routingKey, opciones } = canal.publicados[0];
	// Exchange por defecto + cola retry propia: el reintento no se re-difunde a otros consumidores.
	assert.equal(exchange, '');
	assert.equal(routingKey, 'q.vacaciones.retry');
	assert.equal(opciones.persistent, true);
	assert.equal(opciones.messageId, 'evt-alta');
	assert.equal(opciones.headers['x-retry-count'], 1);
	assert.equal(opciones.headers.traceparent, TRACEPARENT);
	assert.equal(opciones.headers['x-original-routing-key'], 'empleado.creado');
	assert.ok(!('x-death' in opciones.headers));
	assert.ok(!('x-first-death-queue' in opciones.headers));
	assert.equal(canal.acks.length, 1);
});

test('el contador avanza y conserva la routing key original', async () => {
	const canal = canalFalso();
	await crearManejador({ canal, pool: poolFalso({ fallaEn: 'SELECT 1' }), queue: 'q.vacaciones' })(
		mensaje(envelope(), { 'x-retry-count': 2, 'x-original-routing-key': 'empleado.creado' }, 'q.vacaciones'),
	);
	assert.equal(canal.publicados[0].opciones.headers['x-retry-count'], 3);
	assert.equal(canal.publicados[0].opciones.headers['x-original-routing-key'], 'empleado.creado');
});

test('con el contador agotado el fallo va a la DLQ', async () => {
	const canal = canalFalso();

	await crearManejador({ canal, pool: poolFalso({ fallaEn: 'SELECT 1' }), queue: 'q.vacaciones' })(
		mensaje(envelope(), { 'x-retry-count': MAX_REINTENTOS }),
	);

	assert.deepEqual(canal.rechazos, [false]);
	assert.equal(canal.publicados.length, 0);
	assert.equal(canal.acks.length, 0);
});

test('si la base no acepta conexiones también se reintenta, sin dejar el mensaje colgado', async () => {
	const canal = canalFalso();

	await crearManejador({ canal, pool: poolFalso({ fallaConectar: true }), queue: 'q.vacaciones' })(mensaje(envelope()));

	assert.equal(canal.publicados[0].routingKey, 'q.vacaciones.retry');
	assert.equal(canal.acks.length, 1);
});

test('si el broker no confirma el reintento, el mensaje va a la DLQ', async () => {
	const canal = canalFalso({ fallaPublicar: true });

	await reintentarODlq({ canal, mensaje: mensaje(envelope()), eventId: 'evt', queue: 'q.vacaciones', error: new Error('x') });

	assert.deepEqual(canal.rechazos, [false]);
	assert.equal(canal.acks.length, 0);
});

// --- Topología ------------------------------------------------------------------------------

function conexionFalsa(existentes = {}) {
	const registro = { exchanges: {}, colas: {}, borradas: [], bindings: [], existentes };
	registro.createChannel = async () => ({
		on() {},
		async close() {},
		async assertExchange(nombre, tipo) { registro.exchanges[nombre] = tipo; },
		async assertQueue(nombre, { arguments: args }) {
			const previa = registro.existentes[nombre];
			if (previa && JSON.stringify(previa.args) !== JSON.stringify(args)) {
				throw Object.assign(new Error('PRECONDITION_FAILED - inequivalent arg'), { code: 406 });
			}
			registro.existentes[nombre] = { args, mensajes: previa?.mensajes ?? 0 };
			registro.colas[nombre] = args;
		},
		async deleteQueue(nombre, { ifEmpty }) {
			if (ifEmpty && registro.existentes[nombre].mensajes > 0) {
				throw Object.assign(new Error('PRECONDITION_FAILED - queue not empty'), { code: 406 });
			}
			registro.borradas.push(nombre);
			delete registro.existentes[nombre];
		},
		async bindQueue(cola, exchange, clave) { registro.bindings.push([cola, exchange, clave]); },
	});
	return registro;
}

test('la cola retry hace dead-letter al exchange por defecto hacia la cola de origen', async () => {
	const conexion = conexionFalsa();

	await declararTopologia(conexion, { exchange: 'onboarding.eventos', queue: 'q.vacaciones' });

	assert.deepEqual(conexion.exchanges, { 'onboarding.eventos': 'topic', 'onboarding.dlx': 'direct' });
	assert.deepEqual(conexion.colas['q.vacaciones'], {
		'x-dead-letter-exchange': 'onboarding.dlx',
		'x-dead-letter-routing-key': 'q.vacaciones',
	});
	assert.deepEqual(conexion.colas['q.vacaciones.retry'], {
		'x-message-ttl': 5000,
		'x-dead-letter-exchange': '',
		'x-dead-letter-routing-key': 'q.vacaciones',
	});
	assert.ok(conexion.bindings.some(([c, e, k]) => c === 'q.vacaciones.dlq' && e === 'onboarding.dlx' && k === 'q.vacaciones'));
	assert.ok(!conexion.bindings.some(([c]) => c === 'q.vacaciones.retry'));
	assert.deepEqual(conexion.bindings.filter(([c]) => c === 'q.vacaciones').map(([, , k]) => k), CLAVES);
});

test('una cola vacía con argumentos antiguos se recrea al arrancar', async () => {
	const conexion = conexionFalsa({
		'q.vacaciones.retry': { args: { 'x-message-ttl': 5000, 'x-dead-letter-exchange': 'onboarding.eventos' }, mensajes: 0 },
	});

	await declararTopologia(conexion, { exchange: 'onboarding.eventos', queue: 'q.vacaciones' });

	assert.deepEqual(conexion.borradas, ['q.vacaciones.retry']);
	assert.equal(conexion.colas['q.vacaciones.retry']['x-dead-letter-exchange'], '');
});

test('una cola con mensajes y argumentos antiguos no se borra y el error remite a la guía', async () => {
	const conexion = conexionFalsa({ 'q.vacaciones': { args: {}, mensajes: 3 } });

	await assert.rejects(
		declararTopologia(conexion, { exchange: 'onboarding.eventos', queue: 'q.vacaciones' }),
		/docs\/eventos\.md/,
	);
	assert.deepEqual(conexion.borradas, []);
});

// --- Productor ------------------------------------------------------------------------------

test('el envelope publicado es C1 completo y espera la confirmación del broker', async () => {
	const publicados = [];
	const canal = {
		on() {},
		async assertExchange() {},
		publish(exchange, routingKey, content, opciones, cb) {
			publicados.push({ exchange, routingKey, envelope: JSON.parse(content), opciones });
			cb(null);
		},
	};
	const publicador = crearPublicador(
		{ amqpUrl: 'amqp://x', exchange: 'onboarding.eventos' },
		{ conectar: async () => ({ on() {}, createConfirmChannel: async () => canal }) },
	);

	await publicador.publicarProgramadas({ vacacionesId: 'V-1' });

	const [{ exchange, routingKey, envelope: env, opciones }] = publicados;
	assert.equal(exchange, 'onboarding.eventos');
	assert.equal(routingKey, 'vacaciones.programadas');
	assert.deepEqual(Object.keys(env), ['id', 'type', 'version', 'occurredAt', 'producer', 'data']);
	assert.equal(env.producer, 'vacaciones-service');
	assert.equal(env.version, 1);
	assert.equal(opciones.messageId, env.id);
	assert.equal(opciones.persistent, true);
});

test('un nack del broker se convierte en error para quien publica', async () => {
	const canal = {
		on() {},
		async assertExchange() {},
		publish(_e, _r, _c, _o, cb) { cb(new Error('message nacked')); },
	};
	const publicador = crearPublicador(
		{ amqpUrl: 'amqp://x', exchange: 'onboarding.eventos' },
		{ conectar: async () => ({ on() {}, createConfirmChannel: async () => canal }) },
	);

	await assert.rejects(publicador.publicarEvento('vacaciones.iniciadas', {}), /nacked/);
});

test('crearEnvelope usa occurredAt en UTC ISO-8601 y un id UUID', () => {
	const env = crearEnvelope('vacaciones.finalizadas', {}, new Date('2026-11-10T12:00:00Z'));
	assert.equal(env.occurredAt, '2026-11-10T12:00:00.000Z');
	assert.match(env.id, /^[0-9a-f-]{36}$/);
});

function poolConBitacora(bitacora) {
	return {
		connect: async () => ({
			query: async (sql) => { if (['BEGIN', 'COMMIT', 'ROLLBACK'].includes(sql)) bitacora.push(sql); },
			release: () => {},
		}),
	};
}

test('vacaciones.programadas sale después del COMMIT y un fallo del broker no revierte', async () => {
	const bitacora = [];
	const servicio = crearServicio({
		pool: poolConBitacora(bitacora),
		ahora: () => new Date('2026-10-04T12:00:00Z'),
		publicar: {
			publicarProgramadas: async () => { bitacora.push('publicar'); throw new Error('broker caído'); },
		},
		repo: {
			obtenerEmpleado: async () => ({ empleadoId: 'E001', email: 'a@b.com', estado: 'ACTIVO' }),
			buscarConflicto: async () => null,
			siguienteNumero: async () => 1,
			insertarPeriodo: async (_c, fila) => ({ ...fila, estado: 'PROGRAMADA' }),
		},
	});

	const periodo = await servicio.programar({ empleadoId: 'E001', fechaInicio: '2027-03-15', fechaFin: '2027-03-16' });

	assert.equal(periodo.id, 'V-2027-0001');
	assert.deepEqual(bitacora, ['BEGIN', 'COMMIT', 'publicar']);
});

test('el scheduler publica iniciadas y finalizadas después de cada COMMIT', async () => {
	const bitacora = [];
	const periodo = { id: 'V-1', empleadoId: 'E001', estado: 'EN_CURSO', fechaInicio: '2026-10-01', fechaFin: '2026-10-02' };
	const transiciones = crearTransiciones({
		pool: poolConBitacora(bitacora),
		ahora: () => new Date('2026-10-04T12:00:00Z'),
		publicar: { publicarEvento: async (tipo) => { bitacora.push(tipo); throw new Error('broker caído'); } },
		repo: {
			obtenerEmpleado: async () => ({ email: 'a@b.com' }),
			iniciarVencidos: async () => [periodo],
			finalizarVencidos: async () => [{ ...periodo, estado: 'FINALIZADA' }],
		},
	});

	const resultado = await transiciones.ejecutarCiclo();

	assert.equal(resultado.iniciados.length, 1);
	assert.deepEqual(bitacora, ['BEGIN', 'COMMIT', 'vacaciones.iniciadas', 'BEGIN', 'COMMIT', 'vacaciones.finalizadas']);
});

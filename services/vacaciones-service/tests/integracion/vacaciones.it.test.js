// E14: integración contra PostgreSQL y RabbitMQ reales. No usa dobles: migraciones de verdad,
// consumidor conectado al broker, API HTTP sobre el pool real y eventos leídos de una cola.
// Requiere IT_DATABASE_URL e IT_AMQP_URL (los fija scripts/test-integration.sh); sin ellas se omite.
import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import path from 'node:path';
import { after, before, test } from 'node:test';
import { fileURLToPath } from 'node:url';
import amqp from 'amqplib';
import migrate from 'node-pg-migrate';
import { createApp } from '../../src/app.js';
import { crearConsumidor } from '../../src/consumidor.js';
import { baseResponde, crearPool } from '../../src/db.js';
import { crearPublicador } from '../../src/publicar.js';
import { evaluarChecks } from '../../src/salud.js';
import { conLockDeScheduler } from '../../src/lock.js';
import { crearServicio } from '../../src/servicio.js';

const databaseUrl = process.env.IT_DATABASE_URL;
const amqpUrl = process.env.IT_AMQP_URL;
const omitir = !databaseUrl || !amqpUrl ? 'IT_DATABASE_URL e IT_AMQP_URL no definidas' : false;

const dirMigraciones = path.join(path.dirname(fileURLToPath(import.meta.url)), '..', '..', 'migrations');
const config = {
	amqpUrl,
	exchange: `it.vacaciones.${randomUUID().slice(0, 8)}`,
	queue: `q.vacaciones.it.${randomUUID().slice(0, 8)}`,
};
const EMPLEADO = `E-IT-${randomUUID().slice(0, 6)}`;

let pool;
let servicioIt;
let servidor;
let base;
let consumidor;
let conexion;
let canal;
let colaEventos;
let reloj = new Date('2030-01-06T12:00:00Z');
const recibidos = [];

async function migrar(direction, count) {
	await migrate({
		databaseUrl, dir: dirMigraciones, direction, count, migrationsTable: 'pgmigrations', log: () => {},
	});
}

async function esperar(condicion, ms = 15_000) {
	const limite = Date.now() + ms;
	while (Date.now() < limite) {
		const valor = await condicion();
		if (valor) return valor;
		await new Promise((r) => setTimeout(r, 200));
	}
	throw new Error('La condición no se cumplió a tiempo');
}

async function http(metodo, ruta, cuerpo) {
	const respuesta = await fetch(`${base}${ruta}`, {
		method: metodo,
		headers: { 'content-type': 'application/json' },
		body: cuerpo ? JSON.stringify(cuerpo) : undefined,
	});
	return { status: respuesta.status, body: await respuesta.json() };
}

before(async () => {
	if (omitir) return;
	await migrar('up');
	pool = crearPool(databaseUrl);
	const publicar = crearPublicador(config);
	servicioIt = crearServicio({ pool, publicar, ahora: () => reloj });
	let conectado = false;
	consumidor = crearConsumidor({ config, pool, alCambiar: (c) => { conectado = c; } });
	await esperar(() => conectado);

	conexion = await amqp.connect(amqpUrl);
	canal = await conexion.createChannel();
	({ queue: colaEventos } = await canal.assertQueue('', { exclusive: true }));
	await canal.bindQueue(colaEventos, config.exchange, 'vacaciones.*');
	await canal.consume(colaEventos, (m) => recibidos.push(JSON.parse(m.content.toString())), { noAck: true });

	const app = createApp({
		servicio: servicioIt,
		estaSano: () => evaluarChecks({ database: () => baseResponde(pool), messageBroker: () => conectado }, 2_000),
	});
	servidor = app.listen(0);
	base = `http://127.0.0.1:${servidor.address().port}`;
});

after(async () => {
	if (omitir) return;
	consumidor?.detener();
	servidor?.close();
	await canal?.deleteQueue(config.queue).catch(() => {});
	await canal?.deleteQueue(`${config.queue}.retry`).catch(() => {});
	await canal?.deleteQueue(`${config.queue}.dlq`).catch(() => {});
	await canal?.deleteExchange(config.exchange).catch(() => {});
	await conexion?.close().catch(() => {});
	await pool?.end();
});

test('las migraciones bajan y vuelven a subir contra PostgreSQL real', { skip: omitir }, async () => {
	const tablas = async () => (await pool.query(
		`SELECT count(*)::int AS n FROM information_schema.tables
		 WHERE table_schema = 'public' AND table_name IN ('vacaciones', 'empleados_replica', 'secuencias', 'eventos_procesados')`,
	)).rows[0].n;
	assert.equal(await tablas(), 4);
	await migrar('down', 1);
	assert.equal(await tablas(), 0);
	await migrar('up');
	assert.equal(await tablas(), 4);
});

test('health reporta base y broker UP', { skip: omitir }, async () => {
	const { status, body } = await http('GET', '/health');
	assert.equal(status, 200);
	assert.deepEqual(body.checks, { database: 'UP', messageBroker: 'UP' });
});

test('empleado.creado llega por RabbitMQ, crea la réplica y el duplicado no tiene efecto', { skip: omitir }, async () => {
	const envelope = {
		id: randomUUID(), type: 'empleado.creado', version: 1, occurredAt: new Date().toISOString(),
		producer: 'empleados-service', data: { empleadoId: EMPLEADO, email: 'it@empresa.com', estado: 'ACTIVO' },
	};
	for (let i = 0; i < 2; i++) {
		canal.publish(config.exchange, 'empleado.creado', Buffer.from(JSON.stringify(envelope)), { persistent: true });
	}
	await esperar(async () => (await pool.query(
		'SELECT 1 FROM empleados_replica WHERE empleado_id = $1', [EMPLEADO],
	)).rowCount === 1);
	await esperar(async () => (await canal.checkQueue(config.queue)).messageCount === 0);
	const marcas = await pool.query('SELECT count(*)::int AS n FROM eventos_procesados WHERE id = $1', [envelope.id]);
	assert.equal(marcas.rows[0].n, 1);
});

test('un mensaje poison termina en la DLQ real', { skip: omitir }, async () => {
	canal.publish(config.exchange, 'empleado.creado', Buffer.from('{no es json'), { persistent: true });
	await esperar(async () => (await canal.checkQueue(`${config.queue}.dlq`)).messageCount === 1);
});

test('CRUD HTTP completo y transiciones con eventos publicados', { skip: omitir }, async () => {
	const creado = await http('POST', '/vacaciones', { empleadoId: EMPLEADO, fechaInicio: '2030-02-04', fechaFin: '2030-02-08' });
	assert.equal(creado.status, 201);
	assert.equal(creado.body.id, 'V-2030-0001');
	assert.equal(creado.body.diasHabiles, 5);

	const solapado = await http('POST', '/vacaciones', { empleadoId: EMPLEADO, fechaInicio: '2030-02-06', fechaFin: '2030-02-10' });
	assert.equal(solapado.status, 400);

	const actualizado = await http('PUT', `/vacaciones/${creado.body.id}`, { fechaInicio: '2030-02-04', fechaFin: '2030-02-05' });
	assert.equal(actualizado.status, 200);
	assert.equal(actualizado.body.diasHabiles, 2);

	const otro = await http('POST', '/vacaciones', { empleadoId: EMPLEADO, fechaInicio: '2030-03-04', fechaFin: '2030-03-04' });
	assert.equal(otro.status, 201);
	assert.equal((await http('DELETE', `/vacaciones/${otro.body.id}`)).body.estado, 'CANCELADA');

	const lista = await http('GET', `/vacaciones?empleadoId=${EMPLEADO}`);
	assert.deepEqual(lista.body.map((p) => p.estado), ['PROGRAMADA', 'CANCELADA']);
	assert.equal((await http('GET', `/vacaciones/${creado.body.id}`)).status, 200);
	assert.equal((await http('GET', '/vacaciones/V-1999-0001')).status, 404);

	reloj = new Date('2030-02-04T08:00:00Z');
	let ciclo = await servicioIt.ejecutarCiclo();
	assert.deepEqual(ciclo.iniciados.map((p) => p.id), [creado.body.id]);
	reloj = new Date('2030-02-06T08:00:00Z');
	ciclo = await servicioIt.ejecutarCiclo();
	assert.deepEqual(ciclo.finalizados.map((p) => p.id), [creado.body.id]);

	const forzado = await http('POST', '/vacaciones', { empleadoId: EMPLEADO, fechaInicio: '2030-04-01', fechaFin: '2030-04-02' });
	assert.equal((await http('POST', `/vacaciones/${forzado.body.id}/forzar-inicio`)).body.estado, 'EN_CURSO');
	assert.equal((await http('POST', `/vacaciones/${forzado.body.id}/forzar-fin`)).body.estado, 'FINALIZADA');

	// Tres altas (programadas), un inicio por cron y otro forzado, un fin por cron y otro forzado.
	await esperar(() => recibidos.length >= 7);
	const cuenta = (tipo) => recibidos.filter((e) => e.type === tipo).length;
	assert.equal(cuenta('vacaciones.programadas'), 3);
	assert.equal(cuenta('vacaciones.iniciadas'), 2);
	assert.equal(cuenta('vacaciones.finalizadas'), 2);
	for (const evento of recibidos) {
		assert.equal(evento.producer, 'vacaciones-service');
		assert.equal(evento.data.empleadoId, EMPLEADO);
	}
});

test('dos réplicas del scheduler: el advisory lock real deja ejecutar solo a una', { skip: omitir }, async () => {
	let ejecuciones = 0;
	const cicloLento = async () => {
		ejecuciones += 1;
		await new Promise((r) => setTimeout(r, 500));
		return { iniciados: [], finalizados: [] };
	};
	// Dos pools = dos réplicas con su propia conexión a la misma base.
	const otroPool = crearPool(databaseUrl);
	try {
		const [a, b] = await Promise.all([
			conLockDeScheduler(pool, cicloLento)(),
			conLockDeScheduler(otroPool, cicloLento)(),
		]);
		assert.equal(ejecuciones, 1);
		assert.equal([a, b].filter((r) => r.omitido).length, 1);
		// Al terminar se libera: el siguiente tick lo toma cualquiera.
		await conLockDeScheduler(otroPool, cicloLento)();
		assert.equal(ejecuciones, 2);
	} finally {
		await otroPool.end();
	}
});

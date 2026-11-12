// Piezas de arranque que antes quedaban fuera de la medición: configuración, scheduler, pool y logger.
import assert from 'node:assert/strict';
import { mkdtempSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import test from 'node:test';
import { loadConfig } from '../src/config.js';
import { baseResponde, crearPool } from '../src/db.js';
import { installConsoleLogger, log } from '../src/logger.js';
import { iniciarScheduler } from '../src/scheduler.js';

test('loadConfig arma la URL del broker y de la base desde variables y *_FILE', () => {
	const dir = mkdtempSync(join(tmpdir(), 'vac-'));
	writeFileSync(join(dir, 'rabbit'), 'p@ss\n');
	writeFileSync(join(dir, 'db'), 's3cr/to\n');
	const config = loadConfig({
		BROKER_URL: 'amqp://broker:5672', RABBITMQ_USER: 'u',
		RABBITMQ_PASSWORD_FILE: join(dir, 'rabbit'), DB_PASSWORD_FILE: join(dir, 'db'),
		DB_HOST: 'pg', PORT: '9000', HEALTH_TIMEOUT_MS: '1500',
	});
	assert.equal(config.amqpUrl, 'amqp://u:p%40ss@broker:5672');
	assert.equal(config.databaseUrl, 'postgresql://vacaciones:s3cr%2Fto@pg:5432/vacaciones_db');
	assert.equal(config.port, 9000);
	assert.equal(config.healthTimeoutMs, 1500);
	assert.equal(config.queue, 'q.vacaciones');
	assert.equal(config.cron, '* * * * *');
});

test('loadConfig respeta DATABASE_URL y exige las contraseñas', () => {
	const config = loadConfig({ RABBITMQ_PASSWORD: 'x', DATABASE_URL: 'postgresql://a/b' });
	assert.equal(config.databaseUrl, 'postgresql://a/b');
	assert.throws(() => loadConfig({}), /RABBITMQ_PASSWORD/);
	assert.throws(() => loadConfig({ RABBITMQ_PASSWORD: 'x' }), /DB_PASSWORD/);
});

test('iniciarScheduler rechaza una expresión cron inválida', () => {
	assert.throws(() => iniciarScheduler({ expresion: 'cada rato', ejecutarCiclo: async () => ({}) }), /no es una expresión cron válida/);
});

test('iniciarScheduler ejecuta el ciclo y sobrevive a un ciclo fallido', async () => {
	const llamadas = [];
	let falla = false;
	const tarea = iniciarScheduler({
		expresion: '* * * * * *',
		ejecutarCiclo: async () => {
			llamadas.push(Date.now());
			if (falla) throw new Error('base caída');
			falla = true;
			return { iniciados: [{ id: 'V-1' }], finalizados: [] };
		},
	});
	try {
		await new Promise((r) => setTimeout(r, 2_300));
	} finally {
		tarea.stop();
	}
	assert.ok(llamadas.length >= 2, `se esperaban dos disparos, hubo ${llamadas.length}`);
});

test('baseResponde es false si la consulta falla y true si responde', async () => {
	assert.equal(await baseResponde({ query: async () => { throw new Error('caída'); } }), false);
	assert.equal(await baseResponde({ query: async () => ({ rows: [] }) }), true);
	const pool = crearPool('postgresql://u:p@127.0.0.1:1/x');
	assert.equal(typeof pool.query, 'function');
	await pool.end();
});

test('el logger escribe JSON con service y redirige console', (t) => {
	const salida = [];
	t.mock.method(process.stdout, 'write', (linea) => { salida.push(linea); return true; });
	const originales = { log: console.log, error: console.error, warn: console.warn };
	try {
		log('INFO', 'hola', { eventId: 'e1' });
		installConsoleLogger();
		console.error(new Error('boom'));
		console.warn({ a: 1 });
		console.log('texto');
	} finally {
		Object.assign(console, originales);
	}
	const lineas = salida.map((l) => JSON.parse(l));
	assert.equal(lineas[0].service, 'vacaciones-service');
	assert.equal(lineas[0].eventId, 'e1');
	assert.deepEqual(lineas.slice(1).map((l) => [l.level, l.message]), [['ERROR', 'boom'], ['WARN', '{"a":1}'], ['INFO', 'texto']]);
});

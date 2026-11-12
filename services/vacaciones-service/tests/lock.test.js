import assert from 'node:assert/strict';
import test from 'node:test';
import { conLockDeScheduler } from '../src/lock.js';

// Doble del pool: simula el advisory lock de PostgreSQL compartido entre "réplicas".
function poolConLock() {
	const estado = { tomado: false, sentencias: [], liberados: 0 };
	return {
		estado,
		connect: async () => ({
			query: async (sql) => {
				estado.sentencias.push(sql);
				if (sql.includes('pg_try_advisory_lock')) {
					const ok = !estado.tomado;
					estado.tomado = true;
					return { rows: [{ tomado: ok }] };
				}
				if (sql.includes('pg_advisory_unlock')) estado.tomado = false;
				return { rows: [] };
			},
			release: () => { estado.liberados += 1; },
		}),
	};
}

test('con el lock libre ejecuta el ciclo y lo libera al terminar', async () => {
	const pool = poolConLock();
	const resultado = await conLockDeScheduler(pool, async () => ({ iniciados: [1], finalizados: [] }))();
	assert.deepEqual(resultado.iniciados, [1]);
	assert.equal(pool.estado.tomado, false);
	assert.equal(pool.estado.liberados, 1);
});

test('si otra réplica tiene el lock el tick se omite sin ejecutar el ciclo', async () => {
	const pool = poolConLock();
	pool.estado.tomado = true;
	let ejecutado = false;
	const resultado = await conLockDeScheduler(pool, async () => { ejecutado = true; })();
	assert.equal(ejecutado, false);
	assert.equal(resultado.omitido, true);
	assert.ok(!pool.estado.sentencias.some((s) => s.includes('pg_advisory_unlock')), 'no libera un lock ajeno');
});

test('libera el lock aunque el ciclo falle', async () => {
	const pool = poolConLock();
	await assert.rejects(conLockDeScheduler(pool, async () => { throw new Error('base caída'); })(), /base caída/);
	assert.equal(pool.estado.tomado, false);
	assert.equal(pool.estado.liberados, 1);
});

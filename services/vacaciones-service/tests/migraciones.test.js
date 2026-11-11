// E05: cada migración de node-pg-migrate tiene `down` y deshace exactamente lo que crea `up`.
// Sin Docker no hay PostgreSQL: se ejecutan up/down contra un `pgm` falso que registra las llamadas.
import assert from 'node:assert/strict';
import { readdirSync } from 'node:fs';
import { createRequire } from 'node:module';
import { dirname, join } from 'node:path';
import test from 'node:test';
import { fileURLToPath } from 'node:url';

const require = createRequire(import.meta.url);
const carpeta = join(dirname(fileURLToPath(import.meta.url)), '..', 'migrations');
const archivos = readdirSync(carpeta).filter((f) => f.endsWith('.cjs')).sort();

function pgmFalso() {
	const llamadas = [];
	const registrar = (nombre) => (...args) => llamadas.push([nombre, ...args]);
	return {
		llamadas,
		pgm: new Proxy({}, { get: (_t, nombre) => registrar(String(nombre)) }),
	};
}

test('hay migraciones y todas exportan up y down', () => {
	assert.ok(archivos.length > 0);
	for (const archivo of archivos) {
		const migracion = require(join(carpeta, archivo));
		assert.equal(typeof migracion.up, 'function', `${archivo} sin up`);
		assert.equal(typeof migracion.down, 'function', `${archivo} sin down`);
	}
});

test('down borra todas las tablas que crea up, en orden inverso', () => {
	const creadas = [];
	const borradas = [];
	for (const archivo of archivos) {
		const migracion = require(join(carpeta, archivo));
		const subida = pgmFalso();
		migracion.up(subida.pgm);
		creadas.push(...subida.llamadas.filter(([op]) => op === 'createTable').map(([, tabla]) => tabla));
		const bajada = pgmFalso();
		migracion.down(bajada.pgm);
		borradas.push(...bajada.llamadas.filter(([op]) => op === 'dropTable').map(([, tabla]) => tabla));
	}
	assert.deepEqual(borradas, [...creadas].reverse());
});

test('la unicidad está en el esquema: PK en id de período, empleado, año de secuencia y evento procesado', () => {
	const { up } = require(join(carpeta, '001_inicial.cjs'));
	const { pgm, llamadas } = pgmFalso();
	up(pgm);
	const pk = Object.fromEntries(llamadas
		.filter(([op]) => op === 'createTable')
		.map(([, tabla, columnas]) => [tabla, Object.keys(columnas).filter((c) => columnas[c].primaryKey)]));
	assert.deepEqual(pk, {
		empleados_replica: ['empleado_id'],
		secuencias: ['anio'],
		vacaciones: ['id'],
		eventos_procesados: ['id'],
	});
});

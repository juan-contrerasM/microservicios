// Fase 5: contrato HTTP de vacaciones-service (health C2, error C3, OpenAPI E08) sin Docker.
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { describe, it, before, after } from 'node:test';
import { fileURLToPath } from 'node:url';
import { execFileSync } from 'node:child_process';
import SwaggerParser from '@apidevtools/swagger-parser';
import { parse } from 'yaml';
import { createApp, VERSION } from '../src/app.js';
import { ErrorApi, esBaseCaida } from '../src/errores.js';
import { conTimeout, evaluarChecks } from '../src/salud.js';

const raiz = join(dirname(fileURLToPath(import.meta.url)), '..');
const CAMPOS_C3 = ['error', 'message', 'path', 'status', 'timestamp', 'traceId'];
const TRACEPARENT = '00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01';
const PERIODO = { id: 'V-2027-0001', empleadoId: 'E001', fechaInicio: '2027-03-15', fechaFin: '2027-03-30', estado: 'PROGRAMADA' };

async function levantar(app) {
	const server = app.listen(0);
	await new Promise((resolve) => server.once('listening', resolve));
	return { server, base: `http://127.0.0.1:${server.address().port}` };
}

function cerrar(server) {
	return new Promise((resolve) => server.close(resolve));
}

function esC3(cuerpo, status, path) {
	assert.deepEqual(Object.keys(cuerpo).filter((k) => CAMPOS_C3.includes(k)).sort(), CAMPOS_C3);
	assert.equal(cuerpo.status, status);
	assert.equal(cuerpo.path, path);
	assert.match(cuerpo.timestamp, /Z$/);
	assert.doesNotMatch(JSON.stringify(cuerpo), /at .*\.js:\d+/);
}

describe('health (C2)', () => {
	it('UP incluye version y checks', async (t) => {
		const { server, base } = await levantar(createApp({
			servicio: {},
			estaSano: async () => ({ database: 'UP', messageBroker: 'UP' }),
		}));
		t.after(() => cerrar(server));
		const res = await fetch(`${base}/health`);
		assert.equal(res.status, 200);
		assert.deepEqual(await res.json(), {
			status: 'UP', service: 'vacaciones-service', version: VERSION,
			checks: { database: 'UP', messageBroker: 'UP' },
		});
	});

	it('503 si la base está DOWN', async (t) => {
		const { server, base } = await levantar(createApp({
			servicio: {},
			estaSano: async () => ({ database: 'DOWN', messageBroker: 'UP' }),
		}));
		t.after(() => cerrar(server));
		const res = await fetch(`${base}/health`);
		assert.equal(res.status, 503);
		assert.equal((await res.json()).status, 'DOWN');
	});

	it('responde 503 en menos de 3 s aunque la dependencia cuelgue', async (t) => {
		const { server, base } = await levantar(createApp({
			servicio: {},
			estaSano: () => new Promise(() => {}),
			healthTimeoutMs: 2_000,
		}));
		t.after(() => cerrar(server));
		const inicio = Date.now();
		const res = await fetch(`${base}/health`);
		assert.ok(Date.now() - inicio < 3_000, `tardó ${Date.now() - inicio} ms`);
		assert.equal(res.status, 503);
		assert.deepEqual((await res.json()).checks, { database: 'DOWN', messageBroker: 'DOWN' });
	});

	it('evaluarChecks acota cada check por separado', async () => {
		const inicio = Date.now();
		const checks = await evaluarChecks({
			database: () => new Promise(() => {}),
			messageBroker: () => true,
		}, 200);
		assert.ok(Date.now() - inicio < 1_000);
		assert.deepEqual(checks, { database: 'DOWN', messageBroker: 'UP' });
		assert.deepEqual(await evaluarChecks({ database: async () => { throw new Error('x'); } }, 200), { database: 'DOWN' });
		assert.equal(await conTimeout(() => 'ok', 100, 'no'), 'ok');
	});
});

describe('errores (C3)', () => {
	let server;
	let base;
	before(async () => {
		const fallo = (error) => async () => { throw error; };
		const servicio = {
			obtener: fallo(new ErrorApi(404, { mensaje: 'El período de vacaciones con id V-9 no existe' })),
			programar: fallo(new ErrorApi(400, {
				mensaje: 'El empleado ya tiene un período que se solapa con las fechas solicitadas',
				periodoEnConflicto: PERIODO,
			})),
			listar: fallo(Object.assign(new Error('connect ECONNREFUSED 10.0.0.7:5432'), { code: 'ECONNREFUSED' })),
			cancelar: fallo(new TypeError('secreto interno en la pila')),
		};
		({ server, base } = await levantar(createApp({ servicio, estaSano: async () => true })));
	});
	after(() => cerrar(server));

	it('404 de negocio sigue C3 y toma el traceId de traceparent', async () => {
		const res = await fetch(`${base}/vacaciones/V-9`, { headers: { traceparent: TRACEPARENT } });
		assert.equal(res.status, 404);
		const cuerpo = await res.json();
		esC3(cuerpo, 404, '/vacaciones/V-9');
		assert.equal(cuerpo.error, 'Not Found');
		assert.equal(cuerpo.message, 'El período de vacaciones con id V-9 no existe');
		assert.equal(cuerpo.traceId, '4bf92f3577b34da6a3ce929d0e0e4736');
	});

	it('400 por solapamiento conserva periodoEnConflicto', async () => {
		const res = await fetch(`${base}/vacaciones`, {
			method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ empleadoId: 'E001' }),
		});
		assert.equal(res.status, 400);
		const cuerpo = await res.json();
		esC3(cuerpo, 400, '/vacaciones');
		assert.deepEqual(cuerpo.periodoEnConflicto, PERIODO);
		assert.equal(cuerpo.mensaje, undefined);
	});

	it('JSON mal formado responde 400 C3', async () => {
		const res = await fetch(`${base}/vacaciones`, {
			method: 'POST', headers: { 'Content-Type': 'application/json' }, body: '{',
		});
		assert.equal(res.status, 400);
		const cuerpo = await res.json();
		esC3(cuerpo, 400, '/vacaciones');
		assert.equal(cuerpo.message, 'El cuerpo de la petición no es JSON válido');
	});

	it('ruta inexistente responde 404 C3', async () => {
		const res = await fetch(`${base}/no-existe?x=1`);
		assert.equal(res.status, 404);
		esC3(await res.json(), 404, '/no-existe');
	});

	it('base caída responde 503 sin la IP del driver', async () => {
		const res = await fetch(`${base}/vacaciones`);
		assert.equal(res.status, 503);
		const texto = await res.text();
		esC3(JSON.parse(texto), 503, '/vacaciones');
		assert.doesNotMatch(texto, /10\.0\.0\.7/);
	});

	it('error inesperado responde 500 sin stack trace', async () => {
		const res = await fetch(`${base}/vacaciones/V-1`, { method: 'DELETE' });
		assert.equal(res.status, 500);
		const texto = await res.text();
		esC3(JSON.parse(texto), 500, '/vacaciones/V-1');
		assert.equal(JSON.parse(texto).message, 'Error interno del servidor');
		assert.doesNotMatch(texto, /secreto interno|TypeError/);
	});

	it('esBaseCaida reconoce errores de conexión de pg', () => {
		assert.ok(esBaseCaida({ code: '08006' }));
		assert.ok(esBaseCaida({ code: '57P01' }));
		assert.ok(esBaseCaida(new Error('timeout exceeded when trying to connect')));
		assert.ok(!esBaseCaida({ code: '23505' }));
		assert.ok(!esBaseCaida(undefined));
	});
});

describe('OpenAPI (E08)', () => {
	const servido = () => JSON.parse(readFileSync(join(raiz, 'src', 'openapi.json'), 'utf8'));

	it('openapi.yaml exportado no difiere del contrato que sirve la app', async (t) => {
		const { server, base } = await levantar(createApp({ servicio: {}, estaSano: async () => true }));
		t.after(() => cerrar(server));
		const http = await (await fetch(`${base}/openapi.json`)).json();
		const exportado = parse(readFileSync(join(raiz, 'openapi.yaml'), 'utf8'));
		assert.deepEqual(exportado, http, 'openapi.yaml desactualizado: ejecute npm run openapi:export');
		execFileSync(process.execPath, [join(raiz, 'scripts', 'exportar-openapi.js'), '--check']);
	});

	it('es un OpenAPI válido', async () => {
		await SwaggerParser.validate(servido());
	});

	it('documenta bearer, códigos reales y el error C3', () => {
		const spec = servido();
		assert.equal(spec.components.securitySchemes.BearerAuth.scheme, 'bearer');
		assert.deepEqual(spec.security, [{ BearerAuth: [] }]);
		assert.deepEqual(spec.paths['/health'].get.security, []);
		assert.deepEqual(Object.keys(spec.paths['/health'].get.responses), ['200', '503']);
		assert.deepEqual(spec.components.schemas.Error.required.slice().sort(), CAMPOS_C3);
		for (const [ruta, operaciones] of Object.entries(spec.paths)) {
			if (ruta === '/health') continue;
			for (const [metodo, op] of Object.entries(operaciones)) {
				const codigos = Object.keys(op.responses);
				for (const codigo of ['401', '500', '503']) {
					assert.ok(codigos.includes(codigo), `${metodo} ${ruta} sin ${codigo}`);
				}
				if (metodo !== 'get') assert.ok(codigos.includes('403'), `${metodo} ${ruta} sin 403`);
			}
		}
		const casos = spec.paths['/vacaciones'].post.responses['400'].content['application/json'].examples;
		assert.deepEqual(
			Object.keys(casos),
			['fechas', 'pasada', 'empleadoInexistente', 'empleadoRetirado', 'solapamiento', 'json'],
		);
		assert.ok(spec.paths['/vacaciones/{id}'].put.responses['400'].content['application/json'].examples.solapamiento);
	});
});

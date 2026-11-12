// Fase 5 (5.GW): rutas del PDF, errores C3, OpenAPI propio y Swagger UI agregada, sin Docker.
import { createServer } from 'node:http';
import { execFileSync } from 'node:child_process';
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { after, before, describe, it } from 'node:test';
import { fileURLToPath } from 'node:url';
import assert from 'node:assert/strict';
import express from 'express';
import jwt from 'jsonwebtoken';
import request from 'supertest';
import SwaggerParser from '@apidevtools/swagger-parser';
import { parse as parseYaml } from 'yaml';
import { createApp } from '../src/app.js';
import { loadConfig } from '../src/config.js';
import { manejarError } from '../src/errors.js';
import { RUTAS_OPENAPI_POR_DEFECTO } from '../src/swagger.js';

const SECRET = 'secreto-de-prueba-con-al-menos-32-bytes';
const raiz = join(dirname(fileURLToPath(import.meta.url)), '..');
const CAMPOS_C3 = ['error', 'message', 'path', 'status', 'timestamp', 'traceId'];
const TRACEPARENT = '00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01';
const admin = `Bearer ${jwt.sign({ sub: 'admin', role: 'ADMIN' }, SECRET, { expiresIn: '1h' })}`;
const user = `Bearer ${jwt.sign({ sub: 'E001', role: 'USER' }, SECRET, { expiresIn: '1h' })}`;

// Tabla "Operaciones expuestas" del API Gateway en docs/proyecto-final/proyectoFinal.pdf.
const RUTAS_PDF = [
	['post', '/auth/login', '/auth/login', 'auth'],
	['post', '/auth/reset-password', '/auth/reset-password', 'auth'],
	['post', '/auth/recover-password', '/auth/recover-password', 'auth'],
	['post', '/auth/change-password', '/auth/change-password', 'auth'],
	['get', '/empleados', '/empleados', 'empleados'],
	['post', '/empleados', '/empleados', 'empleados'],
	['get', '/empleados/{id}', '/empleados/E001', 'empleados'],
	['put', '/empleados/{id}', '/empleados/E001', 'empleados'],
	['delete', '/empleados/{id}', '/empleados/E001', 'empleados'],
	['get', '/departamentos', '/departamentos', 'departamentos'],
	['post', '/departamentos', '/departamentos', 'departamentos'],
	['get', '/perfiles/{empleadoId}', '/perfiles/E001', 'perfiles'],
	['put', '/perfiles/{empleadoId}', '/perfiles/E001', 'perfiles'],
	['post', '/vacaciones', '/vacaciones', 'vacaciones'],
	['get', '/vacaciones', '/vacaciones', 'vacaciones'],
];

// Rutas que el Gateway ya exponía además de las del PDF (Retos 3–5 y Fase 3).
const RUTAS_EXTRA = [
	['get', '/departamentos/{id}', '/departamentos/IT', 'departamentos'],
	['put', '/departamentos/{id}', '/departamentos/IT', 'departamentos'],
	['delete', '/departamentos/{id}', '/departamentos/IT', 'departamentos'],
	['get', '/empleados/circuit-breaker', '/empleados/circuit-breaker', 'empleados'],
	['post', '/empleados/reconciliar', '/empleados/reconciliar', 'empleados'],
	['get', '/perfiles', '/perfiles', 'perfiles'],
	['get', '/vacaciones/{id}', '/vacaciones/V-2027-0001', 'vacaciones'],
	['put', '/vacaciones/{id}', '/vacaciones/V-2027-0001', 'vacaciones'],
	['delete', '/vacaciones/{id}', '/vacaciones/V-2027-0001', 'vacaciones'],
	['post', '/vacaciones/{id}/forzar-inicio', '/vacaciones/V-2027-0001/forzar-inicio', 'vacaciones'],
	['post', '/vacaciones/{id}/forzar-fin', '/vacaciones/V-2027-0001/forzar-fin', 'vacaciones'],
	['get', '/notificaciones', '/notificaciones', 'notificaciones'],
	['get', '/notificaciones/{empleadoId}', '/notificaciones/E001', 'notificaciones'],
];

function listen(handler) {
	return new Promise((resolve) => {
		const server = createServer(handler);
		server.listen(0, '127.0.0.1', () => {
			resolve({ server, url: `http://127.0.0.1:${server.address().port}` });
		});
	});
}

function cerrar({ server }) {
	server.closeAllConnections?.();
	return new Promise((resolve) => server.close(resolve));
}

function esC3(body, status, path) {
	assert.deepEqual(Object.keys(body).sort(), CAMPOS_C3);
	assert.equal(body.status, status);
	assert.equal(body.path, path);
	assert.match(body.timestamp, /Z$/);
}

function contratoDe(titulo, extra = {}) {
	return {
		openapi: '3.0.3',
		info: { title: titulo, version: '1.0.0' },
		servers: [{ url: 'http://servicio-interno:9999' }],
		paths: {},
		...extra,
	};
}

const servido = () => JSON.parse(readFileSync(join(raiz, 'src', 'openapi.json'), 'utf8'));

describe('auditoría de rutas del PDF', () => {
	const backends = {};
	let app;

	before(async () => {
		for (const nombre of ['auth', 'empleados', 'departamentos', 'perfiles', 'vacaciones', 'notificaciones']) {
			backends[nombre] = await listen((req, res) => {
				res.writeHead(200, { 'Content-Type': 'application/json', 'X-Backend': nombre });
				res.end(JSON.stringify({ metodo: req.method, ruta: req.url }));
			});
		}
		app = createApp({
			authUrl: backends.auth.url,
			empleadosUrl: backends.empleados.url,
			departamentosUrl: backends.departamentos.url,
			perfilesUrl: backends.perfiles.url,
			vacacionesUrl: backends.vacaciones.url,
			notificacionesUrl: backends.notificaciones.url,
			jwtSecret: SECRET,
			proxyTimeoutMs: 2_000,
		});
	});

	after(async () => {
		await Promise.all(Object.values(backends).map(cerrar));
	});

	it('el OpenAPI del Gateway documenta cada ruta del PDF y las extra', () => {
		const spec = servido();
		for (const [metodo, plantilla] of [...RUTAS_PDF, ...RUTAS_EXTRA]) {
			assert.ok(spec.paths[plantilla]?.[metodo], `${metodo.toUpperCase()} ${plantilla} falta en src/openapi.json`);
		}
		assert.equal(spec.paths['/auth/register'], undefined, 'no hay registro público');
	});

	for (const [metodo, plantilla, ruta, backend] of [...RUTAS_PDF, ...RUTAS_EXTRA]) {
		it(`${metodo.toUpperCase()} ${plantilla} llega a ${backend}-service sin cambiar el path`, async () => {
			const res = await request(app)[metodo](ruta)
				.set('Authorization', admin)
				.set('Content-Type', 'application/json')
				.send(metodo === 'get' || metodo === 'delete' ? undefined : {})
				.expect(200);
			assert.equal(res.headers['x-backend'], backend);
			assert.equal(res.body.ruta, ruta);
			assert.equal(res.body.metodo, metodo.toUpperCase());
		});
	}

	it('RBAC: USER lee y cambia su clave; no escribe en negocio', async () => {
		for (const [metodo, , ruta] of RUTAS_PDF) {
			const publica = ['/auth/login', '/auth/reset-password', '/auth/recover-password'].includes(ruta);
			const permitido = publica || metodo === 'get' || ruta === '/auth/change-password'
				|| (metodo === 'put' && ruta === '/perfiles/E001');
			const res = await request(app)[metodo](ruta).set('Authorization', user).send({});
			assert.equal(res.status, permitido ? 200 : 403, `${metodo.toUpperCase()} ${ruta} como USER`);
		}
	});

	it('las rutas públicas del PDF no exigen JWT y las demás sí', async () => {
		for (const [metodo, , ruta] of RUTAS_PDF) {
			const publica = ['/auth/login', '/auth/reset-password', '/auth/recover-password'].includes(ruta);
			const res = await request(app)[metodo](ruta).send({});
			assert.equal(res.status, publica ? 200 : 401, `${metodo.toUpperCase()} ${ruta} sin token`);
		}
	});
});

describe('errores C3 del Gateway', () => {
	const app = createApp({
		empleadosUrl: 'http://127.0.0.1:1',
		departamentosUrl: 'http://127.0.0.1:1',
		authUrl: 'http://127.0.0.1:1',
		jwtSecret: SECRET,
		proxyTimeoutMs: 500,
	});

	it('401 sin token, con traceId del traceparent', async () => {
		const res = await request(app).get('/empleados?estado=RETIRADO').set('traceparent', TRACEPARENT).expect(401);
		esC3(res.body, 401, '/empleados');
		assert.equal(res.body.error, 'Unauthorized');
		assert.equal(res.body.traceId, '4bf92f3577b34da6a3ce929d0e0e4736');
	});

	it('403 por rol', async () => {
		const res = await request(app).post('/departamentos').set('Authorization', user).send({}).expect(403);
		esC3(res.body, 403, '/departamentos');
	});

	it('404 en una ruta que el Gateway no expone (incluido /auth/register sin backend)', async () => {
		const res = await request(app).get('/pagos').set('Authorization', admin).expect(404);
		esC3(res.body, 404, '/pagos');
		assert.equal(res.body.message, 'Recurso no encontrado');
	});

	it('503 C3 cuando el destino no responde', async () => {
		const res = await request(app).get('/empleados/E001').set('Authorization', admin).expect(503);
		esC3(res.body, 503, '/empleados/E001');
		assert.equal(res.body.error, 'Service Unavailable');
	});

	it('500 C3 sin pila ni mensaje interno', async () => {
		const mini = express();
		mini.get('/explota', () => {
			throw new Error('detalle interno con /ruta/secreta.js:12');
		});
		mini.use(manejarError);
		const res = await request(mini).get('/explota').expect(500);
		esC3(res.body, 500, '/explota');
		assert.equal(res.body.message, 'Error interno del servidor');
		assert.doesNotMatch(res.text, /secreta|detalle interno/);
	});
});

describe('OpenAPI propio del Gateway (E08)', () => {
	it('openapi.yaml exportado no difiere del contrato servido en /openapi.json', async () => {
		const app = createApp({
			empleadosUrl: 'http://127.0.0.1:1', departamentosUrl: 'http://127.0.0.1:1', authUrl: 'http://127.0.0.1:1', jwtSecret: SECRET,
		});
		const http = (await request(app).get('/openapi.json').expect(200)).body;
		const exportado = parseYaml(readFileSync(join(raiz, 'openapi.yaml'), 'utf8'));
		assert.deepEqual(exportado, http, 'openapi.yaml desactualizado: ejecute npm run openapi:export');
		execFileSync(process.execPath, [join(raiz, 'scripts', 'exportar-openapi.js'), '--check']);
	});

	it('es un OpenAPI válido', async () => {
		await SwaggerParser.validate(servido());
	});

	it('BearerAuth en negocio y en change-password; público en health, swagger y login/recover/reset', () => {
		const spec = servido();
		assert.equal(spec.components.securitySchemes.BearerAuth.scheme, 'bearer');
		assert.deepEqual(spec.security, [{ BearerAuth: [] }]);
		const publicas = new Set([
			'get /health', 'get /health/services', 'get /swagger', 'get /swagger/specs/{servicio}', 'get /openapi.json',
			'post /auth/login', 'post /auth/recover-password', 'post /auth/reset-password',
		]);
		for (const [ruta, operaciones] of Object.entries(spec.paths)) {
			for (const [metodo, op] of Object.entries(operaciones)) {
				const clave = `${metodo} ${ruta}`;
				if (publicas.has(clave)) {
					assert.deepEqual(op.security, [], `${clave} debe ser pública`);
				} else {
					assert.equal(op.security, undefined, `${clave} debe heredar BearerAuth`);
					for (const codigo of ['401', '500', '503']) {
						assert.ok(op.responses[codigo], `${clave} sin ${codigo}`);
					}
				}
			}
		}
		assert.deepEqual(spec.components.schemas.Error.required.slice().sort(), CAMPOS_C3);
	});
});

describe('Swagger UI agregada en /swagger', () => {
	const backends = {};
	let app;
	let perfilesColgado;

	before(async () => {
		backends.empleados = await listen((req, res) => {
			assert.equal(req.url, '/v3/api-docs');
			res.writeHead(200, { 'Content-Type': 'application/json' });
			res.end(JSON.stringify(contratoDe('empleados-service')));
		});
		backends.departamentos = await listen((req, res) => {
			assert.equal(req.url, '/openapi.yaml');
			res.writeHead(200, { 'Content-Type': 'application/yaml' });
			res.end('openapi: 3.0.3\ninfo:\n  title: departamentos-service\n  version: 1.0.0\npaths: {}\n');
		});
		backends.auth = await listen((req, res) => {
			assert.equal(req.url, '/openapi.json');
			res.writeHead(200, { 'Content-Type': 'application/json' });
			res.end(JSON.stringify(contratoDe('auth-service', { openapi: '3.1.0' })));
		});
		backends.vacaciones = await listen((_req, res) => {
			res.writeHead(500, { 'Content-Type': 'text/plain' });
			res.end('boom');
		});
		// Acepta la conexión y nunca responde: simula un backend colgado.
		perfilesColgado = await listen(() => {});
		app = createApp({
			authUrl: backends.auth.url,
			empleadosUrl: backends.empleados.url,
			departamentosUrl: backends.departamentos.url,
			perfilesUrl: perfilesColgado.url,
			vacacionesUrl: backends.vacaciones.url,
			// notificacionesUrl sin configurar
			jwtSecret: SECRET,
			swaggerTimeoutMs: 300,
		});
	});

	after(async () => {
		await Promise.all([...Object.values(backends), perfilesColgado].map(cerrar));
	});

	it('GET /swagger es público y lista los siete contratos', async () => {
		const res = await request(app).get('/swagger').expect(200);
		assert.match(res.headers['content-type'], /html/);
		for (const nombre of ['api-gateway', ...Object.keys(RUTAS_OPENAPI_POR_DEFECTO)]) {
			assert.ok(res.text.includes(`"url":"/swagger/specs/${nombre}"`), nombre);
		}
		assert.equal(Object.keys(RUTAS_OPENAPI_POR_DEFECTO).length, 6);
		assert.doesNotMatch(res.text, /__SPECS__|unpkg|cdn/);
		await request(app).get('/swagger/index.html').expect(200);
		await request(app).get('/swagger/').expect(200);
	});

	it('sirve los assets de swagger-ui-dist desde el propio Gateway', async () => {
		const js = await request(app).get('/swagger/ui/swagger-ui-bundle.js').expect(200);
		assert.match(js.headers['content-type'], /javascript/);
		await request(app).get('/swagger/ui/swagger-ui.css').expect(200);
		await request(app).get('/swagger/ui/swagger-ui-standalone-preset.js').expect(200);
	});

	it('/swagger/specs/api-gateway es el contrato propio', async () => {
		const res = await request(app).get('/swagger/specs/api-gateway').expect(200);
		assert.equal(res.body.info.title, 'api-gateway');
	});

	it('trae el contrato JSON de un backend y apunta servers al Gateway', async () => {
		const res = await request(app).get('/swagger/specs/empleados-service').expect(200);
		assert.equal(res.body.info.title, 'empleados-service');
		assert.equal(res.body.servers.length, 1);
		assert.equal(res.body.servers[0].url, '/');
		const auth = await request(app).get('/swagger/specs/auth-service').expect(200);
		assert.equal(auth.body.openapi, '3.1.0');
	});

	it('convierte a JSON el YAML de Departamentos', async () => {
		const res = await request(app).get('/swagger/specs/departamentos-service').expect(200);
		assert.match(res.headers['content-type'], /json/);
		assert.equal(res.body.info.title, 'departamentos-service');
	});

	it('backend colgado: 503 C3 dentro del timeout, solo en su entrada', async () => {
		const inicio = Date.now();
		const res = await request(app).get('/swagger/specs/perfiles-service').expect(503);
		assert.ok(Date.now() - inicio < 2_000, `tardó ${Date.now() - inicio} ms`);
		esC3(res.body, 503, '/swagger/specs/perfiles-service');
		assert.equal(res.body.message, 'El contrato de perfiles-service no está disponible');
		// Las demás entradas siguen respondiendo.
		await request(app).get('/swagger/specs/empleados-service').expect(200);
	});

	it('backend con 500 o sin URL configurada: 503 C3', async () => {
		const vac = await request(app).get('/swagger/specs/vacaciones-service').expect(503);
		esC3(vac.body, 503, '/swagger/specs/vacaciones-service');
		const notif = await request(app).get('/swagger/specs/notificaciones-service').expect(503);
		esC3(notif.body, 503, '/swagger/specs/notificaciones-service');
	});

	it('servicio desconocido: 404 C3', async () => {
		const res = await request(app).get('/swagger/specs/pagos-service').expect(404);
		esC3(res.body, 404, '/swagger/specs/pagos-service');
	});

	it('respeta la ruta configurada por variable de entorno', async () => {
		let pedida;
		const fetchImpl = async (url) => {
			pedida = url;
			return new Response(JSON.stringify(contratoDe('empleados-service')), { status: 200 });
		};
		const config = loadConfig({
			EMPLEADOS_URL: 'http://empleados-service:8080',
			DEPARTAMENTOS_URL: 'http://departamentos-service:8081',
			AUTH_URL: 'http://auth-service:8086',
			JWT_SECRET: SECRET,
			EMPLEADOS_OPENAPI_PATH: 'docs/openapi',
			SWAGGER_SPEC_TIMEOUT_MS: '1500',
		});
		assert.deepEqual(config.rutasOpenapi, { 'empleados-service': '/docs/openapi' });
		assert.equal(config.swaggerTimeoutMs, 1500);
		const conRuta = createApp({ ...config, fetchImpl });
		await request(conRuta).get('/swagger/specs/empleados-service').expect(200);
		assert.equal(pedida, 'http://empleados-service:8080/docs/openapi');
	});

	it('respuesta que no es OpenAPI: 503', async () => {
		const fetchImpl = async () => new Response('<html>login</html>', { status: 200 });
		const raro = createApp({
			empleadosUrl: 'http://e', departamentosUrl: 'http://d', authUrl: 'http://a', jwtSecret: SECRET, fetchImpl,
		});
		await request(raro).get('/swagger/specs/empleados-service').expect(503);
	});
});

import { createServer } from 'node:http';
import { after, before, describe, it } from 'node:test';
import assert from 'node:assert/strict';
import jwt from 'jsonwebtoken';
import request from 'supertest';
import { createApp } from '../src/app.js';
import { loadConfig } from '../src/config.js';

const SECRET = 'secreto-de-prueba';

function firmar(payload, options = {}) {
	return jwt.sign(payload, SECRET, { algorithm: 'HS256', expiresIn: '1h', ...options });
}

const adminBearer = `Bearer ${firmar({ sub: 'admin', role: 'ADMIN' })}`;
const userBearer = `Bearer ${firmar({ sub: 'E001', role: 'USER' })}`;

function listen(handler) {
	return new Promise((resolve) => {
		const server = createServer(handler);
		server.listen(0, '127.0.0.1', () => {
			const { port } = server.address();
			resolve({ server, url: `http://127.0.0.1:${port}` });
		});
	});
}

function json(res, status, payload, extraHeaders = {}) {
	const body = JSON.stringify(payload);
	res.writeHead(status, {
		'Content-Type': 'application/json; charset=utf-8',
		'X-Backend': extraHeaders['X-Backend'] || 'mock',
		...extraHeaders,
		'Content-Length': Buffer.byteLength(body),
	});
	res.end(body);
}

function collectBody(req) {
	return new Promise((resolve) => {
		const chunks = [];
		req.on('data', (chunk) => chunks.push(chunk));
		req.on('end', () => resolve(Buffer.concat(chunks).toString('utf8')));
	});
}

function baseEnv(extra = {}) {
	return {
		EMPLEADOS_URL: 'http://empleados-service:8080/',
		DEPARTAMENTOS_URL: 'http://departamentos-service:8081/',
		AUTH_URL: 'http://auth-service:8086/',
		JWT_SECRET: SECRET,
		...extra,
	};
}

describe('loadConfig', () => {
	it('exige EMPLEADOS_URL, DEPARTAMENTOS_URL, AUTH_URL y JWT_SECRET', () => {
		assert.throws(() => loadConfig({}), /EMPLEADOS_URL/);
		assert.throws(() => loadConfig({ EMPLEADOS_URL: 'http://e:8080' }), /DEPARTAMENTOS_URL/);
		assert.throws(() => loadConfig({
			EMPLEADOS_URL: 'http://e:8080',
			DEPARTAMENTOS_URL: 'http://d:8081',
		}), /AUTH_URL/);
		assert.throws(() => loadConfig({
			EMPLEADOS_URL: 'http://e:8080',
			DEPARTAMENTOS_URL: 'http://d:8081',
			AUTH_URL: 'http://a:8086',
		}), /JWT_SECRET/);
	});

	it('recorta la barra final de las URLs', () => {
		const config = loadConfig(baseEnv({ PORT: '9090' }));
		assert.equal(config.empleadosUrl, 'http://empleados-service:8080');
		assert.equal(config.departamentosUrl, 'http://departamentos-service:8081');
		assert.equal(config.authUrl, 'http://auth-service:8086');
		assert.equal(config.jwtSecret, SECRET);
		assert.equal(config.port, 9090);
		assert.equal(config.proxyTimeoutMs, 35_000);
	});
});

describe('api-gateway', () => {
	let empleados;
	let departamentos;
	let app;

	before(async () => {
		empleados = await listen(async (req, res) => {
			const body = await collectBody(req);
			if (req.method === 'POST' && req.url.startsWith('/empleados')) {
				json(res, 201, {
					id: 'E001',
					estado: 'ACTIVO',
					echo: body,
					requestId: req.headers['x-request-id'],
				}, { 'X-Backend': 'empleados' });
				return;
			}
			if (req.method === 'GET' && req.url === '/empleados/E001') {
				json(res, 200, { id: 'E001', nombre: 'Juan' }, { 'X-Backend': 'empleados' });
				return;
			}
			if (req.method === 'DELETE' && req.url === '/empleados/E001') {
				json(res, 200, { id: 'E001', estado: 'RETIRADO' }, { 'X-Backend': 'empleados' });
				return;
			}
			json(res, 404, { mensaje: 'no encontrado' }, { 'X-Backend': 'empleados' });
		});

		departamentos = await listen(async (req, res) => {
			if (req.method === 'POST' && req.url.startsWith('/departamentos')) {
				json(res, 400, { mensaje: 'ya existe un departamento registrado con ese id' }, { 'X-Backend': 'departamentos' });
				return;
			}
			if (req.method === 'GET' && req.url === '/departamentos/IT?include=nombre') {
				json(res, 200, { id: 'IT', nombre: 'Tecnología' }, { 'X-Backend': 'departamentos' });
				return;
			}
			json(res, 404, { mensaje: 'no encontrado' }, { 'X-Backend': 'departamentos' });
		});

		app = createApp({
			empleadosUrl: empleados.url,
			departamentosUrl: departamentos.url,
			authUrl: 'http://127.0.0.1:1',
			jwtSecret: SECRET,
			proxyTimeoutMs: 2_000,
		});
	});

	after(async () => {
		await new Promise((resolve) => empleados.server.close(resolve));
		await new Promise((resolve) => departamentos.server.close(resolve));
	});

	it('GET /health es propio y no depende de los backends', async () => {
		const res = await request(app).get('/health').expect(200);
		assert.deepEqual(res.body, { status: 'UP', service: 'api-gateway' });
		assert.match(res.headers['content-type'], /json/);
	});

	it('sirve el OpenAPI del borde y la UI', async () => {
		const spec = await request(app).get('/openapi.json').expect(200);
		assert.equal(spec.body.info.title, 'api-gateway');
		assert.ok(spec.body.paths['/vacaciones']);
		assert.ok(spec.body.paths['/auth/login']);
		assert.ok(spec.body.paths['/auth/change-password']);
		assert.ok(spec.body.paths['/vacaciones/{id}/forzar-inicio']);
		assert.equal(spec.body.components.securitySchemes.BearerAuth.scheme, 'bearer');
		assert.deepEqual(spec.body.paths['/health'].get.security, []);
		assert.deepEqual(spec.body.paths['/auth/login'].post.security, []);
		const ui = await request(app).get('/swagger/index.html').expect(200);
		assert.match(ui.headers['content-type'], /html/);
	});

	it('propaga 201, cuerpo y cabeceras de empleados', async () => {
		const payload = { id: 'E001', nombre: 'Juan' };
		const res = await request(app)
			.post('/empleados')
			.set('Authorization', adminBearer)
			.set('Content-Type', 'application/json')
			.set('X-Request-Id', 'req-1')
			.send(payload)
			.expect(201);

		assert.equal(res.body.id, 'E001');
		assert.equal(res.body.estado, 'ACTIVO');
		assert.equal(res.headers['x-backend'], 'empleados');
		assert.equal(JSON.parse(res.body.echo).id, 'E001');
		assert.equal(res.body.requestId, 'req-1');
	});

	it('propaga 400 de departamentos sin reescribir el payload', async () => {
		const res = await request(app)
			.post('/departamentos')
			.set('Authorization', adminBearer)
			.set('Content-Type', 'application/json')
			.send({ id: 'IT' })
			.expect(400);

		assert.equal(res.body.mensaje, 'ya existe un departamento registrado con ese id');
		assert.equal(res.headers['x-backend'], 'departamentos');
	});

	it('conserva el path y el query string', async () => {
		const res = await request(app)
			.get('/departamentos/IT')
			.set('Authorization', adminBearer)
			.query({ include: 'nombre' })
			.expect(200);

		assert.equal(res.body.id, 'IT');
		assert.equal(res.headers['x-backend'], 'departamentos');
	});

	it('GET /empleados/{id} conserva el prefijo interno', async () => {
		const res = await request(app)
			.get('/empleados/E001')
			.set('Authorization', adminBearer)
			.expect(200);
		assert.equal(res.body.id, 'E001');
	});
});

describe('api-gateway destino caído', () => {
	it('GET /departamentos responde 503 JSON descriptivo, no HTML', async () => {
		const app = createApp({
			empleadosUrl: 'http://127.0.0.1:1',
			departamentosUrl: 'http://127.0.0.1:1',
			authUrl: 'http://127.0.0.1:1',
			jwtSecret: SECRET,
			proxyTimeoutMs: 500,
		});

		const res = await request(app)
			.get('/departamentos')
			.set('Authorization', adminBearer)
			.expect(503);
		assert.match(res.headers['content-type'], /json/);
		assert.equal(res.body.status, 503);
		assert.equal(res.body.servicio, 'departamentos-service');
		assert.equal(res.body.mensaje, 'El servicio de departamentos no está disponible');
		assert.doesNotMatch(res.text, /<html/i);
	});

	it('POST /empleados responde 503 identificando empleados-service', async () => {
		const app = createApp({
			empleadosUrl: 'http://127.0.0.1:1',
			departamentosUrl: 'http://127.0.0.1:1',
			authUrl: 'http://127.0.0.1:1',
			jwtSecret: SECRET,
			proxyTimeoutMs: 500,
		});

		const res = await request(app)
			.post('/empleados')
			.set('Authorization', adminBearer)
			.set('Content-Type', 'application/json')
			.send({ id: 'E001' })
			.expect(503);

		assert.equal(res.body.servicio, 'empleados-service');
		assert.equal(res.body.mensaje, 'El servicio de empleados no está disponible');
	});

	it('POST /auth/login responde 503 identificando auth-service', async () => {
		const app = createApp({
			empleadosUrl: 'http://127.0.0.1:1',
			departamentosUrl: 'http://127.0.0.1:1',
			authUrl: 'http://127.0.0.1:1',
			jwtSecret: SECRET,
			proxyTimeoutMs: 500,
		});

		const res = await request(app)
			.post('/auth/login')
			.set('Content-Type', 'application/json')
			.send({ usuario: 'admin', contrasena: 'Admin1234!' })
			.expect(503);

		assert.equal(res.body.servicio, 'auth-service');
		assert.equal(res.body.mensaje, 'El servicio de auth no está disponible');
	});

	it('GET /health sigue UP cuando los destinos no responden', async () => {
		const app = createApp({
			empleadosUrl: 'http://127.0.0.1:1',
			departamentosUrl: 'http://127.0.0.1:1',
			authUrl: 'http://127.0.0.1:1',
			jwtSecret: SECRET,
		});

		const res = await request(app).get('/health').expect(200);
		assert.equal(res.body.status, 'UP');
		assert.equal(res.body.service, 'api-gateway');
	});
});

describe('api-gateway JWT y RBAC', () => {
	let empleados;
	let perfiles;
	let app;
	let empleadosHits;

	before(async () => {
		empleadosHits = 0;
		empleados = await listen(async (req, res) => {
			empleadosHits += 1;
			if (req.method === 'DELETE' && req.url === '/empleados/E001') {
				json(res, 200, { id: 'E001', estado: 'RETIRADO' });
				return;
			}
			json(res, 200, { id: 'E001' });
		});
		perfiles = await listen(async (req, res) => {
			json(res, 200, { empleadoId: req.url.split('/')[2], telefono: '3001234567' });
		});
		app = createApp({
			empleadosUrl: empleados.url,
			departamentosUrl: 'http://127.0.0.1:1',
			perfilesUrl: perfiles.url,
			authUrl: 'http://127.0.0.1:1',
			jwtSecret: SECRET,
			proxyTimeoutMs: 500,
		});
	});

	after(async () => {
		await new Promise((resolve) => empleados.server.close(resolve));
		await new Promise((resolve) => perfiles.server.close(resolve));
	});

	it('sin header responde 401 y no proxea', async () => {
		const antes = empleadosHits;
		const res = await request(app).get('/empleados').expect(401);
		assert.deepEqual(res.body, { status: 401, mensaje: 'No autenticado' });
		assert.equal(empleadosHits, antes);
	});

	it('una firma alterada responde 401', async () => {
		const token = firmar({ sub: 'admin', role: 'ADMIN' });
		const alterado = `${token.slice(0, -1)}${token.endsWith('a') ? 'b' : 'a'}`;
		const res = await request(app)
			.get('/empleados')
			.set('Authorization', `Bearer ${alterado}`)
			.expect(401);
		assert.equal(res.body.mensaje, 'No autenticado');
	});

	it('un token de reset no sirve como Bearer', async () => {
		const reset = firmar({ sub: 'E001', type: 'RESET_PASSWORD' });
		const res = await request(app)
			.get('/empleados')
			.set('Authorization', `Bearer ${reset}`)
			.expect(401);
		assert.equal(res.body.mensaje, 'No autenticado');
	});

	it('USER no puede borrar un empleado', async () => {
		const antes = empleadosHits;
		const res = await request(app)
			.delete('/empleados/E001')
			.set('Authorization', userBearer)
			.expect(403);
		assert.deepEqual(res.body, {
			status: 403,
			mensaje: 'No tiene permisos para realizar esta operación',
		});
		assert.equal(empleadosHits, antes);
	});

	it('USER edita su perfil y el proxy sigue', async () => {
		const res = await request(app)
			.put('/perfiles/E001')
			.set('Authorization', userBearer)
			.set('Content-Type', 'application/json')
			.send({ telefono: '3001234567' })
			.expect(200);
		assert.equal(res.body.empleadoId, 'E001');
	});

	it('USER no edita el perfil de otro', async () => {
		const res = await request(app)
			.put('/perfiles/E002')
			.set('Authorization', userBearer)
			.set('Content-Type', 'application/json')
			.send({ telefono: '000' })
			.expect(403);
		assert.equal(res.body.status, 403);
	});

	it('ADMIN pasa el DELETE', async () => {
		const res = await request(app)
			.delete('/empleados/E001')
			.set('Authorization', adminBearer)
			.expect(200);
		assert.equal(res.body.estado, 'RETIRADO');
	});

	it('USER puede leer', async () => {
		const res = await request(app)
			.get('/empleados/E001')
			.set('Authorization', userBearer)
			.expect(200);
		assert.equal(res.body.id, 'E001');
	});
});

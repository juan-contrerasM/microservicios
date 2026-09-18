import { createServer } from 'node:http';
import { after, before, describe, it } from 'node:test';
import assert from 'node:assert/strict';
import request from 'supertest';
import { createApp } from '../src/app.js';
import { loadConfig } from '../src/config.js';

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

describe('loadConfig', () => {
	it('exige EMPLEADOS_URL y DEPARTAMENTOS_URL', () => {
		assert.throws(() => loadConfig({}), /EMPLEADOS_URL/);
		assert.throws(() => loadConfig({ EMPLEADOS_URL: 'http://e:8080' }), /DEPARTAMENTOS_URL/);
	});

	it('recorta la barra final de las URLs', () => {
		const config = loadConfig({
			EMPLEADOS_URL: 'http://empleados-service:8080/',
			DEPARTAMENTOS_URL: 'http://departamentos-service:8081/',
			PORT: '9090',
		});
		assert.equal(config.empleadosUrl, 'http://empleados-service:8080');
		assert.equal(config.departamentosUrl, 'http://departamentos-service:8081');
		assert.equal(config.port, 9090);
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
				json(res, 201, { id: 'E001', estado: 'ACTIVO', echo: body }, { 'X-Backend': 'empleados' });
				return;
			}
			if (req.method === 'GET' && req.url === '/empleados/E001') {
				json(res, 200, { id: 'E001', nombre: 'Juan' }, { 'X-Backend': 'empleados' });
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

	it('propaga 201, cuerpo y cabeceras de empleados', async () => {
		const payload = { id: 'E001', nombre: 'Juan' };
		const res = await request(app)
			.post('/empleados')
			.set('Content-Type', 'application/json')
			.set('X-Request-Id', 'req-1')
			.send(payload)
			.expect(201);

		assert.equal(res.body.id, 'E001');
		assert.equal(res.body.estado, 'ACTIVO');
		assert.equal(res.headers['x-backend'], 'empleados');
		assert.equal(JSON.parse(res.body.echo).id, 'E001');
	});

	it('propaga 400 de departamentos sin reescribir el payload', async () => {
		const res = await request(app)
			.post('/departamentos')
			.set('Content-Type', 'application/json')
			.send({ id: 'IT' })
			.expect(400);

		assert.equal(res.body.mensaje, 'ya existe un departamento registrado con ese id');
		assert.equal(res.headers['x-backend'], 'departamentos');
	});

	it('conserva el path y el query string', async () => {
		const res = await request(app)
			.get('/departamentos/IT')
			.query({ include: 'nombre' })
			.expect(200);

		assert.equal(res.body.id, 'IT');
		assert.equal(res.headers['x-backend'], 'departamentos');
	});

	it('GET /empleados/{id} conserva el prefijo interno', async () => {
		const res = await request(app).get('/empleados/E001').expect(200);
		assert.equal(res.body.id, 'E001');
	});
});

describe('api-gateway destino caído', () => {
	it('GET /departamentos responde 503 JSON descriptivo, no HTML', async () => {
		const app = createApp({
			empleadosUrl: 'http://127.0.0.1:1',
			departamentosUrl: 'http://127.0.0.1:1',
			proxyTimeoutMs: 500,
		});

		const res = await request(app).get('/departamentos').expect(503);
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
			proxyTimeoutMs: 500,
		});

		const res = await request(app)
			.post('/empleados')
			.set('Content-Type', 'application/json')
			.send({ id: 'E001' })
			.expect(503);

		assert.equal(res.body.servicio, 'empleados-service');
		assert.equal(res.body.mensaje, 'El servicio de empleados no está disponible');
	});

	it('GET /health sigue UP cuando los destinos no responden', async () => {
		const app = createApp({
			empleadosUrl: 'http://127.0.0.1:1',
			departamentosUrl: 'http://127.0.0.1:1',
		});

		const res = await request(app).get('/health').expect(200);
		assert.equal(res.body.status, 'UP');
		assert.equal(res.body.service, 'api-gateway');
	});
});

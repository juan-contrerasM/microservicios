// Fase 13 (E24): protecciones del borde. Backend de auth simulado con un servidor HTTP real.
import { EventEmitter } from 'node:events';
import { createServer } from 'node:http';
import { after, before, describe, it } from 'node:test';
import assert from 'node:assert/strict';
import request from 'supertest';
import { createApp } from '../src/app.js';
import { loadConfig } from '../src/config.js';
import { limiteDePeticiones } from '../src/seguridad.js';

const SECRET = 'secreto-de-prueba-con-al-menos-32-bytes';

describe('seguridad del borde', () => {
	let auth;
	let app;

	before(async () => {
		auth = await new Promise((resolve) => {
			const server = createServer((req, res) => {
				req.resume();
				req.on('end', () => {
					res.writeHead(401, { 'content-type': 'application/json' });
					res.end(JSON.stringify({ message: 'Credenciales inválidas' }));
				});
			});
			server.listen(0, '127.0.0.1', () => resolve({ server, url: `http://127.0.0.1:${server.address().port}` }));
		});
		app = createApp({
			empleadosUrl: auth.url, departamentosUrl: auth.url, authUrl: auth.url, jwtSecret: SECRET,
			corsOrigins: ['http://app.empresa.com'], maxBodyBytes: 100, rateLimitMax: 3, retryDelaysMs: [],
		});
	});

	after(() => auth.server.close());

	it('envía cabeceras seguras y una CSP estricta en la API', async () => {
		const res = await request(app).get('/health');
		assert.equal(res.headers['x-content-type-options'], 'nosniff');
		assert.equal(res.headers['x-frame-options'], 'DENY');
		assert.equal(res.headers['referrer-policy'], 'no-referrer');
		assert.match(res.headers['content-security-policy'], /default-src 'none'/);
		assert.equal(res.headers['x-powered-by'], undefined);
	});

	it('la UI de Swagger recibe una CSP que le permite cargar sus recursos', async () => {
		const res = await request(app).get('/swagger/');
		assert.match(res.headers['content-security-policy'], /script-src 'self'/);
	});

	it('CORS solo para orígenes permitidos', async () => {
		const permitido = await request(app).options('/empleados').set('Origin', 'http://app.empresa.com');
		assert.equal(permitido.status, 204);
		assert.equal(permitido.headers['access-control-allow-origin'], 'http://app.empresa.com');
		const ajeno = await request(app).options('/empleados').set('Origin', 'http://malicioso.com');
		assert.equal(ajeno.status, 204);
		assert.equal(ajeno.headers['access-control-allow-origin'], undefined);
	});

	it('rechaza con 413 C3 un cuerpo mayor al máximo', async () => {
		const res = await request(app).post('/auth/login').set('Content-Type', 'application/json')
			.send(JSON.stringify({ usuario: 'x'.repeat(200), contrasena: 'y' }));
		assert.equal(res.status, 413);
		assert.equal(res.body.status, 413);
		assert.match(res.body.message, /supera el máximo/);
	});

	it('limita los intentos de login por IP con 429 y Retry-After', async () => {
		const intentos = [];
		for (let i = 0; i < 4; i += 1) {
			intentos.push(await request(app).post('/auth/login').send({ usuario: 'a', contrasena: 'b' }));
		}
		assert.deepEqual(intentos.map((r) => r.status), [401, 401, 401, 429]);
		assert.equal(intentos[3].body.status, 429);
		assert.ok(Number(intentos[3].headers['retry-after']) > 0);
	});

	// Simula una petición: `status` es la respuesta final que dará el backend si pasa el límite.
	function peticion(mw, ruta, status) {
		let paso = false;
		const res = new EventEmitter();
		Object.assign(res, { headersSent: false, statusCode: status, writeHead() {}, end() {} });
		mw({ method: 'POST', path: ruta, ip: '1.1.1.1', originalUrl: ruta, get: () => '' }, res, () => { paso = true; });
		if (paso) res.emit('finish');
		return paso;
	}

	it('la ventana se reinicia al vencer', () => {
		let t = 0;
		const mw = limiteDePeticiones({ rutas: new Map([['POST /auth/recover-password', 'todas']]), maximo: 1, ventanaMs: 1000, ahora: () => t });
		assert.equal(peticion(mw, '/auth/recover-password', 200), true);
		assert.equal(peticion(mw, '/auth/recover-password', 200), false);
		t = 1500;
		assert.equal(peticion(mw, '/auth/recover-password', 200), true);
	});

	it('en login solo cuentan los intentos fallidos', () => {
		const mw = limiteDePeticiones({ rutas: new Map([['POST /auth/login', 'fallos']]), maximo: 2, ventanaMs: 60_000 });
		for (let i = 0; i < 5; i += 1) assert.equal(peticion(mw, '/auth/login', 200), true, 'los logins correctos no se limitan');
		assert.equal(peticion(mw, '/auth/login', 401), true);
		assert.equal(peticion(mw, '/auth/login', 401), true);
		assert.equal(peticion(mw, '/auth/login', 200), false, 'tras dos fallos la IP queda bloqueada en la ventana');
	});

	it('loadConfig rechaza un JWT_SECRET de menos de 32 bytes', () => {
		assert.throws(() => loadConfig({
			EMPLEADOS_URL: 'http://e', DEPARTAMENTOS_URL: 'http://d', AUTH_URL: 'http://a', JWT_SECRET: 'corto',
		}), /al menos 32 bytes/);
	});
});

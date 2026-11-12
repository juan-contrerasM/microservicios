// Bonificación 3: GET /health/services agrega el /health de los seis backends sin tocar /health.
import { createServer } from 'node:http';
import { after, before, describe, it } from 'node:test';
import assert from 'node:assert/strict';
import request from 'supertest';
import { createApp, VERSION } from '../src/app.js';
import { loadConfig } from '../src/config.js';
import { SERVICIOS } from '../src/salud.js';

const SECRET = 'secreto-de-prueba-con-al-menos-32-bytes';

function listen(handler) {
	return new Promise((resolve) => {
		const server = createServer(handler);
		server.listen(0, '127.0.0.1', () => resolve({ server, url: `http://127.0.0.1:${server.address().port}` }));
	});
}

function health(nombre, status = 'UP', checks = { database: 'UP' }) {
	return (_req, res) => {
		res.writeHead(status === 'UP' ? 200 : 503, { 'Content-Type': 'application/json' });
		res.end(JSON.stringify({ status, service: nombre, version: '1.0.0', checks }));
	};
}

// Puerto que acaba de cerrarse: conexión rechazada, como un contenedor detenido.
async function urlCaida() {
	const { server, url } = await listen(() => {});
	await new Promise((resolve) => server.close(resolve));
	return url;
}

function app(urls, extra = {}) {
	return createApp({
		empleadosUrl: urls['empleados-service'],
		departamentosUrl: urls['departamentos-service'],
		authUrl: urls['auth-service'],
		perfilesUrl: urls['perfiles-service'],
		vacacionesUrl: urls['vacaciones-service'],
		notificacionesUrl: urls['notificaciones-service'],
		jwtSecret: SECRET,
		healthServicesTimeoutMs: 300,
		healthServicesCacheMs: 0,
		...extra,
	});
}

describe('GET /health/services', () => {
	const servidores = [];
	const sanos = {};
	let peticiones = 0;

	before(async () => {
		for (const nombre of SERVICIOS) {
			const { server, url } = await listen((req, res) => {
				peticiones += 1;
				assert.equal(req.url, '/health');
				health(nombre, 'UP', { database: 'UP', messageBroker: 'UP' })(req, res);
			});
			servidores.push(server);
			sanos[nombre] = url;
		}
	});

	after(() => {
		for (const server of servidores) server.close();
	});

	it('200 UP con los seis servicios arriba, sin JWT', async () => {
		const res = await request(app(sanos)).get('/health/services').expect(200);
		assert.equal(res.body.status, 'UP');
		assert.equal(res.body.service, 'api-gateway');
		assert.equal(res.body.version, VERSION);
		assert.deepEqual(Object.keys(res.body.services), SERVICIOS);
		assert.deepEqual(res.body.checks, { gateway: 'UP', ...Object.fromEntries(SERVICIOS.map((s) => [s, 'UP'])) });
		assert.deepEqual(res.body.services['empleados-service'].checks, { database: 'UP', messageBroker: 'UP' });
		assert.equal(res.body.services['auth-service'].httpStatus, 200);
		assert.equal(typeof res.body.services['auth-service'].latencyMs, 'number');
		assert.equal(res.headers['cache-control'], 'no-store');
	});

	it('un servicio detenido: 503 DEGRADED, los otros cinco UP y /health del borde sigue 200', async () => {
		const gateway = app({ ...sanos, 'perfiles-service': await urlCaida() });
		const res = await request(gateway).get('/health/services').expect(503);
		assert.equal(res.body.status, 'DEGRADED');
		assert.equal(res.body.checks['perfiles-service'], 'DOWN');
		assert.deepEqual(res.body.services['perfiles-service'], {
			status: 'DOWN', httpStatus: null, latencyMs: res.body.services['perfiles-service'].latencyMs, checks: null, detail: 'sin conexión',
		});
		for (const nombre of SERVICIOS.filter((s) => s !== 'perfiles-service')) {
			assert.equal(res.body.checks[nombre], 'UP', nombre);
		}
		await request(gateway).get('/health').expect(200);
	});

	it('un servicio con su base caída responde 503: se marca DOWN y se ve qué check falló', async () => {
		const { server, url } = await listen(health('empleados-service', 'DOWN', { database: 'DOWN', messageBroker: 'UP' }));
		try {
			const res = await request(app({ ...sanos, 'empleados-service': url })).get('/health/services').expect(503);
			assert.equal(res.body.status, 'DEGRADED');
			assert.equal(res.body.services['empleados-service'].httpStatus, 503);
			assert.deepEqual(res.body.services['empleados-service'].checks, { database: 'DOWN', messageBroker: 'UP' });
			assert.equal(res.body.services['empleados-service'].detail, undefined);
		} finally {
			server.close();
		}
	});

	it('un servicio colgado no bloquea la respuesta: DOWN por timeout', async () => {
		const colgados = [];
		const { server, url } = await listen((_req, res) => colgados.push(res));
		try {
			const inicio = Date.now();
			const res = await request(app({ ...sanos, 'vacaciones-service': url })).get('/health/services').expect(503);
			assert.ok(Date.now() - inicio < 2_000, 'debe responder dentro del timeout por servicio');
			assert.equal(res.body.services['vacaciones-service'].detail, 'sin respuesta en 300 ms');
		} finally {
			for (const res of colgados) res.destroy();
			server.close();
		}
	});

	it('502 HTML del balanceador sin réplicas cuenta como DOWN, sin reenviar el cuerpo', async () => {
		const { server, url } = await listen((_req, res) => {
			res.writeHead(502, { 'Content-Type': 'text/html' });
			res.end('<html>Bad Gateway</html>');
		});
		try {
			const res = await request(app({ ...sanos, 'notificaciones-service': url })).get('/health/services').expect(503);
			assert.deepEqual(res.body.services['notificaciones-service'], {
				status: 'DOWN', httpStatus: 502, latencyMs: res.body.services['notificaciones-service'].latencyMs, checks: null,
				detail: 'respondió HTTP 502 sin health válido',
			});
		} finally {
			server.close();
		}
	});

	it('solo reenvía checks de texto: nada interno del backend llega al cliente', async () => {
		const { server, url } = await listen((_req, res) => {
			res.writeHead(200, { 'Content-Type': 'application/json' });
			res.end(JSON.stringify({ status: 'UP', checks: { database: 'UP', detalle: { host: 'auth-db', error: 'x' } } }));
		});
		try {
			const res = await request(app({ ...sanos, 'auth-service': url })).get('/health/services').expect(200);
			assert.deepEqual(res.body.services['auth-service'].checks, { database: 'UP' });
		} finally {
			server.close();
		}
	});

	it('todos caídos: 503 DOWN, y el resto del Gateway sigue respondiendo', async () => {
		const caida = await urlCaida();
		const gateway = app(Object.fromEntries(SERVICIOS.map((s) => [s, caida])));
		const res = await request(gateway).get('/health/services').expect(503);
		assert.equal(res.body.status, 'DOWN');
		assert.equal(res.body.checks.gateway, 'UP');
		await request(gateway).get('/health').expect(200);
	});

	it('omite los servicios sin URL configurada', async () => {
		const res = await request(app({ ...sanos, 'perfiles-service': undefined })).get('/health/services').expect(200);
		assert.equal(res.body.services['perfiles-service'], undefined);
		assert.equal(Object.keys(res.body.services).length, 5);
	});

	it('peticiones dentro de la ventana de caché comparten una sola consulta a los backends', async () => {
		const gateway = app(sanos, { healthServicesCacheMs: 60_000 });
		const antes = peticiones;
		await Promise.all([1, 2, 3].map(() => request(gateway).get('/health/services').expect(200)));
		await request(gateway).get('/health/services').expect(200);
		assert.equal(peticiones - antes, SERVICIOS.length);
	});

	it('con <SERVICIO>_HEALTH_URL consulta esa URL y no la del proxy', async () => {
		const gateway = app({ ...sanos, 'empleados-service': await urlCaida() }, { healthUrls: { 'empleados-service': sanos['empleados-service'] } });
		const res = await request(gateway).get('/health/services').expect(200);
		assert.equal(res.body.services['empleados-service'].status, 'UP');
	});

	it('loadConfig lee las URLs de health y HEALTH_SERVICES_TIMEOUT_MS / HEALTH_SERVICES_CACHE_MS', () => {
		const base = { EMPLEADOS_URL: 'http://e:8080', DEPARTAMENTOS_URL: 'http://d:8081', AUTH_URL: 'http://a:8086', JWT_SECRET: SECRET };
		assert.deepEqual(loadConfig(base).healthUrls, {});
		assert.equal(loadConfig(base).healthServicesTimeoutMs, 2_500);
		assert.equal(loadConfig(base).healthServicesCacheMs, 2_000);
		const config = loadConfig({
			...base,
			EMPLEADOS_HEALTH_URL: 'http://empleados-service:8080/',
			HEALTH_SERVICES_TIMEOUT_MS: '1500',
			HEALTH_SERVICES_CACHE_MS: '0',
		});
		assert.deepEqual(config.healthUrls, { 'empleados-service': 'http://empleados-service:8080' });
		assert.equal(config.healthServicesTimeoutMs, 1_500);
		assert.equal(config.healthServicesCacheMs, 0);
	});
});

import express from 'express';
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { exigirAcceso } from './auth.js';
import { manejarError, rutaNoEncontrada } from './errors.js';
import { backendProxy } from './proxy.js';
import { metricsHandler, metricsMiddleware } from './metricas.js';
import { swaggerRouter } from './swagger.js';

const dir = dirname(fileURLToPath(import.meta.url));
const openapi = readFileSync(join(dir, 'openapi.json'), 'utf8');
export const VERSION = JSON.parse(readFileSync(join(dir, '..', 'package.json'), 'utf8')).version;

export function createApp({
	empleadosUrl,
	departamentosUrl,
	notificacionesUrl,
	perfilesUrl,
	vacacionesUrl,
	authUrl,
	jwtSecret,
	proxyTimeoutMs = 35_000,
	proxyTimeouts = {},
	retryDelaysMs = [1_000, 2_000, 4_000],
	circuitFailureThreshold = 3,
	circuitOpenMs = 30_000,
	rutasOpenapi = {},
	swaggerTimeoutMs = 3_000,
	fetchImpl = fetch,
}) {
	if (!jwtSecret) {
		throw new Error('La variable de entorno JWT_SECRET es obligatoria');
	}
	if (!authUrl) {
		throw new Error('La variable de entorno AUTH_URL es obligatoria');
	}

	const app = express();
	const proxyOptions = (servicio) => ({
		timeoutMs: proxyTimeouts[servicio] ?? proxyTimeoutMs,
		retryDelaysMs,
		circuitFailureThreshold,
		circuitOpenMs,
	});
	app.disable('x-powered-by');
	app.use(metricsMiddleware);
	app.get('/metrics', metricsHandler);

	// Health propio del proceso, sin ping a los backends: si un destino cae, el borde sigue UP (C2).
	app.get('/health', (_req, res) => {
		res.status(200).json({ status: 'UP', service: 'api-gateway', version: VERSION, checks: { process: 'UP' } });
	});

	app.get('/openapi.json', (_req, res) => {
		res.type('application/json').send(openapi);
	});

	// Swagger UI agregada de los siete contratos; pública como /health y /metrics.
	app.use(swaggerRouter({
		propio: openapi,
		backends: {
			'auth-service': authUrl,
			'empleados-service': empleadosUrl,
			'departamentos-service': departamentosUrl,
			'perfiles-service': perfilesUrl,
			'vacaciones-service': vacacionesUrl,
			'notificaciones-service': notificacionesUrl,
		},
		rutasOpenapi,
		timeoutMs: swaggerTimeoutMs,
		fetchImpl,
	}));

	app.use(exigirAcceso(jwtSecret));

	app.use(backendProxy({
		target: authUrl,
		servicio: 'auth-service',
		pathPrefix: '/auth',
		...proxyOptions('auth-service'),
	}));

	app.use(backendProxy({
		target: empleadosUrl,
		servicio: 'empleados-service',
		pathPrefix: '/empleados',
		...proxyOptions('empleados-service'),
	}));

	app.use(backendProxy({
		target: departamentosUrl,
		servicio: 'departamentos-service',
		pathPrefix: '/departamentos',
		...proxyOptions('departamentos-service'),
	}));

	if (notificacionesUrl) {
		app.use(backendProxy({
			target: notificacionesUrl,
			servicio: 'notificaciones-service',
			pathPrefix: '/notificaciones',
			...proxyOptions('notificaciones-service'),
		}));
	}

	if (perfilesUrl) {
		app.use(backendProxy({
			target: perfilesUrl,
			servicio: 'perfiles-service',
			pathPrefix: '/perfiles',
			...proxyOptions('perfiles-service'),
		}));
	}

	if (vacacionesUrl) {
		app.use(backendProxy({
			target: vacacionesUrl,
			servicio: 'vacaciones-service',
			pathPrefix: '/vacaciones',
			...proxyOptions('vacaciones-service'),
		}));
	}

	app.use(rutaNoEncontrada);
	app.use(manejarError);

	return app;
}

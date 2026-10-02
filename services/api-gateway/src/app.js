import express from 'express';
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { exigirAcceso } from './auth.js';
import { backendProxy } from './proxy.js';

const dir = dirname(fileURLToPath(import.meta.url));
const openapi = readFileSync(join(dir, 'openapi.json'), 'utf8');
const swaggerHtml = readFileSync(join(dir, 'swagger.html'), 'utf8');

export function createApp({
	empleadosUrl,
	departamentosUrl,
	notificacionesUrl,
	perfilesUrl,
	vacacionesUrl,
	authUrl,
	jwtSecret,
	proxyTimeoutMs = 35_000,
}) {
	if (!jwtSecret) {
		throw new Error('La variable de entorno JWT_SECRET es obligatoria');
	}
	if (!authUrl) {
		throw new Error('La variable de entorno AUTH_URL es obligatoria');
	}

	const app = express();
	app.disable('x-powered-by');

	app.get('/health', (_req, res) => {
		res.status(200).json({ status: 'UP', service: 'api-gateway' });
	});

	app.get('/openapi.json', (_req, res) => {
		res.type('application/json').send(openapi);
	});

	app.get(['/swagger', '/swagger/'], (_req, res) => {
		res.redirect(302, '/swagger/index.html');
	});

	app.get('/swagger/index.html', (_req, res) => {
		res.type('html').send(swaggerHtml);
	});

	app.use(exigirAcceso(jwtSecret));

	app.use(backendProxy({
		target: authUrl,
		servicio: 'auth-service',
		pathPrefix: '/auth',
		timeoutMs: proxyTimeoutMs,
	}));

	app.use(backendProxy({
		target: empleadosUrl,
		servicio: 'empleados-service',
		pathPrefix: '/empleados',
		timeoutMs: proxyTimeoutMs,
	}));

	app.use(backendProxy({
		target: departamentosUrl,
		servicio: 'departamentos-service',
		pathPrefix: '/departamentos',
		timeoutMs: proxyTimeoutMs,
	}));

	if (notificacionesUrl) {
		app.use(backendProxy({
			target: notificacionesUrl,
			servicio: 'notificaciones-service',
			pathPrefix: '/notificaciones',
			timeoutMs: proxyTimeoutMs,
		}));
	}

	if (perfilesUrl) {
		app.use(backendProxy({
			target: perfilesUrl,
			servicio: 'perfiles-service',
			pathPrefix: '/perfiles',
			timeoutMs: proxyTimeoutMs,
		}));
	}

	if (vacacionesUrl) {
		app.use(backendProxy({
			target: vacacionesUrl,
			servicio: 'vacaciones-service',
			pathPrefix: '/vacaciones',
			timeoutMs: proxyTimeoutMs,
		}));
	}

	app.use((req, res) => {
		res.status(404).json({
			status: 404,
			mensaje: 'Recurso no encontrado',
		});
	});

	return app;
}

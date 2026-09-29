import express from 'express';
import { backendProxy } from './proxy.js';

export function createApp({ empleadosUrl, departamentosUrl, notificacionesUrl, perfilesUrl, vacacionesUrl, proxyTimeoutMs = 35_000 }) {
	const app = express();
	app.disable('x-powered-by');

	app.get('/health', (_req, res) => {
		res.status(200).json({ status: 'UP', service: 'api-gateway' });
	});

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

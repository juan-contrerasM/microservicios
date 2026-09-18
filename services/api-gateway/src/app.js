import express from 'express';
import { backendProxy } from './proxy.js';

export function createApp({ empleadosUrl, departamentosUrl, proxyTimeoutMs = 10_000 }) {
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

	app.use((req, res) => {
		res.status(404).json({
			status: 404,
			mensaje: 'Recurso no encontrado',
		});
	});

	return app;
}

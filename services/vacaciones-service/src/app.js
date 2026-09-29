import express from 'express';
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { ErrorApi } from './servicio.js';

const dir = dirname(fileURLToPath(import.meta.url));
const openapi = readFileSync(join(dir, 'openapi.json'), 'utf8');
const swaggerHtml = readFileSync(join(dir, 'swagger.html'), 'utf8');

export function createApp({ servicio, estaSano }) {
	const app = express();
	app.disable('x-powered-by');
	app.use(express.json());

	app.get('/health', async (_req, res) => {
		const sano = await estaSano();
		res.status(sano ? 200 : 503).json({
			status: sano ? 'UP' : 'DOWN',
			service: 'vacaciones-service',
		});
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

	app.post('/vacaciones', async (req, res, next) => {
		try {
			const periodo = await servicio.programar(req.body ?? {});
			res.status(201).json(periodo);
		} catch (error) {
			next(error);
		}
	});

	app.get('/vacaciones', async (req, res, next) => {
		try {
			const empleadoId = typeof req.query.empleadoId === 'string' ? req.query.empleadoId : undefined;
			res.status(200).json(await servicio.listar(empleadoId));
		} catch (error) {
			next(error);
		}
	});

	app.get('/vacaciones/:id', async (req, res, next) => {
		try {
			res.status(200).json(await servicio.obtener(req.params.id));
		} catch (error) {
			next(error);
		}
	});

	app.delete('/vacaciones/:id', async (req, res, next) => {
		try {
			res.status(200).json(await servicio.cancelar(req.params.id));
		} catch (error) {
			next(error);
		}
	});

	app.use((error, _req, res, _next) => {
		if (error instanceof ErrorApi) {
			res.status(error.status).json(error.body);
			return;
		}
		console.error(error);
		res.status(500).json({ mensaje: 'Error interno' });
	});

	return app;
}

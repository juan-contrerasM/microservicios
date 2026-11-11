import { STATUS_CODES } from 'node:http';
import { trace } from '@opentelemetry/api';

/**
 * Error de negocio con código HTTP. `body.mensaje` es el texto para el cliente; cualquier otro
 * campo de `body` (p. ej. `periodoEnConflicto`) viaja como extensión del error C3.
 */
export class ErrorApi extends Error {
	constructor(status, body) {
		super(body.mensaje);
		this.status = status;
		this.body = body;
	}
}

const TRACEPARENT = /^[0-9a-f]{2}-([0-9a-f]{32})-[0-9a-f]{16}-[0-9a-f]{2}$/;

/** traceId del span activo de OpenTelemetry; si no hay, el del header `traceparent`. */
export function traceIdDe(req) {
	const contexto = trace.getActiveSpan()?.spanContext();
	if (contexto?.traceId && !/^0+$/.test(contexto.traceId)) {
		return contexto.traceId;
	}
	const match = TRACEPARENT.exec(String(req?.get?.('traceparent') ?? '').trim().toLowerCase());
	return match ? match[1] : null;
}

/** Cuerpo del contrato C3: `{timestamp, status, error, message, path, traceId}`. */
export function cuerpoError(req, status, message, extra = {}) {
	return {
		timestamp: new Date().toISOString(),
		status,
		error: STATUS_CODES[status] ?? 'Error',
		message,
		path: req.originalUrl?.split('?')[0] ?? req.path,
		traceId: traceIdDe(req),
		...extra,
	};
}

// Códigos de red de Node y clases SQLSTATE 08 (conexión) / 57P0x (servidor apagándose).
const CODIGOS_BASE_CAIDA = new Set(['ECONNREFUSED', 'ECONNRESET', 'ENOTFOUND', 'ETIMEDOUT', 'EAI_AGAIN', '57P01', '57P02', '57P03']);

export function esBaseCaida(error) {
	const code = String(error?.code ?? '');
	return CODIGOS_BASE_CAIDA.has(code)
		|| code.startsWith('08')
		|| /timeout exceeded when trying to connect|Connection terminated/i.test(error?.message ?? '');
}

/** 404 C3 para cualquier ruta que no exista. */
export function rutaNoEncontrada(req, res) {
	res.status(404).json(cuerpoError(req, 404, 'Recurso no encontrado'));
}

/** Middleware final: toda excepción sale como C3, sin stack trace ni mensaje interno. */
// eslint-disable-next-line no-unused-vars
export function manejarError(error, req, res, _next) {
	if (error instanceof ErrorApi) {
		const { mensaje, ...extra } = error.body;
		res.status(error.status).json(cuerpoError(req, error.status, mensaje, extra));
		return;
	}
	if (error?.type === 'entity.parse.failed') {
		res.status(400).json(cuerpoError(req, 400, 'El cuerpo de la petición no es JSON válido'));
		return;
	}
	if (error?.type === 'entity.too.large') {
		res.status(413).json(cuerpoError(req, 413, 'El cuerpo de la petición es demasiado grande'));
		return;
	}
	if (esBaseCaida(error)) {
		console.error(`Base de datos no disponible en ${req.method} ${req.path}: ${error.code ?? error.message}`);
		res.status(503).json(cuerpoError(req, 503, 'La base de datos no está disponible'));
		return;
	}
	console.error(error);
	res.status(500).json(cuerpoError(req, 500, 'Error interno del servidor'));
}

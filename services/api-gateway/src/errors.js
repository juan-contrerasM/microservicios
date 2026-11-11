import { STATUS_CODES } from 'node:http';
import { trace } from '@opentelemetry/api';

const TRACEPARENT = /^[0-9a-f]{2}-([0-9a-f]{32})-[0-9a-f]{16}-[0-9a-f]{2}$/;

function header(req, nombre) {
	const valor = typeof req?.get === 'function' ? req.get(nombre) : req?.headers?.[nombre];
	return String(valor ?? '').trim().toLowerCase();
}

/** traceId del span activo de OpenTelemetry; si no hay, el del header `traceparent`. */
export function traceIdDe(req) {
	const contexto = trace.getActiveSpan()?.spanContext();
	if (contexto?.traceId && !/^0+$/.test(contexto.traceId)) {
		return contexto.traceId;
	}
	const match = TRACEPARENT.exec(header(req, 'traceparent'));
	return match ? match[1] : null;
}

/** Cuerpo del contrato C3: `{timestamp, status, error, message, path, traceId}`. */
export function cuerpoError(req, status, message) {
	return {
		timestamp: new Date().toISOString(),
		status,
		error: STATUS_CODES[status] ?? 'Error',
		message,
		path: String(req?.originalUrl ?? req?.url ?? '').split('?')[0],
		traceId: traceIdDe(req),
	};
}

export function unavailablePayload(req, servicio) {
	const nombre = servicio.replace(/-service$/, '');
	return cuerpoError(req, 503, `El servicio de ${nombre} no está disponible`);
}

export function sendJson(res, status, payload, headers = {}) {
	// En un upgrade de WebSocket `res` es un socket: no hay respuesta HTTP que escribir.
	if (!res || res.headersSent || typeof res.writeHead !== 'function') {
		return;
	}

	const body = JSON.stringify(payload);
	res.writeHead(status, {
		...headers,
		'Content-Type': 'application/json; charset=utf-8',
		'Content-Length': Buffer.byteLength(body),
	});
	res.end(body);
}

/** Responde un error C3. */
export function enviarError(req, res, status, message, headers) {
	sendJson(res, status, cuerpoError(req, status, message), headers);
}

/** 404 C3 para rutas que el Gateway no expone. */
export function rutaNoEncontrada(req, res) {
	enviarError(req, res, 404, 'Recurso no encontrado');
}

/** Middleware final de Express: nunca devuelve la pila ni el mensaje interno. */
// eslint-disable-next-line no-unused-vars
export function manejarError(error, req, res, _next) {
	console.error(`Error no controlado en ${req.method} ${req.originalUrl}: ${error?.stack ?? error}`);
	enviarError(req, res, 500, 'Error interno del servidor');
}

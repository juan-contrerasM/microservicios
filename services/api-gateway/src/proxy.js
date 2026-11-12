import { sendJson, unavailablePayload } from './errors.js';
import { CircuitBreaker, CircuitOpenError } from './circuit-breaker.js';
import { recordCircuitState, recordUpstreamError } from './metricas.js';

function matchesPrefix(pathname, pathPrefix) {
	return pathname === pathPrefix || pathname.startsWith(`${pathPrefix}/`);
}

/**
 * Reverse proxy que conserva path, query, cuerpo, cabeceras y código de estado.
 * No strip de prefijo: /empleados/E001 → {target}/empleados/E001.
 */
const HOP_BY_HOP = new Set(['connection', 'keep-alive', 'proxy-authenticate', 'proxy-authorization', 'te', 'trailer', 'transfer-encoding', 'upgrade', 'host', 'content-length']);

function headersForUpstream(req) {
	const headers = new Headers();
	for (const [name, value] of Object.entries(req.headers)) {
		if (!HOP_BY_HOP.has(name.toLowerCase()) && value !== undefined) headers.set(name, Array.isArray(value) ? value.join(', ') : value);
	}
	headers.set('x-forwarded-host', req.headers.host ?? '');
	headers.set('x-forwarded-proto', req.protocol);
	headers.set('x-forwarded-for', req.ip);
	return headers;
}

async function bodyOf(req) {
	if (req.method === 'GET' || req.method === 'HEAD') return undefined;
	const chunks = [];
	for await (const chunk of req) chunks.push(chunk);
	return chunks.length ? Buffer.concat(chunks) : undefined;
}

async function sleep(ms) {
	await new Promise((resolve) => setTimeout(resolve, ms));
}

/** Reverse proxy con timeout, retry GET y circuito independiente por backend. */
export function backendProxy({
	target, servicio, pathPrefix, timeoutMs, retryDelaysMs = [1_000, 2_000, 4_000],
	circuitFailureThreshold = 3, circuitOpenMs = 30_000, fetchImpl = fetch,
}) {
	const breaker = new CircuitBreaker({
		service: servicio,
		failureThreshold: circuitFailureThreshold,
		openDurationMs: circuitOpenMs,
		onStateChange: recordCircuitState,
	});

	return async function resilientProxy(req, res, next) {
		if (!matchesPrefix(req.path, pathPrefix)) return next();
		const body = await bodyOf(req);
		const delays = req.method === 'GET' ? [0, ...retryDelaysMs] : [0];
		try {
			const response = await breaker.execute(async () => {
				let lastError;
				for (const delay of delays) {
					if (delay) await sleep(delay);
					try {
						const upstream = await fetchImpl(`${target}${req.originalUrl}`, {
							method: req.method, headers: headersForUpstream(req), body,
							signal: AbortSignal.timeout(timeoutMs), redirect: 'manual',
						});
						if (upstream.status >= 500) throw new Error(`upstream ${upstream.status}`);
						return upstream;
					} catch (error) {
						lastError = error;
					}
				}
				throw lastError;
			});

			const responseHeaders = {};
			response.headers.forEach((value, name) => { if (!HOP_BY_HOP.has(name.toLowerCase())) responseHeaders[name] = value; });
			const payload = Buffer.from(await response.arrayBuffer());
			res.writeHead(response.status, { ...responseHeaders, 'content-length': payload.length });
			res.end(payload);
		} catch (error) {
			const causa = error instanceof CircuitOpenError ? 'circuit_open' : error.name === 'TimeoutError' ? 'timeout' : 'upstream';
			recordUpstreamError(servicio, causa);
			console.error(`proxy error [${servicio}] ${req.method} ${req.originalUrl}: ${error.message}`);
			sendJson(res, 503, unavailablePayload(req, servicio));
		}
	};
}

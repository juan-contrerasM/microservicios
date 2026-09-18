import { createProxyMiddleware } from 'http-proxy-middleware';
import { sendJson, unavailablePayload } from './errors.js';

function matchesPrefix(pathname, pathPrefix) {
	return pathname === pathPrefix || pathname.startsWith(`${pathPrefix}/`);
}

/**
 * Reverse proxy que conserva path, query, cuerpo, cabeceras y código de estado.
 * No strip de prefijo: /empleados/E001 → {target}/empleados/E001.
 */
export function backendProxy({ target, servicio, pathPrefix, timeoutMs }) {
	return createProxyMiddleware({
		target,
		changeOrigin: true,
		xfwd: true,
		timeout: timeoutMs,
		proxyTimeout: timeoutMs,
		pathFilter: (pathname) => matchesPrefix(pathname, pathPrefix),
		on: {
			error(err, req, res) {
				console.error(`proxy error [${servicio}] ${req.method} ${req.originalUrl}: ${err.message}`);
				sendJson(res, 503, unavailablePayload(servicio));
			},
		},
	});
}

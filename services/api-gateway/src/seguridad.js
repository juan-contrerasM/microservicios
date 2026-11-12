import { enviarError } from './errors.js';

/**
 * Fase 13 (E24): protecciones del borde, sin dependencias nuevas.
 *  - Cabeceras seguras (equivalente mínimo a helmet para una API JSON + la UI de Swagger).
 *  - CORS explícito: solo los orígenes de CORS_ORIGINS; sin lista, no se emite ninguna cabecera CORS.
 *  - Límite de tamaño de cuerpo: 413 C3 antes de leerlo entero (MAX_BODY_BYTES).
 *  - Rate limiting por IP en login y recuperación (ventana fija): 429 C3 con Retry-After.
 */

const CSP_API = "default-src 'none'; frame-ancestors 'none'";
// Swagger UI necesita sus scripts y estilos (servidos por el propio Gateway) y fetch a sí mismo.
const CSP_SWAGGER = "default-src 'self'; script-src 'self' 'unsafe-inline'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; frame-ancestors 'none'";

export function cabecerasSeguras() {
	return (req, res, next) => {
		res.set({
			'X-Content-Type-Options': 'nosniff',
			'X-Frame-Options': 'DENY',
			'Referrer-Policy': 'no-referrer',
			'Cross-Origin-Resource-Policy': 'same-origin',
			'Permissions-Policy': 'camera=(), microphone=(), geolocation=()',
			'Content-Security-Policy': req.path.startsWith('/swagger') ? CSP_SWAGGER : CSP_API,
		});
		next();
	};
}

export function cors(origenes = []) {
	const permitidos = new Set(origenes);
	return (req, res, next) => {
		const origen = req.get('origin');
		if (origen && permitidos.has(origen)) {
			res.set({
				'Access-Control-Allow-Origin': origen,
				Vary: 'Origin',
				'Access-Control-Allow-Headers': 'Authorization, Content-Type',
				'Access-Control-Allow-Methods': 'GET, POST, PUT, DELETE, OPTIONS',
				'Access-Control-Max-Age': '600',
			});
		}
		if (req.method === 'OPTIONS' && origen) {
			// Preflight: se responde aquí, sin JWT ni proxy. Un origen no permitido recibe 204 sin
			// cabeceras CORS y el navegador bloquea la petición real.
			res.status(204).end();
			return;
		}
		next();
	};
}

export function limiteCuerpo(maxBytes) {
	return (req, res, next) => {
		const declarado = Number(req.get('content-length') || 0);
		if (declarado > maxBytes) {
			enviarError(req, res, 413, `El cuerpo supera el máximo de ${maxBytes} bytes`);
			return;
		}
		next();
	};
}

/**
 * Ventana fija por IP y ruta. `rutas` asigna a cada "MÉTODO /ruta" qué se cuenta:
 *  - 'fallos': solo respuestas >= 400 (login, reset): frena fuerza bruta sin castigar a quien
 *    entra bien varias veces.
 *  - 'todas': cada solicitud (recuperación responde siempre 200 y, sin límite, permitiría enviar
 *    correos en masa).
 * El estado vive en memoria de cada réplica del Gateway: con N réplicas el límite efectivo es N
 * veces mayor, aceptable para frenar fuerza bruta (documentado en el README).
 */
export function limiteDePeticiones({ rutas, maximo, ventanaMs, ahora = () => Date.now() }) {
	const conteos = new Map();
	const entradaDe = (clave, t) => {
		let entrada = conteos.get(clave);
		if (!entrada || t >= entrada.fin) {
			entrada = { cuenta: 0, fin: t + ventanaMs };
			conteos.set(clave, entrada);
		}
		return entrada;
	};
	return (req, res, next) => {
		const modo = rutas.get(`${req.method} ${req.path}`);
		if (!modo) {
			next();
			return;
		}
		const clave = `${req.ip}|${req.path}`;
		const t = ahora();
		const entrada = entradaDe(clave, t);
		if (conteos.size > 10_000) {
			for (const [k, v] of conteos) if (t >= v.fin) conteos.delete(k);
		}
		if (entrada.cuenta >= maximo) {
			const espera = Math.max(1, Math.ceil((entrada.fin - t) / 1000));
			enviarError(req, res, 429, 'Demasiados intentos; espere antes de reintentar', { 'Retry-After': String(espera) });
			return;
		}
		if (modo === 'todas') {
			entrada.cuenta += 1;
		} else {
			res.on('finish', () => {
				if (res.statusCode >= 400) entradaDe(clave, ahora()).cuenta += 1;
			});
		}
		next();
	};
}

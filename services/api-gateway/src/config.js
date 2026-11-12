import { readFileSync } from 'node:fs';

function secret(env, name) {
	if (env[name]?.trim()) return env[name].trim();
	const path = env[`${name}_FILE`];
	if (path?.trim()) return readFileSync(path.trim(), 'utf8').trim();
	return undefined;
}

function required(env, name) {
	const value = secret(env, name);
	if (!value || value.trim() === '') {
		throw new Error(`La variable de entorno ${name} es obligatoria`);
	}
	return value.trim();
}

function requiredUrl(env, name) {
	return required(env, name).replace(/\/+$/, '');
}

const VARIABLES_OPENAPI = {
	'auth-service': 'AUTH_OPENAPI_PATH',
	'empleados-service': 'EMPLEADOS_OPENAPI_PATH',
	'departamentos-service': 'DEPARTAMENTOS_OPENAPI_PATH',
	'perfiles-service': 'PERFILES_OPENAPI_PATH',
	'vacaciones-service': 'VACACIONES_OPENAPI_PATH',
	'notificaciones-service': 'NOTIFICACIONES_OPENAPI_PATH',
};

const VARIABLES_HEALTH = {
	'auth-service': 'AUTH_HEALTH_URL',
	'empleados-service': 'EMPLEADOS_HEALTH_URL',
	'departamentos-service': 'DEPARTAMENTOS_HEALTH_URL',
	'perfiles-service': 'PERFILES_HEALTH_URL',
	'vacaciones-service': 'VACACIONES_HEALTH_URL',
	'notificaciones-service': 'NOTIFICACIONES_HEALTH_URL',
};

function healthUrls(env) {
	return Object.fromEntries(Object.entries(VARIABLES_HEALTH)
		.filter(([, variable]) => env[variable]?.trim())
		.map(([servicio, variable]) => [servicio, requiredUrl(env, variable)]));
}

function rutasOpenapi(env) {
	return Object.fromEntries(Object.entries(VARIABLES_OPENAPI)
		.filter(([, variable]) => env[variable]?.trim())
		.map(([servicio, variable]) => {
			const ruta = env[variable].trim();
			return [servicio, ruta.startsWith('/') ? ruta : `/${ruta}`];
		}));
}

export function loadConfig(env = process.env) {
	const proxyTimeoutMs = Number(env.PROXY_TIMEOUT_MS || 35_000);
	return {
		port: Number(env.PORT || 8080),
		empleadosUrl: requiredUrl(env, 'EMPLEADOS_URL'),
		departamentosUrl: requiredUrl(env, 'DEPARTAMENTOS_URL'),
		notificacionesUrl: env.NOTIFICACIONES_URL
			? requiredUrl(env, 'NOTIFICACIONES_URL')
			: undefined,
		perfilesUrl: env.PERFILES_URL
			? requiredUrl(env, 'PERFILES_URL')
			: undefined,
		vacacionesUrl: env.VACACIONES_URL
			? requiredUrl(env, 'VACACIONES_URL')
			: undefined,
		authUrl: requiredUrl(env, 'AUTH_URL'),
		jwtSecret: secretoJwt(env),
		proxyTimeoutMs,
		proxyTimeouts: {
			'auth-service': Number(env.AUTH_PROXY_TIMEOUT_MS || proxyTimeoutMs),
			'empleados-service': Number(env.EMPLEADOS_PROXY_TIMEOUT_MS || proxyTimeoutMs),
			'departamentos-service': Number(env.DEPARTAMENTOS_PROXY_TIMEOUT_MS || proxyTimeoutMs),
			'notificaciones-service': Number(env.NOTIFICACIONES_PROXY_TIMEOUT_MS || proxyTimeoutMs),
			'perfiles-service': Number(env.PERFILES_PROXY_TIMEOUT_MS || proxyTimeoutMs),
			'vacaciones-service': Number(env.VACACIONES_PROXY_TIMEOUT_MS || proxyTimeoutMs),
		},
		retryDelaysMs: String(env.PROXY_RETRY_DELAYS_MS || '1000,2000,4000').split(',').map(Number),
		circuitFailureThreshold: Number(env.CB_FAILURE_THRESHOLD || 3),
		circuitOpenMs: Number(env.CB_OPEN_DURATION_MS || 30_000),
		// Ruta del OpenAPI en cada backend para GET /swagger/specs/<servicio>. Solo se sobrescribe la
		// que traiga variable; las demás usan RUTAS_OPENAPI_POR_DEFECTO (src/swagger.js).
		rutasOpenapi: rutasOpenapi(env),
		swaggerTimeoutMs: Number(env.SWAGGER_SPEC_TIMEOUT_MS || 3_000),
		// GET /health/services: URL directa del servicio (sin balanceador, que oculta el cuerpo del
		// 503 al sacar la réplica de rotación); sin ella se usa la URL del proxy. Timeout por backend
		// (en paralelo, < 3 s en total) y reutilización.
		healthUrls: healthUrls(env),
		healthServicesTimeoutMs: Number(env.HEALTH_SERVICES_TIMEOUT_MS || 2_500),
		healthServicesCacheMs: Number(env.HEALTH_SERVICES_CACHE_MS || 2_000),
		// Fase 13: orígenes CORS permitidos (lista separada por comas; vacía = sin CORS), tamaño
		// máximo de cuerpo y límite de intentos por IP y minuto en login/recuperación/reset.
		corsOrigins: String(env.CORS_ORIGINS || '').split(',').map((o) => o.trim()).filter(Boolean),
		maxBodyBytes: Number(env.MAX_BODY_BYTES || 1_048_576),
		rateLimitMax: Number(env.AUTH_RATE_LIMIT_MAX || 20),
		rateLimitVentanaMs: Number(env.AUTH_RATE_LIMIT_WINDOW_MS || 60_000),
	};
}

// Fase 13: HS256 con una clave corta es atacable por fuerza bruta (RFC 7518 §3.2 pide ≥ 256 bits).
function secretoJwt(env) {
	const valor = required(env, 'JWT_SECRET');
	if (Buffer.byteLength(valor) < 32) {
		throw new Error('JWT_SECRET debe tener al menos 32 bytes');
	}
	return valor;
}

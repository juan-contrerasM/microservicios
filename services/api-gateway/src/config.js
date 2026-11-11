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

function rutasOpenapi(env) {
	return Object.fromEntries(Object.entries(VARIABLES_OPENAPI)
		.filter(([, variable]) => env[variable]?.trim())
		.map(([servicio, variable]) => {
			const ruta = env[variable].trim();
			return [servicio, ruta.startsWith('/') ? ruta : `/${ruta}`];
		}));
}

export function loadConfig(env = process.env) {
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
		jwtSecret: required(env, 'JWT_SECRET'),
		proxyTimeoutMs: Number(env.PROXY_TIMEOUT_MS || 35_000),
		// Ruta del OpenAPI en cada backend para GET /swagger/specs/<servicio>. Solo se sobrescribe la
		// que traiga variable; las demás usan RUTAS_OPENAPI_POR_DEFECTO (src/swagger.js).
		rutasOpenapi: rutasOpenapi(env),
		swaggerTimeoutMs: Number(env.SWAGGER_SPEC_TIMEOUT_MS || 3_000),
	};
}

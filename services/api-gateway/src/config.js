function requiredUrl(env, name) {
	const value = env[name];
	if (!value || value.trim() === '') {
		throw new Error(`La variable de entorno ${name} es obligatoria`);
	}
	return value.replace(/\/+$/, '');
}

export function loadConfig(env = process.env) {
	return {
		port: Number(env.PORT || 8080),
		empleadosUrl: requiredUrl(env, 'EMPLEADOS_URL'),
		departamentosUrl: requiredUrl(env, 'DEPARTAMENTOS_URL'),
		proxyTimeoutMs: Number(env.PROXY_TIMEOUT_MS || 35_000),
	};
}

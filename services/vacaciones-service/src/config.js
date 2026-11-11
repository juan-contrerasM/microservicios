import { readFileSync } from 'node:fs';

function secret(env, name, fallback = '') {
	if (env[name]?.trim()) return env[name].trim();
	if (env[`${name}_FILE`]?.trim()) {
		return readFileSync(env[`${name}_FILE`].trim(), 'utf8').trim();
	}
	return fallback;
}

export function loadConfig(env = process.env) {
	const brokerUrl = env.BROKER_URL || 'amqp://localhost:5672';
	const user = env.RABBITMQ_USER || 'onboarding';
	const password = secret(env, 'RABBITMQ_PASSWORD');
	if (!password) throw new Error('RABBITMQ_PASSWORD o RABBITMQ_PASSWORD_FILE es obligatorio');
	const destino = new URL(brokerUrl);
	destino.username = user;
	destino.password = password;
	return {
		port: Number(env.PORT || 8085),
		databaseUrl: env.DATABASE_URL || (() => {
			const dbUser = env.DB_USER || 'vacaciones';
			const dbPassword = secret(env, 'DB_PASSWORD');
			if (!dbPassword) throw new Error('DB_PASSWORD o DB_PASSWORD_FILE es obligatorio');
			const dbHost = env.DB_HOST || 'localhost';
			const dbPort = env.DB_PORT || '5432';
			const dbName = env.DB_NAME || 'vacaciones_db';
			return `postgresql://${encodeURIComponent(dbUser)}:${encodeURIComponent(dbPassword)}@${dbHost}:${dbPort}/${dbName}`;
		})(),
		amqpUrl: destino.toString(),
		exchange: env.BROKER_EXCHANGE || 'onboarding.eventos',
		queue: env.BROKER_QUEUE || 'q.vacaciones',
		// Por defecto cada minuto, para probar en desarrollo sin esperar un día.
		cron: env.VACACIONES_CRON || '* * * * *',
		// Tiempo máximo por check de /health (C2: la respuesta debe llegar en < 3 s).
		healthTimeoutMs: Number(env.HEALTH_TIMEOUT_MS || 2_000),
	};
}

export function loadConfig(env = process.env) {
	const brokerUrl = env.BROKER_URL || 'amqp://localhost:5672';
	const user = env.RABBITMQ_USER || 'onboarding';
	const password = env.RABBITMQ_PASSWORD || 'onboarding';
	const destino = new URL(brokerUrl);
	destino.username = user;
	destino.password = password;
	return {
		port: Number(env.PORT || 8085),
		databaseUrl: env.DATABASE_URL || 'postgresql://vacaciones:vacaciones@localhost:5432/vacaciones_db',
		amqpUrl: destino.toString(),
		exchange: env.BROKER_EXCHANGE || 'onboarding.eventos',
		queue: env.BROKER_QUEUE || 'q.vacaciones',
	};
}

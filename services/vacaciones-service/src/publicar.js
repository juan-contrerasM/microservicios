import { randomUUID } from 'node:crypto';
import amqp from 'amqplib';

export function crearPublicador(config) {
	let conexion = null;
	let canal = null;

	async function canalAbierto() {
		if (canal) {
			return canal;
		}
		conexion = await amqp.connect(config.amqpUrl);
		conexion.on('error', () => {
			canal = null;
			conexion = null;
		});
		conexion.on('close', () => {
			canal = null;
			conexion = null;
		});
		canal = await conexion.createChannel();
		await canal.assertExchange(config.exchange, 'topic', { durable: true });
		return canal;
	}

	async function publicarEvento(tipo, data) {
		const abierto = await canalAbierto();
		const envelope = {
			id: randomUUID(),
			type: tipo,
			version: 1,
			occurredAt: new Date().toISOString(),
			producer: 'vacaciones-service',
			data,
		};
		abierto.publish(
			config.exchange,
			tipo,
			Buffer.from(JSON.stringify(envelope)),
			{ contentType: 'application/json', persistent: true },
		);
	}

	return {
		publicarEvento,
		publicarProgramadas: (data) => publicarEvento('vacaciones.programadas', data),
	};
}

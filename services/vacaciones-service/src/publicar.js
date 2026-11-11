import { randomUUID } from 'node:crypto';
import amqp from 'amqplib';

/** Envelope C1 completo: los seis campos son obligatorios. */
export function crearEnvelope(tipo, data, ahora = new Date()) {
	return {
		id: randomUUID(),
		type: tipo,
		version: 1,
		occurredAt: ahora.toISOString(),
		producer: 'vacaciones-service',
		data,
	};
}

/**
 * Publica en el exchange topic con routing key = type. Quien llama lo invoca DESPUÉS del
 * commit (servicio.js, transiciones.js); si falla, lanza y quien llama registra el error sin
 * revertir el período. `traceparent` lo inyecta la instrumentación OTel de amqplib
 * (telemetria.js) en los headers AMQP.
 */
export function crearPublicador(config, { conectar = (url) => amqp.connect(url) } = {}) {
	let conexion = null;
	let canal = null;

	function olvidar() {
		canal = null;
		conexion = null;
	}

	async function canalAbierto() {
		if (canal) {
			return canal;
		}
		conexion = await conectar(config.amqpUrl);
		conexion.on('error', olvidar);
		conexion.on('close', olvidar);
		// Canal con confirmaciones: un publish que el broker no aceptó se convierte en error
		// visible en vez de perderse en silencio.
		const nuevo = await conexion.createConfirmChannel();
		nuevo.on('error', olvidar);
		nuevo.on('close', olvidar);
		await nuevo.assertExchange(config.exchange, 'topic', { durable: true });
		canal = nuevo;
		return canal;
	}

	async function publicarEvento(tipo, data) {
		const abierto = await canalAbierto();
		const envelope = crearEnvelope(tipo, data);
		await new Promise((resolve, reject) => {
			abierto.publish(
				config.exchange,
				tipo,
				Buffer.from(JSON.stringify(envelope)),
				{ contentType: 'application/json', persistent: true, messageId: envelope.id, type: tipo },
				(error) => (error ? reject(error) : resolve()),
			);
		});
		return envelope;
	}

	return {
		publicarEvento,
		publicarProgramadas: (data) => publicarEvento('vacaciones.programadas', data),
	};
}

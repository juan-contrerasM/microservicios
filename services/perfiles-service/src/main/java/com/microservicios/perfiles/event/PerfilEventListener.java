package com.microservicios.perfiles.event;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import com.microservicios.perfiles.config.BrokerProperties;
import com.microservicios.perfiles.service.PerfilEventoService;
import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;

import tools.jackson.databind.ObjectMapper;

/**
 * Consumidor de {@code q.perfiles}. Ack manual y prefetch 1 (application.properties).
 * <ul>
 * <li>Envelope inválido o no JSON: reject sin requeue, termina en {@code q.perfiles.dlq}.</li>
 * <li>Fallo procesando: se publica en {@code q.perfiles.retry} por el exchange por defecto con
 * {@code x-retry-count + 1} y los headers originales ({@code traceparent} incluido) y se confirma
 * el original. A los 5 s vuelve solo a {@code q.perfiles}.</li>
 * <li>Con {@code x-retry-count} = {@value #MAX_REINTENTOS}, el siguiente fallo va a la DLQ.</li>
 * </ul>
 * La deduplicación por {@code id} vive en {@link PerfilEventoService#procesar}, en la misma
 * transacción que el efecto sobre el perfil.
 */
@Component
public class PerfilEventListener {

	private static final Logger log = LoggerFactory.getLogger(PerfilEventListener.class);
	static final int MAX_REINTENTOS = 3;
	static final String HEADER_REINTENTOS = "x-retry-count";
	static final String HEADER_ROUTING_ORIGINAL = "x-original-routing-key";

	private final ObjectMapper objectMapper;
	private final PerfilEventoService perfilEventoService;
	private final String cola;

	public PerfilEventListener(ObjectMapper objectMapper, PerfilEventoService perfilEventoService,
			BrokerProperties broker) {
		this.objectMapper = objectMapper;
		this.perfilEventoService = perfilEventoService;
		this.cola = broker.getQueue();
	}

	@RabbitListener(queues = "${broker.queue}")
	public void consumir(Message message, Channel channel) throws IOException {
		long tag = message.getMessageProperties().getDeliveryTag();
		EnvelopeEntrante envelope;
		try {
			envelope = EnvelopeEntrante.leer(objectMapper, message.getBody());
		} catch (EnvelopeInvalidoException ex) {
			log.error("Mensaje inválido; se envía a DLQ: {}", ex.getMessage());
			channel.basicReject(tag, false);
			return;
		}
		try {
			perfilEventoService.procesar(envelope.id(), envelope.type(), envelope.data());
			channel.basicAck(tag, false);
			log.info("Evento {} ({}) procesado", envelope.id(), envelope.type());
		} catch (Exception ex) {
			reintentarODlq(message, channel, envelope.id(), ex);
		}
	}

	void reintentarODlq(Message message, Channel channel, String eventId, Exception causa) throws IOException {
		MessageProperties propiedades = message.getMessageProperties();
		long tag = propiedades.getDeliveryTag();
		Map<String, Object> headers = new HashMap<>();
		propiedades.getHeaders().forEach((clave, valor) -> {
			// x-death y compañía los gestiona el broker al hacer dead-letter; no se copian.
			if (!clave.startsWith("x-death") && !clave.startsWith("x-first-death-")
					&& !clave.startsWith("x-last-death-")) {
				headers.put(clave, valor);
			}
		});
		int intento = headers.get(HEADER_REINTENTOS) instanceof Number numero ? numero.intValue() : 0;
		if (intento >= MAX_REINTENTOS) {
			log.error("Fallo definitivo procesando {} tras {} reintentos; se envía a DLQ: {}",
					eventId, intento, causa.getMessage());
			channel.basicReject(tag, false);
			return;
		}
		headers.put(HEADER_REINTENTOS, intento + 1);
		headers.putIfAbsent(HEADER_ROUTING_ORIGINAL, propiedades.getReceivedRoutingKey());
		AMQP.BasicProperties nuevas = new AMQP.BasicProperties.Builder()
				.contentType(propiedades.getContentType())
				.deliveryMode(2)
				.headers(headers)
				.messageId(propiedades.getMessageId())
				.correlationId(propiedades.getCorrelationId())
				.type(propiedades.getType())
				.build();
		try {
			channel.basicPublish("", cola + ".retry", nuevas, message.getBody());
		} catch (IOException | RuntimeException ex) {
			log.error("No se pudo programar el reintento de {}; se envía a DLQ: {}", eventId, ex.getMessage());
			channel.basicReject(tag, false);
			return;
		}
		channel.basicAck(tag, false);
		log.error("Fallo procesando {}; reintento {}/{} programado en {}.retry: {}",
				eventId, intento + 1, MAX_REINTENTOS, cola, causa.getMessage());
	}
}

package com.microservicios.perfiles.event;

import java.io.IOException;
import java.util.Map;
import java.util.HashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import com.microservicios.perfiles.service.PerfilEventoService;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.AMQP;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
public class PerfilEventListener {

	private static final Logger log = LoggerFactory.getLogger(PerfilEventListener.class);
	private static final String RETRY_EXCHANGE = "onboarding.retry.q.perfiles";
	private static final int MAX_REINTENTOS = 3;

	private final ObjectMapper objectMapper;
	private final PerfilEventoService perfilEventoService;

	public PerfilEventListener(ObjectMapper objectMapper, PerfilEventoService perfilEventoService) {
		this.objectMapper = objectMapper;
		this.perfilEventoService = perfilEventoService;
	}

	@RabbitListener(queues = "${broker.queue}")
	public void consumir(org.springframework.amqp.core.Message message, Channel channel) throws IOException {
		long tag = message.getMessageProperties().getDeliveryTag();
		try {
			JsonNode envelope = objectMapper.readTree(message.getBody());
			String eventId = texto(envelope.get("id"));
			String type = texto(envelope.get("type"));
			JsonNode dataNode = envelope.path("data");
			Map<String, Object> data = dataNode.isObject()
					? objectMapper.convertValue(dataNode, new TypeReference<Map<String, Object>>() {
					})
					: Map.of();
			perfilEventoService.procesar(eventId, type, data);
			channel.basicAck(tag, false);
		} catch (tools.jackson.core.JacksonException ex) {
			log.error("Mensaje no es JSON; se envía a DLQ: {}", ex.getMessage());
			channel.basicReject(tag, false);
		} catch (Exception ex) {
			Map<String, Object> headers = new HashMap<>(message.getMessageProperties().getHeaders());
			int intento = headers.get("x-retry-count") instanceof Number numero ? numero.intValue() : 0;
			if (intento >= MAX_REINTENTOS) {
				log.error("Fallo definitivo procesando mensaje; se envía a DLQ: {}", ex.getMessage());
				channel.basicReject(tag, false);
				return;
			}
			headers.put("x-retry-count", intento + 1);
			AMQP.BasicProperties properties = new AMQP.BasicProperties.Builder()
					.contentType(message.getMessageProperties().getContentType())
					.deliveryMode(2)
					.headers(headers)
					.build();
			channel.basicPublish(RETRY_EXCHANGE, message.getMessageProperties().getReceivedRoutingKey(),
					properties, message.getBody());
			channel.basicAck(tag, false);
			log.error("Fallo procesando mensaje; reintento {}/{} programado: {}",
					intento + 1, MAX_REINTENTOS, ex.getMessage());
		}
	}

	private static String texto(JsonNode nodo) {
		if (nodo == null || nodo.isNull() || nodo.isMissingNode()) {
			return null;
		}
		return nodo.asString();
	}
}

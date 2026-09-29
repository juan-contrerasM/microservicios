package com.microservicios.perfiles.event;

import java.io.IOException;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import com.microservicios.perfiles.service.PerfilEventoService;
import com.rabbitmq.client.Channel;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
public class PerfilEventListener {

	private static final Logger log = LoggerFactory.getLogger(PerfilEventListener.class);

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
			log.error("Mensaje no es JSON; se confirma para no bloquear la cola: {}", ex.getMessage());
			channel.basicAck(tag, false);
		} catch (Exception ex) {
			log.error("Fallo procesando mensaje; se reencola: {}", ex.getMessage());
			channel.basicNack(tag, false, true);
		}
	}

	private static String texto(JsonNode nodo) {
		if (nodo == null || nodo.isNull() || nodo.isMissingNode()) {
			return null;
		}
		return nodo.asString();
	}
}

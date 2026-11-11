package com.microservicios.perfiles.event;

import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.Set;

import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Envelope C1 ya validado. Si el cuerpo no es JSON, falta alguno de los seis campos o el
 * {@code type} no es uno que consume {@code q.perfiles}, {@link #leer} lanza
 * {@link EnvelopeInvalidoException} y el mensaje va directo a la DLQ.
 */
public record EnvelopeEntrante(String id, String type, int version, String occurredAt, String producer,
		Map<String, Object> data) {

	static final List<String> CAMPOS = List.of("id", "type", "version", "occurredAt", "producer", "data");
	static final Set<String> TIPOS = Set.of("empleado.creado", "empleado.actualizado", "empleado.retirado");

	public static EnvelopeEntrante leer(ObjectMapper objectMapper, byte[] body) {
		JsonNode envelope;
		try {
			envelope = objectMapper.readTree(body);
		} catch (JacksonException ex) {
			throw new EnvelopeInvalidoException("el mensaje no es JSON");
		}
		if (envelope == null || !envelope.isObject()) {
			throw new EnvelopeInvalidoException("el cuerpo no es un objeto JSON");
		}
		List<String> faltantes = CAMPOS.stream().filter(campo -> vacio(envelope.get(campo))).toList();
		if (!faltantes.isEmpty()) {
			throw new EnvelopeInvalidoException("faltan campos obligatorios: " + String.join(", ", faltantes));
		}
		if (!envelope.get("id").isString()) {
			throw new EnvelopeInvalidoException("id debe ser texto");
		}
		String type = envelope.get("type").asString();
		if (!TIPOS.contains(type)) {
			throw new EnvelopeInvalidoException("type desconocido para esta cola: " + type);
		}
		JsonNode version = envelope.get("version");
		if (!version.isIntegralNumber() || version.asInt() < 1) {
			throw new EnvelopeInvalidoException("version debe ser un entero >= 1");
		}
		if (!envelope.get("producer").isString()) {
			throw new EnvelopeInvalidoException("producer debe ser texto");
		}
		String occurredAt = envelope.get("occurredAt").asString();
		try {
			DateTimeFormatter.ISO_DATE_TIME.parse(occurredAt);
		} catch (DateTimeParseException ex) {
			throw new EnvelopeInvalidoException("occurredAt no es ISO-8601");
		}
		JsonNode data = envelope.get("data");
		if (!data.isObject()) {
			throw new EnvelopeInvalidoException("data debe ser un objeto");
		}
		Map<String, Object> carga = objectMapper.convertValue(data, new TypeReference<Map<String, Object>>() {
		});
		return new EnvelopeEntrante(envelope.get("id").asString(), type, version.asInt(), occurredAt,
				envelope.get("producer").asString(), carga);
	}

	private static boolean vacio(JsonNode nodo) {
		return nodo == null || nodo.isNull() || nodo.isMissingNode() || (nodo.isString() && nodo.asString().isEmpty());
	}
}

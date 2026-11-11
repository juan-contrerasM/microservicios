package com.microservicios.perfiles.exception;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.MDC;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Obtiene el trace id de la petición en curso para el campo {@code traceId} del
 * contrato C3, sin depender de la API de OpenTelemetry en el classpath.
 *
 * <p>En Docker el servicio corre con el agente Java de OpenTelemetry, que publica
 * el trace id activo en el MDC de Logback como {@code trace_id}. Sin agente
 * (pruebas, ejecución local) se usa el header W3C {@code traceparent} que propaga
 * el Gateway: identifica la misma traza. Si no hay ninguno devuelve null y el
 * campo se omite de la respuesta.
 */
public final class TraceIds {

	private static final Pattern TRACEPARENT =
			Pattern.compile("^[0-9a-f]{2}-([0-9a-f]{32})-[0-9a-f]{16}-[0-9a-f]{2}$");
	private static final String TRACE_ID_INVALIDO = "0".repeat(32);

	private TraceIds() {
	}

	public static String actual(HttpServletRequest request) {
		String traceId = valido(MDC.get("trace_id"));
		if (traceId == null) {
			traceId = valido(MDC.get("traceId"));
		}
		if (traceId != null || request == null) {
			return traceId;
		}
		String traceparent = request.getHeader("traceparent");
		if (traceparent == null) {
			return null;
		}
		Matcher matcher = TRACEPARENT.matcher(traceparent.trim().toLowerCase(Locale.ROOT));
		return matcher.matches() ? valido(matcher.group(1)) : null;
	}

	private static String valido(String traceId) {
		if (traceId == null || traceId.isBlank() || TRACE_ID_INVALIDO.equals(traceId)) {
			return null;
		}
		return traceId;
	}
}

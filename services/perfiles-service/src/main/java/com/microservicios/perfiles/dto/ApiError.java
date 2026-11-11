package com.microservicios.perfiles.dto;

import java.time.Instant;

import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;

import com.fasterxml.jackson.annotation.JsonInclude;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Error HTTP uniforme (contrato C3 del proyecto final). Es el mismo cuerpo para
 * 400/401/403/404/409/500/503 en los siete servicios: nunca lleva trazas de pila
 * ni detalles internos (SQL, clases, hosts).
 */
@Schema(name = "ApiError", description = "Error HTTP uniforme (contrato C3). Sin trazas de pila ni detalles internos.")
public record ApiError(
		@Schema(description = "Instante UTC del error (ISO-8601)", example = "2026-11-09T12:00:00Z",
				requiredMode = Schema.RequiredMode.REQUIRED) String timestamp,
		@Schema(description = "Código HTTP", example = "404",
				requiredMode = Schema.RequiredMode.REQUIRED) int status,
		@Schema(description = "Frase estándar del código HTTP", example = "Not Found",
				requiredMode = Schema.RequiredMode.REQUIRED) String error,
		@Schema(description = "Mensaje legible para el cliente", example = "El perfil del empleado E999 no existe",
				requiredMode = Schema.RequiredMode.REQUIRED) String message,
		@Schema(description = "Ruta de la petición", example = "/perfiles/E999",
				requiredMode = Schema.RequiredMode.REQUIRED) String path,
		@Schema(description = "Trace id de OpenTelemetry (32 hex). Se omite si la petición no trae contexto de traza.",
				example = "4bf92f3577b34da6a3ce929d0e0e4736")
		@JsonInclude(JsonInclude.Include.NON_EMPTY) String traceId) {

	public static ApiError of(HttpStatusCode status, String message, String path, String traceId) {
		HttpStatus conocido = HttpStatus.resolve(status.value());
		String error = conocido != null ? conocido.getReasonPhrase() : "Error";
		return new ApiError(Instant.now().toString(), status.value(), error, message, path, traceId);
	}
}

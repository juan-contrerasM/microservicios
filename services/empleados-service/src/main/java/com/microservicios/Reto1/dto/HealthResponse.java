package com.microservicios.Reto1.dto;

import java.util.Map;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Cuerpo de {@code GET /health} según el contrato C2 del proyecto final.
 */
@Schema(name = "HealthResponse",
		description = "Estado de salud (contrato C2). UP solo si todas las dependencias reales están UP.")
public record HealthResponse(
		@Schema(description = "UP o DOWN", example = "UP", allowableValues = { "UP", "DOWN" },
				requiredMode = Schema.RequiredMode.REQUIRED) String status,
		@Schema(description = "Nombre del servicio", example = "empleados-service",
				requiredMode = Schema.RequiredMode.REQUIRED) String service,
		@Schema(description = "Versión del artefacto desplegado", example = "0.0.1-SNAPSHOT",
				requiredMode = Schema.RequiredMode.REQUIRED) String version,
		@Schema(description = "Estado de cada dependencia: database (PostgreSQL) y messageBroker (RabbitMQ)",
				example = "{\"database\":\"UP\",\"messageBroker\":\"UP\"}",
				requiredMode = Schema.RequiredMode.REQUIRED) Map<String, String> checks) {
}

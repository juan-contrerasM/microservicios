package com.microservicios.Reto1.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Estado de salud del servicio. UP solo si PostgreSQL acepta conexiones.")
public record HealthResponse(
		@Schema(description = "UP o DOWN", example = "UP") String status) {
}

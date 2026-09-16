package com.microservicios.Reto1.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;

/**
 * Metadatos de la especificación OpenAPI servida por Springdoc.
 * UI: {@code /swagger-ui.html} · spec: {@code /v3/api-docs}.
 */
@Configuration
public class OpenApiConfig {

	@Bean
	public OpenAPI empleadosOpenAPI() {
		return new OpenAPI().info(new Info()
				.title("empleados-service")
				.version("1.0.0")
				.description("""
						API REST del servicio de empleados (Java 21 / Spring Boot + PostgreSQL).
						Registra y consulta empleados; el estado de un alta siempre queda ACTIVO.
						Antes de persistir valida departamentoId contra departamentos-service
						(timeout 3s, 4 intentos, backoff 1s→2s→4s). POST /empleados responde 201;
						duplicados y departamento inexistente responden 400; si se agotan los
						reintentos hacia departamentos, 503 y no se persiste. GET /health hace
						PING real a PostgreSQL (200 UP / 503 DOWN).
						"""));
	}
}

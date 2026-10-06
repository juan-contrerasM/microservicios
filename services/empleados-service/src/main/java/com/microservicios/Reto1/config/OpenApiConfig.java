package com.microservicios.Reto1.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;

/**
 * Metadatos de la especificación OpenAPI servida por Springdoc.
 * UI: {@code /swagger-ui.html} · spec: {@code /v3/api-docs}.
 */
@Configuration
public class OpenApiConfig {

	@Bean
	public OpenAPI empleadosOpenAPI() {
		return new OpenAPI()
				.components(new Components().addSecuritySchemes("BearerAuth",
						new SecurityScheme()
								.type(SecurityScheme.Type.HTTP)
								.scheme("bearer")
								.bearerFormat("JWT")
								.description("Access JWT emitido por auth-service y validado por el API Gateway")))
				.info(new Info()
				.title("empleados-service")
				.version("1.0.0")
				.description("""
						API REST del servicio de empleados (Java 21 / Spring Boot + PostgreSQL).
						POST /empleados responde 201. El estado queda ACTIVO si departamentos
						confirma el departamento, o PENDIENTE_VALIDACION si el circuito está
						abierto o la llamada falla. Duplicados y departamento inexistente
						responden 400. PUT actualiza solo los campos enviados y publica
						empleado.actualizado; no pasa el estado a RETIRADO. DELETE es baja
						lógica: RETIRADO, fechaRetiro y empleado.retirado (motivo RENUNCIA si
						el cuerpo no lo trae). Un segundo DELETE responde 400. GET /empleados
						acepta estado, desde y hasta. GET /health hace PING a PostgreSQL.
						"""));
	}
}

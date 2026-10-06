package com.microservicios.perfiles.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;

/**
 * UI: {@code /swagger-ui.html} · spec: {@code /v3/api-docs}.
 */
@Configuration
public class OpenApiConfig {

	@Bean
	public OpenAPI perfilesOpenAPI() {
		return new OpenAPI()
				.components(new Components().addSecuritySchemes("BearerAuth",
						new SecurityScheme()
								.type(SecurityScheme.Type.HTTP)
								.scheme("bearer")
								.bearerFormat("JWT")
								.description("Access JWT emitido por auth-service y validado por el API Gateway")))
				.info(new Info()
				.title("perfiles-service")
				.version("1.0.0")
				.description("""
						Perfil replicado por eventos. empleado.creado inserta el perfil,
						empleado.actualizado copia nombre y email, empleado.retirado
						pone archivado en true. PUT solo edita telefono, direccion, ciudad
						y biografia, y no publica eventos. Un perfil ausente responde 404.
						GET /health consulta PostgreSQL.
						"""));
	}
}

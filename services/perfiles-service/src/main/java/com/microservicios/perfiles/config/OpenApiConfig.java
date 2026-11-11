package com.microservicios.perfiles.config;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;

/**
 * UI: {@code /swagger-ui.html} · spec: {@code /v3/api-docs} · exportada en
 * {@code services/perfiles-service/openapi.yaml} (ver {@code OpenApiContratoTest}).
 */
@Configuration
public class OpenApiConfig {

	/** Nombre del esquema de seguridad, común a los siete servicios. */
	public static final String BEARER_AUTH = "bearerAuth";

	@Bean
	public OpenAPI perfilesOpenAPI(@Value("${service.artifact-version:desconocida}") String version) {
		return new OpenAPI()
				// Ruta relativa: Swagger UI prueba contra el mismo origen que la sirve
				// (el servicio o el Gateway) y el YAML exportado no depende del host.
				.servers(List.of(new Server().url("/").description("Mismo origen que sirve la documentación")))
				.components(new Components().addSecuritySchemes(BEARER_AUTH,
						new SecurityScheme()
								.type(SecurityScheme.Type.HTTP)
								.scheme("bearer")
								.bearerFormat("JWT")
								.description("Access JWT emitido por auth-service y validado por el API Gateway")))
				.info(new Info()
						.title("perfiles-service")
						.version(version)
						.description("""
								Perfil replicado por eventos. empleado.creado inserta el perfil,
								empleado.actualizado copia los datos del empleado, empleado.retirado
								pone archivado en true. PUT solo edita los campos personales
								(telefono, direccion, ciudad, codigoPostal, biografia, fotoUrl y
								redesSociales) y no publica eventos. Un perfil ausente responde 404.

								Todos los errores usan el formato uniforme ApiError (contrato C3).
								401 y 403 los emite el API Gateway: 401 sin token o token inválido,
								403 cuando un USER edita un perfil ajeno. 503 indica que PostgreSQL
								no está disponible. GET /health es público (contrato C2) y revisa
								PostgreSQL y RabbitMQ.
								"""));
	}

	/**
	 * Añade a cada operación de negocio (las que exigen bearerAuth) las respuestas
	 * que no dependen del controlador: 401/403 del Gateway, 500 y 503. Una respuesta
	 * ya declarada en el controlador (por ejemplo el 403 por propiedad del PUT) no
	 * se sobrescribe.
	 */
	@Bean
	public OpenApiCustomizer respuestasComunesDeError() {
		return openApi -> {
			if (openApi.getPaths() == null) {
				return;
			}
			openApi.getPaths().forEach((ruta, item) -> item.readOperationsMap().forEach((metodo, operacion) -> {
				if (operacion.getSecurity() == null || operacion.getSecurity().isEmpty()) {
					return;
				}
				ApiResponses respuestas = operacion.getResponses();
				agregar(respuestas, 401, "Unauthorized",
						"Sin token, token inválido o expirado (lo responde el API Gateway)",
						"No autenticado", ruta);
				if (metodo != PathItem.HttpMethod.GET) {
					agregar(respuestas, 403, "Forbidden",
							"El token no permite la operación (lo responde el API Gateway)",
							"No tiene permisos para realizar esta operación", ruta);
				}
				agregar(respuestas, 500, "Internal Server Error", "Error inesperado, sin detalles internos",
						"Error interno del servidor", ruta);
				agregar(respuestas, 503, "Service Unavailable", "PostgreSQL no está disponible",
						"La base de datos no está disponible", ruta);
			}));
		};
	}

	private static void agregar(ApiResponses respuestas, int status, String error, String descripcion,
			String mensaje, String ruta) {
		String codigo = String.valueOf(status);
		if (respuestas.containsKey(codigo)) {
			return;
		}
		Map<String, Object> ejemplo = new LinkedHashMap<>();
		ejemplo.put("timestamp", "2026-11-09T12:00:00Z");
		ejemplo.put("status", status);
		ejemplo.put("error", error);
		ejemplo.put("message", mensaje);
		ejemplo.put("path", ruta);
		ejemplo.put("traceId", "4bf92f3577b34da6a3ce929d0e0e4736");
		respuestas.addApiResponse(codigo, new ApiResponse()
				.description(descripcion)
				.content(new Content().addMediaType("application/json", new MediaType()
						.schema(new Schema<>().$ref("#/components/schemas/ApiError"))
						.example(ejemplo))));
	}
}

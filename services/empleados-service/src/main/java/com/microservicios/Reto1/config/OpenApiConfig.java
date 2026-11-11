package com.microservicios.Reto1.config;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
 * Metadatos de la especificación OpenAPI servida por Springdoc.
 * UI: {@code /swagger-ui.html} · spec: {@code /v3/api-docs} · exportada en
 * {@code services/empleados-service/openapi.yaml} (ver {@code OpenApiContratoTest}).
 */
@Configuration
public class OpenApiConfig {

	/** Nombre del esquema de seguridad, común a los siete servicios. */
	public static final String BEARER_AUTH = "bearerAuth";

	/** Operaciones que no tocan PostgreSQL y por tanto no pueden responder 503. */
	private static final Set<String> SIN_DEPENDENCIAS = Set.of("estadoCircuitBreaker");

	@Bean
	public OpenAPI empleadosOpenAPI(@Value("${service.artifact-version:desconocida}") String version) {
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
						.title("empleados-service")
						.version(version)
						.description("""
								API REST del servicio de empleados (Java 21 / Spring Boot + PostgreSQL).
								POST /empleados responde 201. El estado queda ACTIVO si departamentos
								confirma el departamento, o PENDIENTE_VALIDACION si el circuito está
								abierto o la llamada falla. Departamento inexistente responde 400;
								id, email o numeroEmpleado duplicado responde 409. PUT actualiza solo
								los campos enviados y publica empleado.actualizado; no pasa el estado
								a RETIRADO. DELETE es baja lógica: RETIRADO, fechaRetiro y
								empleado.retirado (motivo RENUNCIA si el cuerpo no lo trae). Un segundo
								DELETE responde 400. GET /empleados acepta estado, desde y hasta.

								Todos los errores usan el formato uniforme ApiError (contrato C3).
								401 y 403 los emite el API Gateway: 401 sin token o token inválido,
								403 cuando un USER intenta una operación de escritura. 503 indica que
								PostgreSQL no está disponible. GET /health es público (contrato C2).
								"""));
	}

	/**
	 * Añade a cada operación de negocio (las que exigen bearerAuth) las respuestas
	 * que no dependen del controlador: 401/403 del Gateway, 500 y 503. Así cada
	 * operación documenta todos los códigos que de verdad puede devolver sin repetir
	 * las mismas anotaciones en cada método.
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
							"El rol del token no permite la operación: solo ADMIN escribe (lo responde el API Gateway)",
							"No tiene permisos para realizar esta operación", ruta);
				}
				agregar(respuestas, 500, "Internal Server Error", "Error inesperado, sin detalles internos",
						"Error interno del servidor", ruta);
				if (!SIN_DEPENDENCIAS.contains(operacion.getOperationId())) {
					agregar(respuestas, 503, "Service Unavailable", "PostgreSQL no está disponible",
							"La base de datos no está disponible", ruta);
				}
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

package com.microservicios.perfiles.controller;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.microservicios.perfiles.config.OpenApiConfig;
import com.microservicios.perfiles.dto.ActualizarPerfilRequest;
import com.microservicios.perfiles.dto.ApiError;
import com.microservicios.perfiles.model.Perfil;
import com.microservicios.perfiles.service.PerfilConsultaService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * Consulta y edición del perfil replicado. Las respuestas 401, 403, 500 y 503
 * comunes a todas las operaciones las añade
 * {@link OpenApiConfig#respuestasComunesDeError()}.
 */
@RestController
@Tag(name = "Perfiles", description = "Perfil de empleado replicado por eventos")
@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
public class PerfilController {

	private static final String JSON = "application/json";

	private static final String PERFIL_EJEMPLO = """
			{
			  "id": "0b8f6a52-3c1e-4c1f-9a51-1f7c2d9e4a10",
			  "empleadoId": "E001",
			  "nombre": "Juan",
			  "apellido": "Pérez",
			  "email": "juan.perez@empresa.com",
			  "telefono": "+57 300 000 0000",
			  "direccion": "Calle 10 # 20-30",
			  "ciudad": "Bogotá",
			  "codigoPostal": "110111",
			  "cargo": "Desarrollador Senior",
			  "area": "Tecnología",
			  "numeroEmpleado": "EMP-2026-001",
			  "fotoUrl": "https://example.com/fotos/E001.png",
			  "redesSociales": "{\\"linkedin\\":\\"https://linkedin.com/in/juanperez\\"}",
			  "biografia": "Desarrollador backend.",
			  "fechaCreacion": "2026-11-09T12:00:00Z",
			  "archivado": false
			}
			""";
	private static final String ERROR_NO_EXISTE = """
			{"timestamp":"2026-11-09T12:00:00Z","status":404,"error":"Not Found",\
			"message":"El perfil del empleado E999 no existe",\
			"path":"/perfiles/E999","traceId":"4bf92f3577b34da6a3ce929d0e0e4736"}""";
	private static final String ERROR_CUERPO = """
			{"timestamp":"2026-11-09T12:00:00Z","status":400,"error":"Bad Request",\
			"message":"El cuerpo de la solicitud es inválido o está mal formado",\
			"path":"/perfiles/E001","traceId":"4bf92f3577b34da6a3ce929d0e0e4736"}""";

	private final PerfilConsultaService perfiles;

	public PerfilController(PerfilConsultaService perfiles) {
		this.perfiles = perfiles;
	}

	@GetMapping("/perfiles")
	@Operation(summary = "Listar perfiles",
			description = "Devuelve todos los perfiles, incluidos los archivados (empleados retirados).")
	@ApiResponses(@ApiResponse(responseCode = "200", description = "Listado de perfiles",
			content = @Content(mediaType = JSON, array = @ArraySchema(schema = @Schema(implementation = Perfil.class)),
					examples = @ExampleObject(value = "[" + PERFIL_EJEMPLO + "]"))))
	public List<Perfil> listar() {
		return perfiles.listar();
	}

	@GetMapping("/perfiles/{empleadoId}")
	@Operation(summary = "Obtener el perfil de un empleado",
			description = "Un perfil archivado (empleado retirado) sigue respondiendo 200 con archivado=true.")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Perfil encontrado",
					content = @Content(mediaType = JSON, schema = @Schema(implementation = Perfil.class),
							examples = @ExampleObject(value = PERFIL_EJEMPLO))),
			@ApiResponse(responseCode = "404", description = "No hay perfil para ese empleado",
					content = @Content(mediaType = JSON, schema = @Schema(implementation = ApiError.class),
							examples = @ExampleObject(value = ERROR_NO_EXISTE)))
	})
	public Perfil obtener(
			@Parameter(description = "Id del empleado dueño del perfil", example = "E001", required = true)
			@PathVariable String empleadoId) {
		return perfiles.obtener(empleadoId);
	}

	@PutMapping("/perfiles/{empleadoId}")
	@Operation(summary = "Editar el perfil",
			description = "Edita telefono, direccion, ciudad, codigoPostal, biografia, fotoUrl y redesSociales. "
					+ "Un campo ausente no se borra. No publica eventos. Un ADMIN edita cualquier perfil; "
					+ "un USER solo el suyo (el Gateway compara el empleadoId con el sub del token).")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Perfil actualizado",
					content = @Content(mediaType = JSON, schema = @Schema(implementation = Perfil.class),
							examples = @ExampleObject(value = PERFIL_EJEMPLO))),
			@ApiResponse(responseCode = "400", description = "JSON mal formado",
					content = @Content(mediaType = JSON, schema = @Schema(implementation = ApiError.class),
							examples = @ExampleObject(value = ERROR_CUERPO))),
			@ApiResponse(responseCode = "403",
					description = "Un USER intenta editar un perfil ajeno (lo responde el API Gateway)",
					content = @Content(mediaType = JSON, schema = @Schema(implementation = ApiError.class),
							examples = @ExampleObject(value = """
									{"timestamp":"2026-11-09T12:00:00Z","status":403,"error":"Forbidden",\
									"message":"No tiene permisos para realizar esta operación",\
									"path":"/perfiles/E002","traceId":"4bf92f3577b34da6a3ce929d0e0e4736"}"""))),
			@ApiResponse(responseCode = "404", description = "No hay perfil para ese empleado",
					content = @Content(mediaType = JSON, schema = @Schema(implementation = ApiError.class),
							examples = @ExampleObject(value = ERROR_NO_EXISTE)))
	})
	public Perfil actualizar(
			@Parameter(description = "Id del empleado dueño del perfil", example = "E001", required = true)
			@PathVariable String empleadoId,
			@io.swagger.v3.oas.annotations.parameters.RequestBody(
					required = true,
					content = @Content(mediaType = JSON,
							schema = @Schema(implementation = ActualizarPerfilRequest.class),
							examples = @ExampleObject(value = """
									{
									  "telefono": "+57 300 000 0000",
									  "direccion": "Calle 10 # 20-30",
									  "ciudad": "Bogotá",
									  "codigoPostal": "110111",
									  "biografia": "Desarrollador backend.",
									  "fotoUrl": "https://example.com/fotos/E001.png",
									  "redesSociales": "{\\"linkedin\\":\\"https://linkedin.com/in/juanperez\\"}"
									}
									""")))
			@RequestBody ActualizarPerfilRequest cambios) {
		return perfiles.actualizar(empleadoId, cambios);
	}
}

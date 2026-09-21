package com.microservicios.Reto1.controller;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.microservicios.Reto1.dto.ApiError;
import com.microservicios.Reto1.dto.ApiValidationError;
import com.microservicios.Reto1.dto.CircuitBreakerStatus;
import com.microservicios.Reto1.dto.ReconciliacionResultado;
import com.microservicios.Reto1.model.Empleado;
import com.microservicios.Reto1.service.EmpleadoService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

/**
 * Endpoints HTTP para el registro y consulta de empleados.
 */
@RestController
@RequestMapping("/empleados")
@Tag(name = "Empleados", description = "Registro y consulta de empleados")
public class EmpleadoController {

	private static final String JSON = "application/json";

	private final EmpleadoService empleadoService;

	public EmpleadoController(EmpleadoService empleadoService) {
		this.empleadoService = empleadoService;
	}

	/**
	 * Registra un nuevo empleado en estado ACTIVO cuando el departamento se
	 * valida, o PENDIENTE_VALIDACION cuando la dependencia no está disponible.
	 *
	 * @param empleado datos del empleado a registrar
	 * @return el empleado registrado con código 201, o 400 si el id,
	 *         el email o el numeroEmpleado ya existen
	 */
	@PostMapping
	@Operation(
			summary = "Registrar empleado",
			description = "Registra un empleado nuevo. El estado enviado en el cuerpo se ignora: "
					+ "queda ACTIVO si departamentos-service confirma el departamento, o "
					+ "PENDIENTE_VALIDACION si la dependencia no está disponible. La validación "
					+ "usa timeout de 5s, hasta 4 intentos con backoff 1s→2s→4s y Circuit Breaker.")
	@ApiResponses({
			@ApiResponse(responseCode = "201", description = "Empleado registrado",
					content = @Content(mediaType = JSON, schema = @Schema(implementation = Empleado.class))),
			@ApiResponse(responseCode = "400",
					description = "Campos inválidos, JSON mal formado, id/email/numeroEmpleado "
							+ "duplicado, o departamento inexistente",
					content = @Content(mediaType = JSON, schema = @Schema(oneOf = {
							ApiError.class, ApiValidationError.class }),
							examples = {
									@ExampleObject(name = "duplicado",
											value = "{\"mensaje\":\"Ya existe un empleado registrado con ese email\"}"),
									@ExampleObject(name = "departamentoInexistente",
											value = "{\"mensaje\":\"El departamento con id XX no existe\"}")
							})),
			@ApiResponse(responseCode = "500", description = "Error interno",
					content = @Content(mediaType = JSON, schema = @Schema(implementation = ApiError.class)))
	})
	public ResponseEntity<Empleado> registrar(
			@io.swagger.v3.oas.annotations.parameters.RequestBody(
					required = true,
					content = @Content(
							mediaType = JSON,
							schema = @Schema(implementation = Empleado.class),
							examples = @ExampleObject(value = """
									{
									  "id": "E001",
									  "nombre": "Juan",
									  "apellido": "Pérez",
									  "email": "juan.perez@empresa.com",
									  "numeroEmpleado": "EMP-2026-001",
									  "cargo": "Desarrollador Senior",
									  "area": "Tecnología",
									  "departamentoId": "IT",
									  "fechaIngreso": "2026-02-10",
									  "estado": "ACTIVO"
									}
									""")))
			@Valid @RequestBody Empleado empleado) {
		Empleado registrado = empleadoService.registrar(empleado);
		return ResponseEntity.status(HttpStatus.CREATED).body(registrado);
	}

	/**
	 * Consulta un empleado por su id.
	 *
	 * @param id identificador del empleado
	 * @return el empleado encontrado con código 200, o 404 si no existe
	 */
	@GetMapping("/{id}")
	@Operation(summary = "Consultar empleado por id")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Empleado encontrado",
					content = @Content(mediaType = JSON, schema = @Schema(implementation = Empleado.class))),
			@ApiResponse(responseCode = "404", description = "No existe un empleado con ese id",
					content = @Content(mediaType = JSON, schema = @Schema(implementation = ApiError.class),
							examples = @ExampleObject(value = "{\"mensaje\":\"El empleado con id E999 no existe\"}"))),
			@ApiResponse(responseCode = "500", description = "Error interno",
					content = @Content(mediaType = JSON, schema = @Schema(implementation = ApiError.class)))
	})
	public ResponseEntity<Empleado> consultar(
			@Parameter(description = "Identificador del empleado", example = "E001", required = true)
			@PathVariable String id) {
		Empleado empleado = empleadoService.consultarPorId(id);
		return ResponseEntity.ok(empleado);
	}

	/**
	 * Lista todos los empleados registrados.
	 *
	 * @return los empleados registrados con código 200 (arreglo vacío si no hay ninguno)
	 */
	@GetMapping
	@Operation(summary = "Listar empleados", description = "Devuelve todos los empleados registrados.")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Listado de empleados",
					content = @Content(mediaType = JSON,
							array = @io.swagger.v3.oas.annotations.media.ArraySchema(
									schema = @Schema(implementation = Empleado.class)))),
			@ApiResponse(responseCode = "500", description = "Error interno",
					content = @Content(mediaType = JSON, schema = @Schema(implementation = ApiError.class)))
	})
	public ResponseEntity<List<Empleado>> listar() {
		return ResponseEntity.ok(empleadoService.listarTodos());
	}

	/**
	 * Consulta el estado observable del Circuit Breaker que protege la
	 * llamada a departamentos (Reto 3, criterio 3).
	 *
	 * @return el nombre y estado (CLOSED/OPEN/HALF_OPEN) del circuito
	 */
	@GetMapping("/circuit-breaker")
	@Operation(summary = "Estado del Circuit Breaker de departamentos",
			description = "Expone el estado (CLOSED/OPEN/HALF_OPEN) del circuito que protege "
					+ "la llamada a departamentos-service, sin depender solo de logs.")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Estado del circuito",
					content = @Content(mediaType = JSON, schema = @Schema(implementation = CircuitBreakerStatus.class),
							examples = @ExampleObject(value = "{\"name\":\"departamentos\",\"state\":\"CLOSED\"}")))
	})
	public ResponseEntity<CircuitBreakerStatus> estadoCircuitBreaker() {
		return ResponseEntity.ok(empleadoService.estadoCircuitBreaker());
	}

	/**
	 * Revalida contra departamentos a cada empleado PENDIENTE_VALIDACION.
	 * Mecanismo mínimo de reconciliación exigido por el criterio 4 (disparado
	 * a mano; no hace falta un worker asíncrono en este reto).
	 *
	 * @return cuántos pendientes se evaluaron y cuántos pasaron a ACTIVO
	 */
	@PostMapping("/reconciliar")
	@Operation(summary = "Reconciliar empleados PENDIENTE_VALIDACION",
			description = "Vuelve a consultar departamentos para cada empleado pendiente. Si el "
					+ "departamento existe, el empleado pasa a ACTIVO. Si no, o si departamentos "
					+ "sigue sin disponibilidad, se deja PENDIENTE_VALIDACION: nunca se le asigna "
					+ "un departamento por defecto ni se borra en silencio.")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Resultado del barrido de reconciliación",
					content = @Content(mediaType = JSON, schema = @Schema(implementation = ReconciliacionResultado.class)))
	})
	public ResponseEntity<ReconciliacionResultado> reconciliar() {
		return ResponseEntity.ok(empleadoService.reconciliarPendientes());
	}
}

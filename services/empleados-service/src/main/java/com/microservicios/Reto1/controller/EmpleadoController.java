package com.microservicios.Reto1.controller;

import java.time.LocalDate;
import java.util.List;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.microservicios.Reto1.dto.ActualizarEmpleadoRequest;
import com.microservicios.Reto1.dto.ApiError;
import com.microservicios.Reto1.dto.ApiValidationError;
import com.microservicios.Reto1.dto.CircuitBreakerStatus;
import com.microservicios.Reto1.dto.ReconciliacionResultado;
import com.microservicios.Reto1.model.Empleado;
import com.microservicios.Reto1.model.EstadoEmpleado;
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
	 * Lista empleados. Sin query params devuelve todos. Con {@code estado} filtra.
	 * {@code desde} y {@code hasta} acotan la fecha UTC de {@code fechaRetiro}.
	 */
	@GetMapping
	@Operation(summary = "Listar empleados",
			description = "Sin parámetros devuelve todos. `estado=RETIRADO` devuelve solo las bajas lógicas. "
					+ "`desde` y `hasta` (AAAA-MM-DD) incluyen ambos extremos y comparan la fecha de "
					+ "`fechaRetiro`, no la hora. Deben enviarse juntos.")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Listado de empleados",
					content = @Content(mediaType = JSON,
							array = @io.swagger.v3.oas.annotations.media.ArraySchema(
									schema = @Schema(implementation = Empleado.class)))),
			@ApiResponse(responseCode = "400", description = "Estado o rango de fechas inválido",
					content = @Content(mediaType = JSON, schema = @Schema(implementation = ApiError.class))),
			@ApiResponse(responseCode = "500", description = "Error interno",
					content = @Content(mediaType = JSON, schema = @Schema(implementation = ApiError.class)))
	})
	public ResponseEntity<List<Empleado>> listar(
			@Parameter(description = "Filtra por estado. Para la auditoría de bajas: RETIRADO")
			@RequestParam(required = false) EstadoEmpleado estado,
			@Parameter(description = "Inicio del rango de fechaRetiro, inclusive (AAAA-MM-DD)", example = "2026-01-01")
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate desde,
			@Parameter(description = "Fin del rango de fechaRetiro, inclusive (AAAA-MM-DD)", example = "2026-12-31")
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate hasta) {
		return ResponseEntity.ok(empleadoService.listar(estado, desde, hasta));
	}

	/**
	 * Actualización parcial. Publica {@code empleado.actualizado} después del commit.
	 */
	@PutMapping("/{id}")
	@Operation(summary = "Actualizar empleado",
			description = "Actualiza solo los campos enviados. El id de la ruta no cambia y este "
					+ "método no pasa el estado a RETIRADO. Si cambia departamentoId, se vuelve a "
					+ "validar contra departamentos. Tras persistir publica empleado.actualizado.")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Empleado actualizado",
					content = @Content(mediaType = JSON, schema = @Schema(implementation = Empleado.class))),
			@ApiResponse(responseCode = "400",
					description = "Campo inválido, email o numeroEmpleado duplicado, o departamento inexistente",
					content = @Content(mediaType = JSON, schema = @Schema(implementation = ApiError.class))),
			@ApiResponse(responseCode = "404", description = "No existe un empleado con ese id",
					content = @Content(mediaType = JSON, schema = @Schema(implementation = ApiError.class),
							examples = @ExampleObject(value = "{\"mensaje\":\"El empleado con id E999 no existe\"}")))
	})
	public ResponseEntity<Empleado> actualizar(
			@Parameter(description = "Identificador del empleado", example = "E001", required = true)
			@PathVariable String id,
			@Valid @RequestBody ActualizarEmpleadoRequest cambios) {
		return ResponseEntity.ok(empleadoService.actualizar(id, cambios));
	}

	/**
	 * Baja lógica. No borra la fila.
	 */
	@DeleteMapping("/{id}")
	@Operation(summary = "Retirar empleado",
			description = "Pasa el estado a RETIRADO, guarda fechaRetiro en UTC y publica "
					+ "empleado.retirado. La fila permanece. Un segundo DELETE responde 400 y no publica.")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Empleado retirado",
					content = @Content(mediaType = JSON, schema = @Schema(implementation = Empleado.class))),
			@ApiResponse(responseCode = "400", description = "El empleado ya estaba retirado",
					content = @Content(mediaType = JSON, schema = @Schema(implementation = ApiError.class),
							examples = @ExampleObject(
									value = "{\"mensaje\":\"El empleado con id E001 ya está retirado\"}"))),
			@ApiResponse(responseCode = "404", description = "No existe un empleado con ese id",
					content = @Content(mediaType = JSON, schema = @Schema(implementation = ApiError.class)))
	})
	public ResponseEntity<Empleado> retirar(
			@Parameter(description = "Identificador del empleado", example = "E001", required = true)
			@PathVariable String id) {
		return ResponseEntity.ok(empleadoService.retirar(id));
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

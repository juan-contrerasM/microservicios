package com.microservicios.Reto1.exception;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import com.microservicios.Reto1.dto.ApiError;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Traduce cualquier excepción que salga de un controlador al error uniforme del
 * contrato C3 ({@link ApiError}). Ningún cuerpo de error lleva trazas de pila,
 * SQL ni nombres de clase: el detalle técnico solo va al log (con stack trace en
 * los 500, según el contrato C5).
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

	private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

	static final String MENSAJE_BD_NO_DISPONIBLE = "La base de datos no está disponible";
	static final String MENSAJE_ERROR_INTERNO = "Error interno del servidor";

	@ExceptionHandler(BadRequestException.class)
	public ResponseEntity<ApiError> handleBadRequest(BadRequestException ex, HttpServletRequest request) {
		return responder(HttpStatus.BAD_REQUEST, ex.getMessage(), request);
	}

	@ExceptionHandler(ConflictException.class)
	public ResponseEntity<ApiError> handleConflict(ConflictException ex, HttpServletRequest request) {
		return responder(HttpStatus.CONFLICT, ex.getMessage(), request);
	}

	@ExceptionHandler(ServiceUnavailableException.class)
	public ResponseEntity<ApiError> handleServiceUnavailable(ServiceUnavailableException ex,
			HttpServletRequest request) {
		return responder(HttpStatus.SERVICE_UNAVAILABLE, ex.getMessage(), request);
	}

	@ExceptionHandler(EmpleadoNoEncontradoException.class)
	public ResponseEntity<ApiError> handleEmpleadoNoEncontrado(EmpleadoNoEncontradoException ex,
			HttpServletRequest request) {
		return responder(HttpStatus.NOT_FOUND, ex.getMessage(), request);
	}

	/**
	 * El detalle por campo viaja dentro de {@code message} para no salirse de los
	 * seis campos del contrato C3: "La solicitud contiene campos inválidos: nombre: ...".
	 */
	@ExceptionHandler(MethodArgumentNotValidException.class)
	public ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException ex, HttpServletRequest request) {
		Map<String, String> errores = new LinkedHashMap<>();
		ex.getBindingResult().getFieldErrors().forEach(error -> errores.putIfAbsent(
				error.getField(),
				error.getDefaultMessage() != null ? error.getDefaultMessage() : "Valor inválido"));

		if (errores.isEmpty()) {
			return responder(HttpStatus.BAD_REQUEST, "Datos de entrada inválidos", request);
		}
		String detalle = errores.entrySet().stream()
				.map(entrada -> entrada.getKey() + ": " + entrada.getValue())
				.collect(Collectors.joining("; "));
		return responder(HttpStatus.BAD_REQUEST, "La solicitud contiene campos inválidos: " + detalle, request);
	}

	@ExceptionHandler(MethodArgumentTypeMismatchException.class)
	public ResponseEntity<ApiError> handleTipoInvalido(MethodArgumentTypeMismatchException ex,
			HttpServletRequest request) {
		return responder(HttpStatus.BAD_REQUEST, "El parámetro " + ex.getName() + " es inválido", request);
	}

	@ExceptionHandler(HttpMessageNotReadableException.class)
	public ResponseEntity<ApiError> handleUnreadable(HttpMessageNotReadableException ex, HttpServletRequest request) {
		return responder(HttpStatus.BAD_REQUEST, "El cuerpo de la solicitud es inválido o está mal formado", request);
	}

	@ExceptionHandler({ NoHandlerFoundException.class, NoResourceFoundException.class })
	public ResponseEntity<ApiError> handleRecursoNoEncontrado(Exception ex, HttpServletRequest request) {
		return responder(HttpStatus.NOT_FOUND, "Recurso no encontrado", request);
	}

	/**
	 * PostgreSQL caído o sin conexiones libres en el pool: es una dependencia caída
	 * (503), no un error del cliente ni un fallo inesperado del código.
	 */
	@ExceptionHandler({ DataAccessResourceFailureException.class, CannotCreateTransactionException.class })
	public ResponseEntity<ApiError> handleBaseDeDatosNoDisponible(Exception ex, HttpServletRequest request) {
		log.warn("PostgreSQL no disponible atendiendo {} {}: {}", request.getMethod(), request.getRequestURI(),
				ex.getMessage());
		return responder(HttpStatus.SERVICE_UNAVAILABLE, MENSAJE_BD_NO_DISPONIBLE, request);
	}

	/**
	 * Última barrera de unicidad: la restricción UNIQUE del esquema rechazó la fila
	 * (carrera entre dos altas). El mensaje no expone el nombre de la restricción.
	 */
	@ExceptionHandler(DataIntegrityViolationException.class)
	public ResponseEntity<ApiError> handleIntegridad(DataIntegrityViolationException ex, HttpServletRequest request) {
		log.warn("Violación de integridad atendiendo {} {}", request.getMethod(), request.getRequestURI());
		return responder(HttpStatus.CONFLICT, "La operación entra en conflicto con datos ya registrados", request);
	}

	/**
	 * Excepciones de Spring MVC que ya traen su código (405, 415, 400 de parámetros
	 * faltantes...) conservan el código; cualquier otra cosa es un 500 genérico.
	 */
	@ExceptionHandler(Exception.class)
	public ResponseEntity<ApiError> handleInesperado(Exception ex, HttpServletRequest request) {
		if (ex instanceof ErrorResponse errorResponse) {
			HttpStatusCode status = errorResponse.getStatusCode();
			return responder(status, mensajeGenerico(status), request);
		}
		log.error("Error inesperado atendiendo {} {}", request.getMethod(), request.getRequestURI(), ex);
		return responder(HttpStatus.INTERNAL_SERVER_ERROR, MENSAJE_ERROR_INTERNO, request);
	}

	private static String mensajeGenerico(HttpStatusCode status) {
		return switch (status.value()) {
			case 400 -> "Solicitud inválida";
			case 404 -> "Recurso no encontrado";
			case 405 -> "Método no permitido para este recurso";
			case 406 -> "Formato de respuesta no soportado";
			case 415 -> "Tipo de contenido no soportado";
			case 503 -> "Servicio no disponible";
			default -> status.is5xxServerError() ? MENSAJE_ERROR_INTERNO : "Solicitud no procesable";
		};
	}

	private static ResponseEntity<ApiError> responder(HttpStatusCode status, String mensaje,
			HttpServletRequest request) {
		String path = request != null ? request.getRequestURI() : null;
		return ResponseEntity.status(status).body(ApiError.of(status, mensaje, path, TraceIds.actual(request)));
	}
}

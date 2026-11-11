package com.microservicios.perfiles.exception;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import com.microservicios.perfiles.dto.ApiError;

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

	@ExceptionHandler(PerfilNoEncontradoException.class)
	public ResponseEntity<ApiError> noEncontrado(PerfilNoEncontradoException ex, HttpServletRequest request) {
		return responder(HttpStatus.NOT_FOUND, ex.getMessage(), request);
	}

	@ExceptionHandler(HttpMessageNotReadableException.class)
	public ResponseEntity<ApiError> cuerpoIlegible(HttpMessageNotReadableException ex, HttpServletRequest request) {
		return responder(HttpStatus.BAD_REQUEST, "El cuerpo de la solicitud es inválido o está mal formado", request);
	}

	@ExceptionHandler(MethodArgumentTypeMismatchException.class)
	public ResponseEntity<ApiError> parametroInvalido(MethodArgumentTypeMismatchException ex,
			HttpServletRequest request) {
		return responder(HttpStatus.BAD_REQUEST, "El parámetro " + ex.getName() + " es inválido", request);
	}

	@ExceptionHandler({ NoHandlerFoundException.class, NoResourceFoundException.class })
	public ResponseEntity<ApiError> recursoNoEncontrado(Exception ex, HttpServletRequest request) {
		return responder(HttpStatus.NOT_FOUND, "Recurso no encontrado", request);
	}

	/**
	 * PostgreSQL caído o sin conexiones libres en el pool: dependencia caída (503),
	 * no un error del cliente ni un fallo inesperado del código.
	 */
	@ExceptionHandler({ DataAccessResourceFailureException.class, CannotCreateTransactionException.class })
	public ResponseEntity<ApiError> baseDeDatosNoDisponible(Exception ex, HttpServletRequest request) {
		log.warn("PostgreSQL no disponible atendiendo {} {}: {}", request.getMethod(), request.getRequestURI(),
				ex.getMessage());
		return responder(HttpStatus.SERVICE_UNAVAILABLE, MENSAJE_BD_NO_DISPONIBLE, request);
	}

	/**
	 * Excepciones de Spring MVC que ya traen su código (405, 415, 400 de parámetros
	 * faltantes...) conservan el código; cualquier otra cosa es un 500 genérico.
	 */
	@ExceptionHandler(Exception.class)
	public ResponseEntity<ApiError> inesperado(Exception ex, HttpServletRequest request) {
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

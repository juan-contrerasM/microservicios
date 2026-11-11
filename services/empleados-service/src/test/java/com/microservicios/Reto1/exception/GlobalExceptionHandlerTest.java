package com.microservicios.Reto1.exception;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.time.Instant;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.core.MethodParameter;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import com.microservicios.Reto1.controller.EmpleadoController;
import com.microservicios.Reto1.dto.ApiError;
import com.microservicios.Reto1.model.Empleado;

class GlobalExceptionHandlerTest {

	private static final String TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";

	private final GlobalExceptionHandler handler = new GlobalExceptionHandler();
	private final MockHttpServletRequest request = new MockHttpServletRequest("POST", "/empleados");

	@AfterEach
	void limpiarMdc() {
		MDC.clear();
	}

	private static void assertContratoC3(ApiError body, int status, String error, String path) {
		assertThat(body.status()).isEqualTo(status);
		assertThat(body.error()).isEqualTo(error);
		assertThat(body.path()).isEqualTo(path);
		assertThat(Instant.parse(body.timestamp())).isNotNull();
		assertThat(body.message()).isNotBlank();
	}

	@Test
	void handleBadRequestDevuelve400ConElContratoC3() {
		var respuesta = handler.handleBadRequest(new BadRequestException("Solicitud inválida"), request);

		assertThat(respuesta.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		assertContratoC3(respuesta.getBody(), 400, "Bad Request", "/empleados");
		assertThat(respuesta.getBody().message()).isEqualTo("Solicitud inválida");
	}

	@Test
	void handleConflictDevuelve409() {
		var respuesta = handler.handleConflict(new ConflictException("Ya existe un empleado con ese id"), request);

		assertThat(respuesta.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
		assertContratoC3(respuesta.getBody(), 409, "Conflict", "/empleados");
		assertThat(respuesta.getBody().message()).isEqualTo("Ya existe un empleado con ese id");
	}

	@Test
	void handleServiceUnavailableDevuelve503ConElMensaje() {
		var respuesta = handler.handleServiceUnavailable(
				new ServiceUnavailableException("El servicio de departamentos no está disponible"), request);

		assertThat(respuesta.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
		assertContratoC3(respuesta.getBody(), 503, "Service Unavailable", "/empleados");
		assertThat(respuesta.getBody().message()).isEqualTo("El servicio de departamentos no está disponible");
	}

	@Test
	void handleEmpleadoNoEncontradoDevuelve404ConElMensaje() {
		MockHttpServletRequest get = new MockHttpServletRequest("GET", "/empleados/E999");

		var respuesta = handler.handleEmpleadoNoEncontrado(new EmpleadoNoEncontradoException("E999"), get);

		assertThat(respuesta.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
		assertContratoC3(respuesta.getBody(), 404, "Not Found", "/empleados/E999");
		assertThat(respuesta.getBody().message()).isEqualTo("El empleado con id E999 no existe");
	}

	@Test
	void handleValidationIncluyeCadaCampoInvalidoEnElMensaje() throws NoSuchMethodException {
		BindingResult bindingResult = new BeanPropertyBindingResult(new Empleado(), "empleado");
		bindingResult.addError(new FieldError("empleado", "id", "El id es obligatorio"));
		bindingResult.addError(new FieldError("empleado", "nombre", "El nombre es obligatorio"));
		bindingResult.addError(new FieldError("empleado", "email", "El email no tiene un formato válido"));
		Method registrar = EmpleadoController.class.getMethod("registrar", Empleado.class);
		MethodArgumentNotValidException ex = new MethodArgumentNotValidException(
				new MethodParameter(registrar, 0), bindingResult);

		var respuesta = handler.handleValidation(ex, request);

		assertThat(respuesta.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(respuesta.getBody().message()).isEqualTo("La solicitud contiene campos inválidos: "
				+ "id: El id es obligatorio; nombre: El nombre es obligatorio; "
				+ "email: El email no tiene un formato válido");
	}

	@Test
	void handleValidationSinErroresDeCampoUsaMensajePorDefecto() throws NoSuchMethodException {
		BindingResult bindingResult = new BeanPropertyBindingResult(new Empleado(), "empleado");
		Method registrar = EmpleadoController.class.getMethod("registrar", Empleado.class);
		MethodArgumentNotValidException ex = new MethodArgumentNotValidException(
				new MethodParameter(registrar, 0), bindingResult);

		var respuesta = handler.handleValidation(ex, request);

		assertThat(respuesta.getBody().message()).isEqualTo("Datos de entrada inválidos");
	}

	@Test
	void handleUnreadableDevuelve400ConMensajeGenerico() {
		var respuesta = handler.handleUnreadable(new HttpMessageNotReadableException("json inválido en línea 3",
				(org.springframework.http.HttpInputMessage) null), request);

		assertThat(respuesta.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(respuesta.getBody().message())
				.isEqualTo("El cuerpo de la solicitud es inválido o está mal formado");
	}

	@Test
	void handleRecursoNoEncontradoParaRutaInexistenteDevuelve404() {
		var respuesta = handler.handleRecursoNoEncontrado(
				new NoHandlerFoundException("GET", "/no-existe", new HttpHeaders()), request);

		assertThat(respuesta.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
		assertThat(respuesta.getBody().message()).isEqualTo("Recurso no encontrado");
	}

	@Test
	void handleRecursoNoEncontradoParaRecursoEstaticoDevuelve404() {
		var respuesta = handler.handleRecursoNoEncontrado(
				new NoResourceFoundException(HttpMethod.GET, "/no-existe", "no encontrado"), request);

		assertThat(respuesta.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
		assertThat(respuesta.getBody().message()).isEqualTo("Recurso no encontrado");
	}

	@Test
	void metodoNoSoportadoConservaSuCodigo405() {
		var respuesta = handler.handleInesperado(new HttpRequestMethodNotSupportedException("PATCH"), request);

		assertThat(respuesta.getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
		assertContratoC3(respuesta.getBody(), 405, "Method Not Allowed", "/empleados");
	}

	@Test
	void baseDeDatosCaidaDevuelve503SinDetallesInternos() {
		var respuesta = handler.handleBaseDeDatosNoDisponible(new CannotCreateTransactionException(
				"Could not open JPA EntityManager: Connection to empleados-db:5432 refused"), request);

		assertThat(respuesta.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
		assertThat(respuesta.getBody().message()).isEqualTo("La base de datos no está disponible");
		assertThat(respuesta.getBody().toString()).doesNotContain("empleados-db", "JPA");
	}

	@Test
	void falloDeRecursoDeDatosTambienEs503() {
		var respuesta = handler.handleBaseDeDatosNoDisponible(
				new DataAccessResourceFailureException("pool agotado"), request);

		assertThat(respuesta.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
	}

	@Test
	void violacionDeUnicidadEnLaBaseDevuelve409SinNombreDeRestriccion() {
		var respuesta = handler.handleIntegridad(new DataIntegrityViolationException(
				"duplicate key value violates unique constraint \"uk_empleados_email\""), request);

		assertThat(respuesta.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
		assertThat(respuesta.getBody().message()).doesNotContain("uk_empleados_email");
	}

	@Test
	void errorInesperadoDevuelve500SinTrazaNiMensajeInterno() {
		var respuesta = handler.handleInesperado(
				new IllegalStateException("NullPointer en EmpleadoService.java:42"), request);

		assertThat(respuesta.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
		assertContratoC3(respuesta.getBody(), 500, "Internal Server Error", "/empleados");
		assertThat(respuesta.getBody().message()).isEqualTo("Error interno del servidor");
		assertThat(respuesta.getBody().toString()).doesNotContain("EmpleadoService", "NullPointer");
	}

	@Test
	void traceIdSeTomaDelMdcDelAgenteOpenTelemetry() {
		MDC.put("trace_id", TRACE_ID);

		var respuesta = handler.handleConflict(new ConflictException("duplicado"), request);

		assertThat(respuesta.getBody().traceId()).isEqualTo(TRACE_ID);
	}

	@Test
	void traceIdSeTomaDelHeaderTraceparentSiNoHayAgente() {
		request.addHeader("traceparent", "00-" + TRACE_ID + "-00f067aa0ba902b7-01");

		var respuesta = handler.handleConflict(new ConflictException("duplicado"), request);

		assertThat(respuesta.getBody().traceId()).isEqualTo(TRACE_ID);
	}

	@Test
	void traceIdSeOmiteSiNoHayContextoDeTraza() {
		request.addHeader("traceparent", "basura");

		var respuesta = handler.handleConflict(new ConflictException("duplicado"), request);

		assertThat(respuesta.getBody().traceId()).isNull();
	}
}

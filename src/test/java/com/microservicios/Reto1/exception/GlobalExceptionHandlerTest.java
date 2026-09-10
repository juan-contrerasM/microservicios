package com.microservicios.Reto1.exception;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

import java.lang.reflect.Method;

import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpHeaders;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import com.microservicios.Reto1.controller.EmpleadoController;
import com.microservicios.Reto1.dto.ApiError;
import com.microservicios.Reto1.dto.ApiValidationError;
import com.microservicios.Reto1.model.Empleado;

class GlobalExceptionHandlerTest {

	private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

	@Test
	void handleBadRequestDevuelve400ConElMensaje() {
		var respuesta = handler.handleBadRequest(new BadRequestException("Solicitud inválida"));

		assertThat(respuesta.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(respuesta.getBody().getMensaje()).isEqualTo("Solicitud inválida");
	}

	@Test
	void handleConflictDevuelve409ConElMensaje() {
		var respuesta = handler.handleConflict(new ConflictException("Ya existe un empleado con ese id"));

		assertThat(respuesta.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
		assertThat(respuesta.getBody().getMensaje()).isEqualTo("Ya existe un empleado con ese id");
	}

	@Test
	void handleEmpleadoNoEncontradoDevuelve404ConElMensaje() {
		var respuesta = handler.handleEmpleadoNoEncontrado(new EmpleadoNoEncontradoException("E999"));

		assertThat(respuesta.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
		assertThat(respuesta.getBody().getMensaje()).isEqualTo("El empleado con id E999 no existe");
	}

	@Test
	void handleValidationDevuelve400ConElErrorAsociadoAlCampo() throws NoSuchMethodException {
		BindingResult bindingResult = new BeanPropertyBindingResult(new Empleado(), "empleado");
		bindingResult.addError(new FieldError("empleado", "nombre", "El nombre es obligatorio"));
		Method registrar = EmpleadoController.class.getMethod("registrar", Empleado.class);
		MethodArgumentNotValidException ex = new MethodArgumentNotValidException(
				new MethodParameter(registrar, 0), bindingResult);

		var respuesta = handler.handleValidation(ex);

		assertThat(respuesta.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(respuesta.getBody().getMensaje()).isEqualTo("La solicitud contiene campos inválidos");
		assertThat(respuesta.getBody()).isInstanceOf(ApiValidationError.class);
		ApiValidationError body = (ApiValidationError) respuesta.getBody();
		assertThat(body.getErrores()).containsEntry("nombre", "El nombre es obligatorio");
	}

	@Test
	void handleValidationDevuelveTodosLosMensajesDeCamposInvalidos() throws NoSuchMethodException {
		BindingResult bindingResult = new BeanPropertyBindingResult(new Empleado(), "empleado");
		bindingResult.addError(new FieldError("empleado", "id", "El id es obligatorio"));
		bindingResult.addError(new FieldError("empleado", "nombre", "El nombre es obligatorio"));
		bindingResult.addError(new FieldError("empleado", "email", "El email es obligatorio"));
		Method registrar = EmpleadoController.class.getMethod("registrar", Empleado.class);
		MethodArgumentNotValidException ex = new MethodArgumentNotValidException(
				new MethodParameter(registrar, 0), bindingResult);

		var respuesta = handler.handleValidation(ex);

		assertThat(respuesta.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(respuesta.getBody().getMensaje()).isEqualTo("La solicitud contiene campos inválidos");
		assertThat(respuesta.getBody()).isInstanceOf(ApiValidationError.class);
		ApiValidationError body = (ApiValidationError) respuesta.getBody();
		assertThat(body.getErrores()).containsExactly(
				entry("id", "El id es obligatorio"),
				entry("nombre", "El nombre es obligatorio"),
				entry("email", "El email es obligatorio"));
	}

	@Test
	void handleValidationSinMensajeUsaMensajePorDefecto() throws NoSuchMethodException {
		BindingResult bindingResult = new BeanPropertyBindingResult(new Empleado(), "empleado");
		Method registrar = EmpleadoController.class.getMethod("registrar", Empleado.class);
		MethodArgumentNotValidException ex = new MethodArgumentNotValidException(
				new MethodParameter(registrar, 0), bindingResult);

		var respuesta = handler.handleValidation(ex);

		assertThat(respuesta.getBody().getMensaje()).isEqualTo("Datos de entrada inválidos");
	}

	@Test
	void handleUnreadableDevuelve400ConMensajeGenerico() {
		var respuesta = handler.handleUnreadable(new HttpMessageNotReadableException("json inválido", (org.springframework.http.HttpInputMessage) null));

		assertThat(respuesta.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(respuesta.getBody().getMensaje())
				.isEqualTo("El cuerpo de la solicitud es inválido o está mal formado");
	}

	@Test
	void handleRecursoNoEncontradoParaRutaInexistenteDevuelve404() {
		ApiError body = handler
				.handleRecursoNoEncontrado(new NoHandlerFoundException("GET", "/no-existe", new HttpHeaders()))
				.getBody();

		assertThat(body.getMensaje()).isEqualTo("Recurso no encontrado");
	}

	@Test
	void handleRecursoNoEncontradoParaRecursoEstaticoDevuelve404() {
		var respuesta = handler
				.handleRecursoNoEncontrado(new NoResourceFoundException(HttpMethod.GET, "/no-existe", "no encontrado"));

		assertThat(respuesta.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
		assertThat(respuesta.getBody().getMensaje()).isEqualTo("Recurso no encontrado");
	}

	@Test
	void handleRecursoNoEncontradoParaMetodoNoSoportadoDevuelve404() {
		var respuesta = handler
				.handleRecursoNoEncontrado(new HttpRequestMethodNotSupportedException("PUT"));

		assertThat(respuesta.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
		assertThat(respuesta.getBody().getMensaje()).isEqualTo("Recurso no encontrado");
	}
}

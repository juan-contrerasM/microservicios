package com.microservicios.Reto1.contrato;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Connection;
import java.sql.SQLException;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.CannotCreateTransactionException;

import com.microservicios.Reto1.exception.ConflictException;
import com.microservicios.Reto1.exception.EmpleadoNoEncontradoException;
import com.microservicios.Reto1.model.Empleado;

/**
 * Contrato HTTP de punta a punta dentro de Spring MVC: formato de error C3 en
 * cada código y health C2 con la base de datos simulada caída.
 */
class ContratoHttpTest extends ContratoHttpBase {

	private static final String TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";
	private static final String EMPLEADO_VALIDO = """
			{"id":"E001","nombre":"Juan","apellido":"Pérez","email":"juan.perez@empresa.com",
			 "numeroEmpleado":"EMP-2026-001","cargo":"Dev","area":"TI","departamentoId":"IT",
			 "fechaIngreso":"2026-02-10","estado":"ACTIVO"}""";

	private static void assertContratoC3(ResultActions resultado, int status, String error, String path)
			throws Exception {
		resultado.andExpect(status().is(status))
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
				.andExpect(jsonPath("$.timestamp").isString())
				.andExpect(jsonPath("$.status").value(status))
				.andExpect(jsonPath("$.error").value(error))
				.andExpect(jsonPath("$.message").isString())
				.andExpect(jsonPath("$.path").value(path))
				// Nada de detalles internos ni del formato anterior.
				.andExpect(jsonPath("$.trace").doesNotExist())
				.andExpect(jsonPath("$.exception").doesNotExist())
				.andExpect(jsonPath("$.mensaje").doesNotExist());
	}

	@Test
	void empleadoInexistenteResponde404ConElContratoC3() throws Exception {
		when(empleadoService.consultarPorId("E999")).thenThrow(new EmpleadoNoEncontradoException("E999"));

		ResultActions resultado = mockMvc.perform(get("/empleados/E999"));

		assertContratoC3(resultado, 404, "Not Found", "/empleados/E999");
		resultado.andExpect(jsonPath("$.message").value("El empleado con id E999 no existe"));
	}

	@Test
	void cuerpoInvalidoResponde400ConElCampoEnElMensaje() throws Exception {
		String emailInvalido = EMPLEADO_VALIDO.replace("juan.perez@empresa.com", "no-es-email");

		ResultActions resultado = mockMvc.perform(post("/empleados")
				.contentType(MediaType.APPLICATION_JSON).content(emailInvalido));

		assertContratoC3(resultado, 400, "Bad Request", "/empleados");
		resultado.andExpect(jsonPath("$.message")
				.value("La solicitud contiene campos inválidos: email: El email no tiene un formato válido"));
	}

	@Test
	void jsonMalFormadoResponde400() throws Exception {
		ResultActions resultado = mockMvc.perform(post("/empleados")
				.contentType(MediaType.APPLICATION_JSON).content("{ no es json"));

		assertContratoC3(resultado, 400, "Bad Request", "/empleados");
	}

	@Test
	void duplicadoResponde409() throws Exception {
		when(empleadoService.registrar(any(Empleado.class)))
				.thenThrow(new ConflictException("Ya existe un empleado registrado con ese email"));

		ResultActions resultado = mockMvc.perform(post("/empleados")
				.contentType(MediaType.APPLICATION_JSON).content(EMPLEADO_VALIDO));

		assertContratoC3(resultado, 409, "Conflict", "/empleados");
	}

	@Test
	void rutaInexistenteResponde404() throws Exception {
		assertContratoC3(mockMvc.perform(get("/no-existe")), 404, "Not Found", "/no-existe");
	}

	@Test
	void metodoNoSoportadoResponde405() throws Exception {
		assertContratoC3(mockMvc.perform(patch("/empleados/E001")), 405, "Method Not Allowed", "/empleados/E001");
	}

	@Test
	void postgresCaidoEnUnaOperacionDeNegocioResponde503() throws Exception {
		when(empleadoService.consultarPorId("E001"))
				.thenThrow(new CannotCreateTransactionException("Could not open JPA EntityManager"));

		ResultActions resultado = mockMvc.perform(get("/empleados/E001"));

		assertContratoC3(resultado, 503, "Service Unavailable", "/empleados/E001");
		resultado.andExpect(jsonPath("$.message").value("La base de datos no está disponible"));
	}

	@Test
	void errorInesperadoResponde500SinDetallesInternos() throws Exception {
		when(empleadoService.listar(null, null, null)).thenThrow(new IllegalStateException("detalle interno"));

		ResultActions resultado = mockMvc.perform(get("/empleados"));

		assertContratoC3(resultado, 500, "Internal Server Error", "/empleados");
		resultado.andExpect(jsonPath("$.message").value("Error interno del servidor"));
	}

	@Test
	void traceIdSePropagaDesdeTraceparent() throws Exception {
		when(empleadoService.consultarPorId("E999")).thenThrow(new EmpleadoNoEncontradoException("E999"));

		mockMvc.perform(get("/empleados/E999").header("traceparent", "00-" + TRACE_ID + "-00f067aa0ba902b7-01"))
				.andExpect(jsonPath("$.traceId").value(TRACE_ID));
	}

	@Test
	void healthResponde200ConElContratoC2() throws Exception {
		Connection connection = Mockito.mock(Connection.class);
		when(dataSource.getConnection()).thenReturn(connection);
		when(connection.isValid(anyInt())).thenReturn(true);
		org.springframework.amqp.rabbit.connection.Connection amqp =
				Mockito.mock(org.springframework.amqp.rabbit.connection.Connection.class);
		when(brokerConnectionFactory.createConnection()).thenReturn(amqp);
		when(amqp.isOpen()).thenReturn(true);

		mockMvc.perform(get("/health"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("UP"))
				.andExpect(jsonPath("$.service").value("empleados-service"))
				.andExpect(jsonPath("$.version").isString())
				.andExpect(jsonPath("$.checks.database").value("UP"))
				.andExpect(jsonPath("$.checks.messageBroker").value("UP"));
	}

	@Test
	void healthResponde503EnMenosDeTresSegundosConPostgresColgado() throws Exception {
		when(dataSource.getConnection()).thenAnswer(invocacion -> {
			Thread.sleep(30_000);
			throw new SQLException("nunca llega");
		});
		org.springframework.amqp.rabbit.connection.Connection amqp =
				Mockito.mock(org.springframework.amqp.rabbit.connection.Connection.class);
		when(brokerConnectionFactory.createConnection()).thenReturn(amqp);
		when(amqp.isOpen()).thenReturn(true);

		long inicio = System.nanoTime();
		mockMvc.perform(get("/health"))
				.andExpect(status().isServiceUnavailable())
				.andExpect(jsonPath("$.status").value("DOWN"))
				.andExpect(jsonPath("$.checks.database").value("DOWN"))
				.andExpect(jsonPath("$.checks.messageBroker").value("UP"));
		assertThat((System.nanoTime() - inicio) / 1_000_000).isLessThan(3000);
	}
}

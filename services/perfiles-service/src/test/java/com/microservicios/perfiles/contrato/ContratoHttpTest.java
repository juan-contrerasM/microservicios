package com.microservicios.perfiles.contrato;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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

import com.microservicios.perfiles.dto.ActualizarPerfilRequest;
import com.microservicios.perfiles.exception.PerfilNoEncontradoException;

/**
 * Contrato HTTP de punta a punta dentro de Spring MVC: formato de error C3 en
 * cada código y health C2 con las dependencias simuladas caídas o colgadas.
 */
class ContratoHttpTest extends ContratoHttpBase {

	private static final String TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";

	private static void assertContratoC3(ResultActions resultado, int status, String error, String path)
			throws Exception {
		resultado.andExpect(status().is(status))
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
				.andExpect(jsonPath("$.timestamp").isString())
				.andExpect(jsonPath("$.status").value(status))
				.andExpect(jsonPath("$.error").value(error))
				.andExpect(jsonPath("$.message").isString())
				.andExpect(jsonPath("$.path").value(path))
				.andExpect(jsonPath("$.trace").doesNotExist())
				.andExpect(jsonPath("$.exception").doesNotExist())
				.andExpect(jsonPath("$.mensaje").doesNotExist());
	}

	private org.springframework.amqp.rabbit.connection.Connection brokerUp() {
		org.springframework.amqp.rabbit.connection.Connection amqp =
				Mockito.mock(org.springframework.amqp.rabbit.connection.Connection.class);
		when(brokerConnectionFactory.createConnection()).thenReturn(amqp);
		when(amqp.isOpen()).thenReturn(true);
		return amqp;
	}

	@Test
	void perfilInexistenteResponde404ConElContratoC3() throws Exception {
		when(perfilConsultaService.obtener("E999")).thenThrow(new PerfilNoEncontradoException("E999"));

		ResultActions resultado = mockMvc.perform(get("/perfiles/E999"));

		assertContratoC3(resultado, 404, "Not Found", "/perfiles/E999");
		resultado.andExpect(jsonPath("$.message").value("El perfil del empleado E999 no existe"));
	}

	@Test
	void putConJsonMalFormadoResponde400() throws Exception {
		ResultActions resultado = mockMvc.perform(put("/perfiles/E001")
				.contentType(MediaType.APPLICATION_JSON).content("{ no es json"));

		assertContratoC3(resultado, 400, "Bad Request", "/perfiles/E001");
	}

	@Test
	void putDePerfilInexistenteResponde404() throws Exception {
		when(perfilConsultaService.actualizar(eq("E999"), any(ActualizarPerfilRequest.class)))
				.thenThrow(new PerfilNoEncontradoException("E999"));

		ResultActions resultado = mockMvc.perform(put("/perfiles/E999")
				.contentType(MediaType.APPLICATION_JSON).content("{\"telefono\":\"123\"}"));

		assertContratoC3(resultado, 404, "Not Found", "/perfiles/E999");
	}

	@Test
	void metodoNoSoportadoResponde405() throws Exception {
		assertContratoC3(mockMvc.perform(delete("/perfiles/E001")), 405, "Method Not Allowed", "/perfiles/E001");
	}

	@Test
	void rutaInexistenteResponde404() throws Exception {
		assertContratoC3(mockMvc.perform(get("/no-existe")), 404, "Not Found", "/no-existe");
	}

	@Test
	void postgresCaidoResponde503() throws Exception {
		when(perfilConsultaService.listar())
				.thenThrow(new CannotCreateTransactionException("Could not open JPA EntityManager"));

		ResultActions resultado = mockMvc.perform(get("/perfiles"));

		assertContratoC3(resultado, 503, "Service Unavailable", "/perfiles");
		resultado.andExpect(jsonPath("$.message").value("La base de datos no está disponible"));
	}

	@Test
	void errorInesperadoResponde500SinDetallesInternos() throws Exception {
		when(perfilConsultaService.listar()).thenThrow(new IllegalStateException("detalle interno"));

		ResultActions resultado = mockMvc.perform(get("/perfiles"));

		assertContratoC3(resultado, 500, "Internal Server Error", "/perfiles");
		resultado.andExpect(jsonPath("$.message").value("Error interno del servidor"));
	}

	@Test
	void traceIdSePropagaDesdeTraceparent() throws Exception {
		when(perfilConsultaService.obtener("E999")).thenThrow(new PerfilNoEncontradoException("E999"));

		mockMvc.perform(get("/perfiles/E999").header("traceparent", "00-" + TRACE_ID + "-00f067aa0ba902b7-01"))
				.andExpect(jsonPath("$.traceId").value(TRACE_ID));
	}

	@Test
	void healthResponde200ConElContratoC2() throws Exception {
		Connection connection = Mockito.mock(Connection.class);
		when(dataSource.getConnection()).thenReturn(connection);
		when(connection.isValid(anyInt())).thenReturn(true);
		brokerUp();

		mockMvc.perform(get("/health"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("UP"))
				.andExpect(jsonPath("$.service").value("perfiles-service"))
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
		brokerUp();

		long inicio = System.nanoTime();
		mockMvc.perform(get("/health"))
				.andExpect(status().isServiceUnavailable())
				.andExpect(jsonPath("$.status").value("DOWN"))
				.andExpect(jsonPath("$.checks.database").value("DOWN"))
				.andExpect(jsonPath("$.checks.messageBroker").value("UP"));
		assertThat((System.nanoTime() - inicio) / 1_000_000).isLessThan(3000);
	}

	@Test
	void healthResponde503ConRabbitMqCaido() throws Exception {
		Connection connection = Mockito.mock(Connection.class);
		when(dataSource.getConnection()).thenReturn(connection);
		when(connection.isValid(anyInt())).thenReturn(true);
		when(brokerConnectionFactory.createConnection())
				.thenThrow(new org.springframework.amqp.AmqpConnectException(new java.net.ConnectException("refused")));

		mockMvc.perform(get("/health"))
				.andExpect(status().isServiceUnavailable())
				.andExpect(jsonPath("$.checks.database").value("UP"))
				.andExpect(jsonPath("$.checks.messageBroker").value("DOWN"));
	}
}

package com.microservicios.perfiles.exception;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;

class GlobalExceptionHandlerTest {

	private static final String TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";

	private final GlobalExceptionHandler handler = new GlobalExceptionHandler();
	private final MockHttpServletRequest request = new MockHttpServletRequest("GET", "/perfiles/E999");

	@AfterEach
	void limpiarMdc() {
		MDC.clear();
	}

	@Test
	void perfilNoEncontradoDevuelve404ConElContratoC3() {
		var respuesta = handler.noEncontrado(new PerfilNoEncontradoException("E999"), request);

		assertThat(respuesta.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
		var body = respuesta.getBody();
		assertThat(body.status()).isEqualTo(404);
		assertThat(body.error()).isEqualTo("Not Found");
		assertThat(body.message()).isEqualTo("El perfil del empleado E999 no existe");
		assertThat(body.path()).isEqualTo("/perfiles/E999");
		assertThat(Instant.parse(body.timestamp())).isNotNull();
		assertThat(body.traceId()).isNull();
	}

	@Test
	void traceIdSeTomaDelMdcDelAgenteOpenTelemetry() {
		MDC.put("trace_id", TRACE_ID);

		var respuesta = handler.noEncontrado(new PerfilNoEncontradoException("E999"), request);

		assertThat(respuesta.getBody().traceId()).isEqualTo(TRACE_ID);
	}

	@Test
	void falloDeRecursoDeDatosEs503SinDetallesInternos() {
		var respuesta = handler.baseDeDatosNoDisponible(
				new DataAccessResourceFailureException("Connection to database-perfiles:5432 refused"), request);

		assertThat(respuesta.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
		assertThat(respuesta.getBody().message()).isEqualTo("La base de datos no está disponible");
		assertThat(respuesta.getBody().toString()).doesNotContain("database-perfiles");
	}

	@Test
	void errorInesperadoDevuelve500Generico() {
		var respuesta = handler.inesperado(new IllegalStateException("PerfilConsultaService.java:42"), request);

		assertThat(respuesta.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
		assertThat(respuesta.getBody().message()).isEqualTo("Error interno del servidor");
		assertThat(respuesta.getBody().toString()).doesNotContain("PerfilConsultaService");
	}
}

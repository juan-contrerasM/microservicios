package com.microservicios.Reto1.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.SQLException;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

@ExtendWith(MockitoExtension.class)
class HealthControllerTest {

	@Mock
	private DataSource dataSource;
	@Mock
	private Connection connection;

	private HealthController controller;

	@BeforeEach
	void setUp() {
		controller = new HealthController(dataSource);
	}

	@Test
	void healthDevuelve200CuandoPostgreSqlEstaDisponible() throws SQLException {
		when(dataSource.getConnection()).thenReturn(connection);
		when(connection.isValid(2)).thenReturn(true);

		var respuesta = controller.health();

		assertThat(respuesta.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(respuesta.getBody().status()).isEqualTo("UP");
	}

	@Test
	void healthDevuelve503CuandoPostgreSqlNoEstaDisponible() throws SQLException {
		when(dataSource.getConnection()).thenThrow(new SQLException("sin conexión"));

		var respuesta = controller.health();

		assertThat(respuesta.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
		assertThat(respuesta.getBody().status()).isEqualTo("DOWN");
	}
}

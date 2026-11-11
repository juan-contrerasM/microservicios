package com.microservicios.perfiles.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.SQLException;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.AmqpConnectException;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.http.HttpStatus;

/**
 * Contrato C2 sin Docker: dependencias simuladas con Mockito, incluido el caso
 * del hallazgo de Fase 1 (PostgreSQL que cuelga en lugar de rechazar la conexión).
 */
@ExtendWith(MockitoExtension.class)
class HealthControllerTest {

	private static final long LIMITE_CONTRATO_MS = 3000;

	@Mock
	private DataSource dataSource;
	@Mock
	private Connection connection;
	@Mock
	private CachingConnectionFactory broker;
	@Mock
	private org.springframework.amqp.rabbit.connection.Connection brokerConnection;

	private HealthController controller;

	@AfterEach
	void tearDown() {
		if (controller != null) {
			controller.cerrar();
		}
	}

	private HealthController conBroker() {
		controller = new HealthController(dataSource, broker, "1.2.3", 2000);
		return controller;
	}

	private HealthController sinBroker() {
		controller = new HealthController(dataSource, null, "1.2.3", 2000);
		return controller;
	}

	private void baseDeDatosUp() throws SQLException {
		when(dataSource.getConnection()).thenReturn(connection);
		when(connection.isValid(anyInt())).thenReturn(true);
	}

	private void brokerUp() {
		when(broker.createConnection()).thenReturn(brokerConnection);
		when(brokerConnection.isOpen()).thenReturn(true);
	}

	@Test
	void healthDevuelve200ConElContratoC2CuandoTodoEstaUp() throws SQLException {
		baseDeDatosUp();
		brokerUp();

		var respuesta = conBroker().health();

		assertThat(respuesta.getStatusCode()).isEqualTo(HttpStatus.OK);
		var body = respuesta.getBody();
		assertThat(body.status()).isEqualTo("UP");
		assertThat(body.service()).isEqualTo("perfiles-service");
		assertThat(body.version()).isEqualTo("1.2.3");
		assertThat(body.checks()).containsExactly(
				org.assertj.core.api.Assertions.entry("database", "UP"),
				org.assertj.core.api.Assertions.entry("messageBroker", "UP"));
	}

	@Test
	void healthSinBrokerSoloReportaLaBaseDeDatos() throws SQLException {
		baseDeDatosUp();

		var respuesta = sinBroker().health();

		assertThat(respuesta.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(respuesta.getBody().checks()).containsOnlyKeys("database");
	}

	@Test
	void healthDevuelve503CuandoPostgreSqlRechazaLaConexion() throws SQLException {
		when(dataSource.getConnection()).thenThrow(new SQLException("sin conexión"));
		brokerUp();

		var respuesta = conBroker().health();

		assertThat(respuesta.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
		assertThat(respuesta.getBody().status()).isEqualTo("DOWN");
		assertThat(respuesta.getBody().checks()).containsEntry("database", "DOWN")
				.containsEntry("messageBroker", "UP");
	}

	@Test
	void healthDevuelve503CuandoLaConexionNoEsValida() throws SQLException {
		when(dataSource.getConnection()).thenReturn(connection);
		when(connection.isValid(anyInt())).thenReturn(false);

		var respuesta = sinBroker().health();

		assertThat(respuesta.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
	}

	@Test
	void healthResponde503EnMenosDeTresSegundosAunquePostgreSqlCuelgue() throws SQLException {
		// Hikari esperando una conexión que nunca llega (hallazgo de Fase 1).
		when(dataSource.getConnection()).thenAnswer(invocacion -> {
			Thread.sleep(30_000);
			return connection;
		});
		brokerUp();

		long inicio = System.nanoTime();
		var respuesta = conBroker().health();
		long transcurridoMs = (System.nanoTime() - inicio) / 1_000_000;

		assertThat(transcurridoMs).isLessThan(LIMITE_CONTRATO_MS);
		assertThat(respuesta.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
		assertThat(respuesta.getBody().checks()).containsEntry("database", "DOWN")
				.containsEntry("messageBroker", "UP");
	}

	@Test
	void healthResponde503EnMenosDeTresSegundosAunqueAmbasDependenciasCuelguen() throws SQLException {
		when(dataSource.getConnection()).thenAnswer(invocacion -> {
			Thread.sleep(30_000);
			return connection;
		});
		when(broker.createConnection()).thenAnswer(invocacion -> {
			Thread.sleep(30_000);
			return brokerConnection;
		});

		long inicio = System.nanoTime();
		var respuesta = conBroker().health();
		long transcurridoMs = (System.nanoTime() - inicio) / 1_000_000;

		assertThat(transcurridoMs).isLessThan(LIMITE_CONTRATO_MS);
		assertThat(respuesta.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
		assertThat(respuesta.getBody().checks()).containsEntry("database", "DOWN")
				.containsEntry("messageBroker", "DOWN");
	}

	@Test
	void healthDevuelve503CuandoRabbitMqNoEstaDisponible() throws SQLException {
		baseDeDatosUp();
		when(broker.createConnection()).thenThrow(new AmqpConnectException(new java.net.ConnectException("refused")));

		var respuesta = conBroker().health();

		assertThat(respuesta.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
		assertThat(respuesta.getBody().checks()).containsEntry("database", "UP")
				.containsEntry("messageBroker", "DOWN");
	}
}

package com.microservicios.perfiles.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.connection.Connection;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.ShutdownSignalException;
import com.rabbitmq.client.impl.AMQImpl;

class MigradorColasTest {

	private ConnectionFactory connectionFactory;
	private Connection conexion;
	private Channel primero;
	private Channel segundo;
	private RabbitAdmin admin;
	private MigradorColas migrador;

	@BeforeEach
	void setUp() {
		connectionFactory = mock(ConnectionFactory.class);
		conexion = mock(Connection.class);
		primero = mock(Channel.class);
		segundo = mock(Channel.class);
		admin = mock(RabbitAdmin.class);
		when(connectionFactory.createConnection()).thenReturn(conexion);
		when(conexion.createChannel(false)).thenReturn(primero, segundo);
		migrador = new MigradorColas(connectionFactory, admin);
	}

	@Test
	void laColaRetryVuelveSoloALaColaDeOrigen() {
		Queue retry = BrokerConfig.colaRetry("q.perfiles");
		Queue principal = BrokerConfig.colaPrincipal("q.perfiles");

		assertThat(retry.getName()).isEqualTo("q.perfiles.retry");
		assertThat(retry.getArguments()).isEqualTo(Map.of(
				"x-message-ttl", 5000,
				"x-dead-letter-exchange", "",
				"x-dead-letter-routing-key", "q.perfiles"));
		assertThat(principal.getArguments()).isEqualTo(Map.of(
				"x-dead-letter-exchange", "onboarding.dlx",
				"x-dead-letter-routing-key", "q.perfiles"));
	}

	@Test
	void colaCompatibleNoSeToca() throws IOException {
		migrador.asegurar(BrokerConfig.colaRetry("q.perfiles"));

		verify(primero).queueDeclare(eq("q.perfiles.retry"), eq(true), eq(false), eq(false), anyMap());
		verify(admin, never()).initialize();
	}

	@Test
	void colaVaciaConArgumentosAntiguosSeRecreaYSeRedeclaraLaTopologia() throws IOException {
		when(primero.queueDeclare(eq("q.perfiles.retry"), anyBoolean(), anyBoolean(), anyBoolean(), anyMap()))
				.thenThrow(preconditionFailed());

		migrador.asegurar(BrokerConfig.colaRetry("q.perfiles"));

		verify(segundo).queueDelete("q.perfiles.retry", false, true);
		verify(segundo).queueDeclare(eq("q.perfiles.retry"), eq(true), eq(false), eq(false), anyMap());
		verify(admin).initialize();
	}

	@Test
	void colaConMensajesNoSeBorraYElErrorRemiteALaGuia() throws IOException {
		when(primero.queueDeclare(eq("q.perfiles"), anyBoolean(), anyBoolean(), anyBoolean(), anyMap()))
				.thenThrow(preconditionFailed());
		when(segundo.queueDelete("q.perfiles", false, true)).thenThrow(preconditionFailed());

		assertThatThrownBy(() -> migrador.asegurarCola(BrokerConfig.colaPrincipal("q.perfiles")))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("docs/eventos.md");
		verify(segundo, never()).queueDeclare(eq("q.perfiles"), anyBoolean(), anyBoolean(), anyBoolean(), anyMap());
	}

	@Test
	void otroErrorNoSeConfundeConPreconditionFailed() {
		assertThat(MigradorColas.esPreconditionFailed(new IOException("conexión rechazada"))).isFalse();
		assertThat(MigradorColas.esPreconditionFailed(preconditionFailed())).isTrue();
	}

	private static IOException preconditionFailed() {
		AMQP.Channel.Close cierre = new AMQImpl.Channel.Close(406, "PRECONDITION_FAILED - inequivalent arg", 50, 10);
		return new IOException(new ShutdownSignalException(false, true, cierre, null));
	}
}

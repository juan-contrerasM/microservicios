package com.microservicios.Reto1.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.transaction.annotation.Transactional;

import com.microservicios.Reto1.config.BrokerProperties;
import com.microservicios.Reto1.service.EmpleadoVacacionesService;
import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;

import tools.jackson.databind.json.JsonMapper;

/**
 * Fase 4 (E12) con dobles: sin RabbitMQ ni PostgreSQL.
 */
@ExtendWith(MockitoExtension.class)
class VacacionesEventListenerTest {

	private static final String TRACEPARENT = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";
	private static final String VALIDO = """
			{"id":"evt-1","type":"vacaciones.iniciadas","version":1,"occurredAt":"2026-11-10T22:08:00Z",
			 "producer":"empleados-service","data":{"empleadoId":"E001","email":"juan@empresa.com"}}""";

	@Mock
	private EmpleadoVacacionesService servicio;

	@Mock
	private Channel canal;

	private VacacionesEventListener listener;

	@BeforeEach
	void setUp() {
		BrokerProperties broker = new BrokerProperties();
		broker.setQueue("q.empleados");
		listener = new VacacionesEventListener(JsonMapper.builder().build(), servicio, broker);
	}

	@Test
	void eventoValidoSeProcesaYSeConfirma() throws IOException {
		listener.consumir(mensaje(VALIDO, Map.of()), canal);

		verify(servicio).procesar("evt-1", "vacaciones.iniciadas", Map.of("empleadoId", "E001", "email", "juan@empresa.com"));
		verify(canal).basicAck(7L, false);
		verify(canal, never()).basicReject(anyLong(), any(Boolean.class));
	}

	@ParameterizedTest
	@ValueSource(strings = {
			"no es json",
			"[1,2]",
			"{\"id\":\"evt-1\",\"type\":\"vacaciones.iniciadas\",\"version\":1,\"occurredAt\":\"2026-11-10T22:08:00Z\",\"data\":{}}",
			"{\"id\":\"\",\"type\":\"vacaciones.iniciadas\",\"version\":1,\"occurredAt\":\"2026-11-10T22:08:00Z\",\"producer\":\"x\",\"data\":{}}",
			"{\"id\":\"evt-1\",\"type\":\"usuario.creado\",\"version\":1,\"occurredAt\":\"2026-11-10T22:08:00Z\",\"producer\":\"x\",\"data\":{}}",
			"{\"id\":\"evt-1\",\"type\":\"vacaciones.iniciadas\",\"version\":\"1\",\"occurredAt\":\"2026-11-10T22:08:00Z\",\"producer\":\"x\",\"data\":{}}",
			"{\"id\":\"evt-1\",\"type\":\"vacaciones.iniciadas\",\"version\":0,\"occurredAt\":\"2026-11-10T22:08:00Z\",\"producer\":\"x\",\"data\":{}}",
			"{\"id\":\"evt-1\",\"type\":\"vacaciones.iniciadas\",\"version\":1,\"occurredAt\":\"ayer\",\"producer\":\"x\",\"data\":{}}",
			"{\"id\":\"evt-1\",\"type\":\"vacaciones.iniciadas\",\"version\":1,\"occurredAt\":\"2026-11-10T22:08:00Z\",\"producer\":\"x\",\"data\":[1]}"
	})
	void envelopeInvalidoSeRechazaSinRequeueYNoTocaLaBase(String cuerpo) throws IOException {
		listener.consumir(mensaje(cuerpo, Map.of()), canal);

		verify(canal).basicReject(7L, false);
		verify(canal, never()).basicAck(anyLong(), any(Boolean.class));
		verifyNoInteractions(servicio);
	}

	@Test
	void falloPublicaEnLaColaRetryPropiaConContadorYConservaHeaders() throws IOException {
		doThrow(new IllegalStateException("base caída")).when(servicio).procesar(anyString(), anyString(), any());

		listener.consumir(mensaje(VALIDO, Map.of("traceparent", TRACEPARENT,
				"x-death", List.of(Map.of("count", 1L)), "x-first-death-queue", "q.empleados.retry")), canal);

		ArgumentCaptor<AMQP.BasicProperties> props = ArgumentCaptor.forClass(AMQP.BasicProperties.class);
		// Exchange por defecto + cola retry propia: el reintento no se re-difunde a otros consumidores.
		verify(canal).basicPublish(eq(""), eq("q.empleados.retry"), props.capture(), any(byte[].class));
		verify(canal).basicAck(7L, false);
		Map<String, Object> headers = props.getValue().getHeaders();
		assertThat(headers).containsEntry("x-retry-count", 1)
				.containsEntry("traceparent", TRACEPARENT)
				.containsEntry("x-original-routing-key", "vacaciones.iniciadas")
				.doesNotContainKeys("x-death", "x-first-death-queue");
		assertThat(props.getValue().getDeliveryMode()).isEqualTo(2);
		assertThat(props.getValue().getMessageId()).isEqualTo("evt-1");
	}

	@Test
	void elContadorAvanzaYConservaLaRoutingKeyOriginal() throws IOException {
		doThrow(new IllegalStateException("base caída")).when(servicio).procesar(anyString(), anyString(), any());

		Message mensaje = mensaje(VALIDO, Map.of("x-retry-count", 2, "x-original-routing-key", "vacaciones.iniciadas"));
		mensaje.getMessageProperties().setReceivedRoutingKey("q.empleados");
		listener.consumir(mensaje, canal);

		ArgumentCaptor<AMQP.BasicProperties> props = ArgumentCaptor.forClass(AMQP.BasicProperties.class);
		verify(canal).basicPublish(eq(""), eq("q.empleados.retry"), props.capture(), any(byte[].class));
		assertThat(props.getValue().getHeaders()).containsEntry("x-retry-count", 3)
				.containsEntry("x-original-routing-key", "vacaciones.iniciadas");
	}

	@Test
	void contadorAgotadoVaADlq() throws IOException {
		doThrow(new IllegalStateException("base caída")).when(servicio).procesar(anyString(), anyString(), any());

		listener.consumir(mensaje(VALIDO, Map.of("x-retry-count", 3)), canal);

		verify(canal).basicReject(7L, false);
		verify(canal, never()).basicPublish(anyString(), anyString(), any(), any(byte[].class));
		verify(canal, never()).basicAck(anyLong(), any(Boolean.class));
	}

	@Test
	void siNoSePuedeProgramarElReintentoVaADlq() throws IOException {
		doThrow(new IllegalStateException("base caída")).when(servicio).procesar(anyString(), anyString(), any());
		doThrow(new IOException("canal caído")).when(canal)
				.basicPublish(anyString(), anyString(), any(), any(byte[].class));

		listener.consumir(mensaje(VALIDO, Map.of()), canal);

		verify(canal).basicReject(7L, false);
		verify(canal, never()).basicAck(anyLong(), any(Boolean.class));
	}

	@Test
	void laDeduplicacionYElEfectoCompartenTransaccion() throws NoSuchMethodException {
		assertThat(EmpleadoVacacionesService.class.getMethod("procesar", String.class, String.class, Map.class)
				.isAnnotationPresent(Transactional.class)).isTrue();
	}

	private static Message mensaje(String cuerpo, Map<String, Object> headers) {
		MessageProperties propiedades = new MessageProperties();
		propiedades.setDeliveryTag(7L);
		propiedades.setReceivedRoutingKey("vacaciones.iniciadas");
		propiedades.setContentType("application/json");
		propiedades.setMessageId("evt-1");
		headers.forEach(propiedades::setHeader);
		return new Message(cuerpo.getBytes(StandardCharsets.UTF_8), propiedades);
	}
}

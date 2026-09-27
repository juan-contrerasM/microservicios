package com.microservicios.Reto1.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import com.microservicios.Reto1.config.BrokerProperties;
import com.microservicios.Reto1.model.Empleado;
import com.microservicios.Reto1.model.EstadoEmpleado;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

@ExtendWith(MockitoExtension.class)
class RabbitEmpleadoEventPublisherTest {

	@Mock
	private RabbitTemplate rabbitTemplate;

	private RabbitEmpleadoEventPublisher publisher;

	@BeforeEach
	void setUp() {
		BrokerProperties broker = new BrokerProperties();
		broker.setExchange("onboarding.eventos");
		ObjectMapper mapper = JsonMapper.builder().findAndAddModules().build();
		Clock clock = Clock.fixed(Instant.parse("2026-09-26T14:00:00Z"), ZoneOffset.UTC);
		publisher = new RabbitEmpleadoEventPublisher(rabbitTemplate, mapper, broker, clock);
	}

	@Test
	void publicaCreadoEnElExchangeConLaRoutingKeyDelTipo() {
		publisher.publicarCreado(empleado());

		ArgumentCaptor<Message> mensaje = ArgumentCaptor.forClass(Message.class);
		verify(rabbitTemplate).send(eq("onboarding.eventos"), eq("empleado.creado"), mensaje.capture());
		String json = new String(mensaje.getValue().getBody(), StandardCharsets.UTF_8);
		assertThat(json).contains("\"type\":\"empleado.creado\"");
		assertThat(json).contains("\"version\":1");
		assertThat(json).contains("\"producer\":\"empleados-service\"");
		assertThat(json).contains("\"empleadoId\":\"E001\"");
		assertThat(json).contains("\"estado\":\"ACTIVO\"");
		assertThat(json).contains("\"occurredAt\":\"2026-09-26T14:00:00Z\"");
		assertThat(mensaje.getValue().getMessageProperties().getContentType()).isEqualTo("application/json");
	}

	@Test
	void publicaRetiradoConFechaRetiro() {
		Empleado empleado = empleado();
		empleado.setEstado(EstadoEmpleado.RETIRADO);
		empleado.setFechaRetiro(Instant.parse("2026-09-26T14:05:00Z"));

		publisher.publicarRetirado(empleado);

		ArgumentCaptor<Message> mensaje = ArgumentCaptor.forClass(Message.class);
		verify(rabbitTemplate).send(eq("onboarding.eventos"), eq("empleado.retirado"), mensaje.capture());
		String json = new String(mensaje.getValue().getBody(), StandardCharsets.UTF_8);
		assertThat(json).contains("\"type\":\"empleado.retirado\"");
		assertThat(json).contains("\"fechaRetiro\":\"2026-09-26T14:05:00Z\"");
		assertThat(json).contains("\"estado\":\"RETIRADO\"");
		assertThat(json).doesNotContain("numeroEmpleado");
	}

	@Test
	void unFalloDelBrokerNoSePropaga() {
		doThrow(new RuntimeException("conexión rechazada")).when(rabbitTemplate)
				.send(any(), any(), any(Message.class));

		publisher.publicarCreado(empleado());
	}

	private Empleado empleado() {
		Empleado empleado = new Empleado();
		empleado.setId("E001");
		empleado.setNombre("Juan");
		empleado.setApellido("Pérez");
		empleado.setEmail("juan.perez@empresa.com");
		empleado.setNumeroEmpleado("EMP-2026-001");
		empleado.setCargo("Desarrollador Senior");
		empleado.setArea("Tecnología");
		empleado.setDepartamentoId("IT");
		empleado.setFechaIngreso(LocalDate.of(2026, 3, 1));
		empleado.setEstado(EstadoEmpleado.ACTIVO);
		return empleado;
	}
}

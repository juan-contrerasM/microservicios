package com.microservicios.Reto1.event;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import com.microservicios.Reto1.config.BrokerProperties;
import com.microservicios.Reto1.model.Empleado;

import tools.jackson.databind.ObjectMapper;

/**
 * Publica en el exchange topic con routing key = {@code type}.
 * Un fallo de red o de serialización queda en el log y no se relanza.
 */
@Component
public class RabbitEmpleadoEventPublisher implements EmpleadoEventPublisher {

	static final String PRODUCER = "empleados-service";
	static final String CREADO = "empleado.creado";
	static final String ACTUALIZADO = "empleado.actualizado";
	static final String RETIRADO = "empleado.retirado";
	static final int VERSION = 1;

	private static final Logger log = LoggerFactory.getLogger(RabbitEmpleadoEventPublisher.class);

	private final RabbitTemplate rabbitTemplate;
	private final ObjectMapper objectMapper;
	private final BrokerProperties brokerProperties;
	private final Clock clock;

	public RabbitEmpleadoEventPublisher(RabbitTemplate rabbitTemplate, ObjectMapper objectMapper,
			BrokerProperties brokerProperties, Clock clock) {
		this.rabbitTemplate = rabbitTemplate;
		this.objectMapper = objectMapper;
		this.brokerProperties = brokerProperties;
		this.clock = clock;
	}

	@Override
	public void publicarCreado(Empleado empleado) {
		publicar(CREADO, datosPersistidos(empleado), Instant.now(clock), empleado.getId());
	}

	@Override
	public void publicarActualizado(Empleado empleado) {
		publicar(ACTUALIZADO, datosPersistidos(empleado), Instant.now(clock), empleado.getId());
	}

	@Override
	public void publicarRetirado(Empleado empleado) {
		EmpleadoRetiradoData data = new EmpleadoRetiradoData(
				empleado.getId(),
				empleado.getNombre(),
				empleado.getApellido(),
				empleado.getEmail(),
				empleado.getFechaRetiro(),
				empleado.getEstado().name());
		publicar(RETIRADO, data, empleado.getFechaRetiro(), empleado.getId());
	}

	private EmpleadoEventoData datosPersistidos(Empleado empleado) {
		return new EmpleadoEventoData(
				empleado.getId(),
				empleado.getNombre(),
				empleado.getApellido(),
				empleado.getEmail(),
				empleado.getNumeroEmpleado(),
				empleado.getCargo(),
				empleado.getArea(),
				empleado.getDepartamentoId(),
				empleado.getFechaIngreso(),
				empleado.getEstado().name());
	}

	private void publicar(String type, Object data, Instant occurredAt, String empleadoId) {
		try {
			EventEnvelope envelope = new EventEnvelope(
					UUID.randomUUID().toString(),
					type,
					VERSION,
					occurredAt,
					PRODUCER,
					data);
			byte[] body = objectMapper.writeValueAsBytes(envelope);
			Message message = MessageBuilder.withBody(body)
					.setContentType(MessageProperties.CONTENT_TYPE_JSON)
					.build();
			rabbitTemplate.send(brokerProperties.getExchange(), type, message);
			log.info("Evento publicado {} para empleado {}", type, empleadoId);
		} catch (Exception ex) {
			log.error("Fallo publicando {} para empleado {}: {}", type, empleadoId, ex.getMessage());
		}
	}
}

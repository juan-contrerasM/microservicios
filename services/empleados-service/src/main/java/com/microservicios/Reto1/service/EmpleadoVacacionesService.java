package com.microservicios.Reto1.service;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.microservicios.Reto1.model.EstadoEmpleado;
import com.microservicios.Reto1.model.EventoProcesado;
import com.microservicios.Reto1.repository.EmpleadoRepository;
import com.microservicios.Reto1.repository.EventoProcesadoRepository;

/**
 * Estado {@code EN_VACACIONES} del PDF: Empleados consume {@code vacaciones.iniciadas} y
 * {@code vacaciones.finalizadas} de Vacaciones. La marca de evento procesado y el cambio de estado
 * van en la misma transacción (deduplicación atómica, E12).
 * <ul>
 * <li>iniciadas: {@code ACTIVO → EN_VACACIONES}.</li>
 * <li>finalizadas: {@code EN_VACACIONES → ACTIVO}.</li>
 * <li>Un empleado {@code RETIRADO} (o {@code PENDIENTE_VALIDACION}) no cambia: el retiro es
 * definitivo aunque llegue durante unas vacaciones.</li>
 * </ul>
 */
@Service
public class EmpleadoVacacionesService {

	private static final Logger log = LoggerFactory.getLogger(EmpleadoVacacionesService.class);

	private final EmpleadoRepository empleados;
	private final EventoProcesadoRepository eventos;
	private final Clock clock;

	public EmpleadoVacacionesService(EmpleadoRepository empleados, EventoProcesadoRepository eventos, Clock clock) {
		this.empleados = empleados;
		this.eventos = eventos;
		this.clock = clock;
	}

	/** @return {@code false} si el evento ya se había procesado (duplicado) o no trae id. */
	@Transactional
	public boolean procesar(String eventId, String type, Map<String, Object> data) {
		if (eventId == null || eventId.isBlank()) {
			log.error("Evento sin id; se confirma sin efecto");
			return false;
		}
		if (eventos.existsById(eventId)) {
			return false;
		}
		Object valor = data == null ? null : data.get("empleadoId");
		String empleadoId = valor == null ? null : valor.toString().trim();
		if (empleadoId == null || empleadoId.isEmpty()) {
			log.error("{} sin empleadoId; se confirma sin efecto", type);
		} else {
			EstadoEmpleado desde = "vacaciones.iniciadas".equals(type) ? EstadoEmpleado.ACTIVO : EstadoEmpleado.EN_VACACIONES;
			EstadoEmpleado hacia = "vacaciones.iniciadas".equals(type) ? EstadoEmpleado.EN_VACACIONES : EstadoEmpleado.ACTIVO;
			empleados.findById(empleadoId).ifPresentOrElse(empleado -> {
				if (empleado.getEstado() == desde) {
					empleado.setEstado(hacia);
					empleados.save(empleado);
					log.info("Empleado {}: {} → {} por {}", empleadoId, desde, hacia, type);
				} else {
					log.info("Empleado {} está {}; {} no cambia su estado", empleadoId, empleado.getEstado(), type);
				}
			}, () -> log.error("{} para un empleado inexistente ({}); se confirma sin efecto", type, empleadoId));
		}
		eventos.save(new EventoProcesado(eventId, Instant.now(clock)));
		return true;
	}
}

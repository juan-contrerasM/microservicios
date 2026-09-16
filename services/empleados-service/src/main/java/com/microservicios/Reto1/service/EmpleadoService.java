package com.microservicios.Reto1.service;

import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import com.microservicios.Reto1.client.DepartamentoClient;
import com.microservicios.Reto1.exception.ConflictException;
import com.microservicios.Reto1.exception.EmpleadoNoEncontradoException;
import com.microservicios.Reto1.model.Empleado;
import com.microservicios.Reto1.model.EstadoEmpleado;
import com.microservicios.Reto1.repository.EmpleadoRepository;

/**
 * Reglas de negocio para el alta y consulta de empleados.
 */
@Service
public class EmpleadoService {

	private static final Logger log = LoggerFactory.getLogger(EmpleadoService.class);

	private final EmpleadoRepository empleadoRepository;
	private final DepartamentoClient departamentoClient;

	public EmpleadoService(EmpleadoRepository empleadoRepository, DepartamentoClient departamentoClient) {
		this.empleadoRepository = empleadoRepository;
		this.departamentoClient = departamentoClient;
	}

	/**
	 * Registra un empleado en estado ACTIVO, validando unicidad local y la
	 * existencia del departamento mediante su API.
	 *
	 * @param empleado empleado a registrar
	 * @return el empleado persistido
	 * @throws ConflictException si el id, el email o el numeroEmpleado ya existen
	 */
	public Empleado registrar(Empleado empleado) {
		log.debug("Registrando empleado con id {}", empleado.getId());

		if (empleadoRepository.existsById(empleado.getId())) {
			log.warn("Intento de registrar un empleado con id duplicado: {}", empleado.getId());
			throw new ConflictException("Ya existe un empleado con ese id");
		}
		if (empleadoRepository.existsByEmail(empleado.getEmail())) {
			log.warn("Intento de registrar un empleado con email duplicado");
			throw new ConflictException("Ya existe un empleado registrado con ese email");
		}
		if (empleadoRepository.existsByNumeroEmpleado(empleado.getNumeroEmpleado())) {
			log.warn("Intento de registrar un empleado con numeroEmpleado duplicado: {}", empleado.getNumeroEmpleado());
			throw new ConflictException("Ya existe un empleado registrado con ese numeroEmpleado");
		}

		departamentoClient.validarExistencia(empleado.getDepartamentoId());

		// En este reto solo se maneja ACTIVO
		empleado.setEstado(EstadoEmpleado.ACTIVO);
		try {
			Empleado registrado = empleadoRepository.save(empleado);
			log.info("Empleado registrado con id {}", registrado.getId());
			return registrado;
		} catch (DataIntegrityViolationException ex) {
			throw traducirViolacionDeUnicidad(ex);
		}
	}

	private ConflictException traducirViolacionDeUnicidad(DataIntegrityViolationException ex) {
		String detalle = String.valueOf(ex.getMostSpecificCause().getMessage()).toLowerCase(Locale.ROOT);
		if (detalle.contains("uk_empleados_email")) {
			return new ConflictException("Ya existe un empleado registrado con ese email");
		}
		if (detalle.contains("uk_empleados_numero_empleado")) {
			return new ConflictException("Ya existe un empleado registrado con ese numeroEmpleado");
		}
		if (detalle.contains("pk_empleados")) {
			return new ConflictException("Ya existe un empleado con ese id");
		}
		return new ConflictException("Ya existe un empleado con datos únicos duplicados");
	}

	/**
	 * Busca un empleado por su id.
	 *
	 * @param id identificador del empleado
	 * @return el empleado encontrado
	 * @throws EmpleadoNoEncontradoException si no existe un empleado con ese id
	 */
	public Empleado consultarPorId(String id) {
		log.debug("Consultando empleado con id {}", id);
		return empleadoRepository.findById(id)
				.orElseThrow(() -> {
					log.warn("Empleado no encontrado con id {}", id);
					return new EmpleadoNoEncontradoException(id);
				});
	}
}

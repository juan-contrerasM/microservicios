package com.microservicios.Reto1.service;

import java.util.List;
import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import com.microservicios.Reto1.client.DepartamentoClient;
import com.microservicios.Reto1.dto.CircuitBreakerStatus;
import com.microservicios.Reto1.dto.ReconciliacionResultado;
import com.microservicios.Reto1.exception.BadRequestException;
import com.microservicios.Reto1.exception.ConflictException;
import com.microservicios.Reto1.exception.EmpleadoNoEncontradoException;
import com.microservicios.Reto1.exception.ServiceUnavailableException;
import com.microservicios.Reto1.model.Empleado;
import com.microservicios.Reto1.model.EstadoEmpleado;
import com.microservicios.Reto1.repository.EmpleadoRepository;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;

/**
 * Reglas de negocio para el alta y consulta de empleados.
 */
@Service
public class EmpleadoService {

	private static final Logger log = LoggerFactory.getLogger(EmpleadoService.class);
	static final String CIRCUIT_BREAKER_NAME = "departamentos";

	private final EmpleadoRepository empleadoRepository;
	private final DepartamentoClient departamentoClient;
	private final CircuitBreaker circuitBreakerDepartamentos;

	public EmpleadoService(EmpleadoRepository empleadoRepository, DepartamentoClient departamentoClient,
			CircuitBreakerRegistry circuitBreakerRegistry) {
		this.empleadoRepository = empleadoRepository;
		this.departamentoClient = departamentoClient;
		this.circuitBreakerDepartamentos = circuitBreakerRegistry.circuitBreaker(CIRCUIT_BREAKER_NAME);
	}

	/**
	 * Registra un empleado, validando unicidad local y la existencia del
	 * departamento mediante su API a través del Circuit Breaker. Si el
	 * departamento no existe (404), la solicitud se rechaza con 400 y no se
	 * persiste. Si el circuito está OPEN o la llamada falla, el empleado se
	 * persiste igual con {@code estado: PENDIENTE_VALIDACION} (criterio 4).
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

		empleado.setEstado(validarDepartamentoConCircuitBreaker(empleado.getDepartamentoId()));
		try {
			Empleado registrado = empleadoRepository.save(empleado);
			log.info("Empleado registrado con id {} en estado {}", registrado.getId(), registrado.getEstado());
			return registrado;
		} catch (DataIntegrityViolationException ex) {
			throw traducirViolacionDeUnicidad(ex);
		}
	}

	/**
	 * Valida el departamento a través del Circuit Breaker.
	 *
	 * @return {@code ACTIVO} si departamentos confirmó el departamento;
	 *         {@code PENDIENTE_VALIDACION} si el circuito está OPEN o la
	 *         llamada agotó reintentos (fallback de disponibilidad)
	 * @throws BadRequestException si departamentos respondió que el
	 *         departamento no existe (éxito del circuito, error de negocio)
	 */
	private EstadoEmpleado validarDepartamentoConCircuitBreaker(String departamentoId) {
		try {
			circuitBreakerDepartamentos.executeRunnable(() -> departamentoClient.validarExistencia(departamentoId));
			return EstadoEmpleado.ACTIVO;
		} catch (BadRequestException ex) {
			throw ex;
		} catch (CallNotPermittedException ex) {
			log.warn("Circuito 'departamentos' OPEN: se registra el empleado como PENDIENTE_VALIDACION sin llamar a la red");
			return EstadoEmpleado.PENDIENTE_VALIDACION;
		} catch (ServiceUnavailableException ex) {
			log.warn("Fallo validando departamento {} contra departamentos-service: se registra como PENDIENTE_VALIDACION",
					departamentoId);
			return EstadoEmpleado.PENDIENTE_VALIDACION;
		}
	}

	/**
	 * Revalida contra departamentos a cada empleado en PENDIENTE_VALIDACION.
	 * Pasa a ACTIVO el que ya tiene departamento confirmado; el resto queda
	 * pendiente (no se borra ni se le asigna un departamento por defecto).
	 *
	 * @return cuántos pendientes se evaluaron y cuántos quedaron ACTIVO
	 */
	public ReconciliacionResultado reconciliarPendientes() {
		List<Empleado> pendientes = empleadoRepository.findByEstado(EstadoEmpleado.PENDIENTE_VALIDACION);
		int reconciliados = 0;
		for (Empleado empleado : pendientes) {
			if (validarDepartamentoParaReconciliar(empleado)) {
				empleado.setEstado(EstadoEmpleado.ACTIVO);
				empleadoRepository.save(empleado);
				reconciliados++;
			}
		}
		log.info("Reconciliación de pendientes: {} evaluados, {} pasaron a ACTIVO", pendientes.size(), reconciliados);
		return new ReconciliacionResultado(pendientes.size(), reconciliados);
	}

	private boolean validarDepartamentoParaReconciliar(Empleado empleado) {
		try {
			circuitBreakerDepartamentos.executeRunnable(
					() -> departamentoClient.validarExistencia(empleado.getDepartamentoId()));
			return true;
		} catch (BadRequestException ex) {
			log.warn("Departamento {} sigue sin existir; el empleado {} se deja PENDIENTE_VALIDACION para revisión de RRHH",
					empleado.getDepartamentoId(), empleado.getId());
			return false;
		} catch (CallNotPermittedException | ServiceUnavailableException ex) {
			log.warn("departamentos-service sigue sin disponibilidad; el empleado {} se deja PENDIENTE_VALIDACION",
					empleado.getId());
			return false;
		}
	}

	/**
	 * Estado observable del Circuit Breaker que protege la llamada a departamentos.
	 */
	public CircuitBreakerStatus estadoCircuitBreaker() {
		return new CircuitBreakerStatus(circuitBreakerDepartamentos.getName(),
				circuitBreakerDepartamentos.getState().name());
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

	/**
	 * Lista todos los empleados registrados.
	 *
	 * @return los empleados registrados, o una lista vacía si no hay ninguno
	 */
	public List<Empleado> listarTodos() {
		log.debug("Listando todos los empleados");
		return empleadoRepository.findAll();
	}
}

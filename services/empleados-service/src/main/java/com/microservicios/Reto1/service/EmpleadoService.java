package com.microservicios.Reto1.service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.microservicios.Reto1.client.DepartamentoClient;
import com.microservicios.Reto1.dto.ActualizarEmpleadoRequest;
import com.microservicios.Reto1.dto.CircuitBreakerStatus;
import com.microservicios.Reto1.dto.ReconciliacionResultado;
import com.microservicios.Reto1.exception.BadRequestException;
import com.microservicios.Reto1.exception.ConflictException;
import com.microservicios.Reto1.exception.EmpleadoNoEncontradoException;
import com.microservicios.Reto1.exception.ServiceUnavailableException;
import com.microservicios.Reto1.event.EmpleadoEventPublisher;
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
	static final String MOTIVO_RETIRO_POR_DEFECTO = "RENUNCIA";

	private final EmpleadoRepository empleadoRepository;
	private final DepartamentoClient departamentoClient;
	private final CircuitBreaker circuitBreakerDepartamentos;
	private final EmpleadoEventPublisher eventPublisher;
	private final Clock clock;

	public EmpleadoService(EmpleadoRepository empleadoRepository, DepartamentoClient departamentoClient,
			CircuitBreakerRegistry circuitBreakerRegistry, EmpleadoEventPublisher eventPublisher, Clock clock) {
		this.empleadoRepository = empleadoRepository;
		this.departamentoClient = departamentoClient;
		this.circuitBreakerDepartamentos = circuitBreakerRegistry.circuitBreaker(CIRCUIT_BREAKER_NAME);
		this.eventPublisher = eventPublisher;
		this.clock = clock;
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
	@Transactional
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
			publicarDespuesDelCommit(() -> eventPublisher.publicarCreado(registrado));
			return registrado;
		} catch (DataIntegrityViolationException ex) {
			throw traducirViolacionDeUnicidad(ex);
		}
	}

	/**
	 * Actualiza solo los campos presentes. Si cambia {@code departamentoId}, lo
	 * vuelve a validar con el mismo cliente y Circuit Breaker del alta. No
	 * modifica el id ni pasa el estado a RETIRADO.
	 *
	 * @throws EmpleadoNoEncontradoException si el id no existe
	 * @throws ConflictException si el email o el numeroEmpleado ya pertenecen a otro empleado
	 */
	@Transactional
	public Empleado actualizar(String id, ActualizarEmpleadoRequest cambios) {
		Empleado empleado = consultarPorId(id);
		aplicarCambios(empleado, cambios);
		try {
			Empleado guardado = empleadoRepository.save(empleado);
			log.info("Empleado actualizado con id {}", guardado.getId());
			publicarDespuesDelCommit(() -> eventPublisher.publicarActualizado(guardado));
			return guardado;
		} catch (DataIntegrityViolationException ex) {
			throw traducirViolacionDeUnicidad(ex);
		}
	}

	/**
	 * Baja lógica: la fila se queda, el estado pasa a RETIRADO y se guarda
	 * {@code fechaRetiro} en UTC. Un segundo retiro responde 400 y no publica.
	 */
	@Transactional
	public Empleado retirar(String id) {
		return retirar(id, MOTIVO_RETIRO_POR_DEFECTO);
	}

	/**
	 * @param motivo valor de {@code data.motivo} en {@code empleado.retirado}. El catálogo
	 *               lo exige; si el cliente no lo envía se usa {@code RENUNCIA}.
	 */
	@Transactional
	public Empleado retirar(String id, String motivo) {
		Empleado empleado = consultarPorId(id);
		if (empleado.getEstado() == EstadoEmpleado.RETIRADO) {
			throw new BadRequestException("El empleado con id " + id + " ya está retirado");
		}
		String motivoEvento = motivo == null || motivo.isBlank() ? MOTIVO_RETIRO_POR_DEFECTO : motivo;
		empleado.setEstado(EstadoEmpleado.RETIRADO);
		empleado.setFechaRetiro(Instant.now(clock));
		Empleado guardado = empleadoRepository.save(empleado);
		log.info("Empleado retirado con id {}", guardado.getId());
		publicarDespuesDelCommit(() -> eventPublisher.publicarRetirado(guardado, motivoEvento));
		return guardado;
	}

	private void aplicarCambios(Empleado empleado, ActualizarEmpleadoRequest cambios) {
		if (cambios.getNombre() != null) {
			exigirTexto(cambios.getNombre(), "El nombre es obligatorio");
			empleado.setNombre(cambios.getNombre());
		}
		if (cambios.getApellido() != null) {
			exigirTexto(cambios.getApellido(), "El apellido es obligatorio");
			empleado.setApellido(cambios.getApellido());
		}
		if (cambios.getEmail() != null) {
			exigirTexto(cambios.getEmail(), "El email es obligatorio");
			if (empleadoRepository.findByEmail(cambios.getEmail())
					.filter(otro -> !otro.getId().equals(empleado.getId()))
					.isPresent()) {
				throw new ConflictException("Ya existe un empleado registrado con ese email");
			}
			empleado.setEmail(cambios.getEmail());
		}
		if (cambios.getNumeroEmpleado() != null) {
			exigirTexto(cambios.getNumeroEmpleado(), "El numeroEmpleado es obligatorio");
			if (empleadoRepository.findByNumeroEmpleado(cambios.getNumeroEmpleado())
					.filter(otro -> !otro.getId().equals(empleado.getId()))
					.isPresent()) {
				throw new ConflictException("Ya existe un empleado registrado con ese numeroEmpleado");
			}
			empleado.setNumeroEmpleado(cambios.getNumeroEmpleado());
		}
		if (cambios.getCargo() != null) {
			exigirTexto(cambios.getCargo(), "El cargo es obligatorio");
			empleado.setCargo(cambios.getCargo());
		}
		if (cambios.getArea() != null) {
			exigirTexto(cambios.getArea(), "El area es obligatoria");
			empleado.setArea(cambios.getArea());
		}
		if (cambios.getFechaIngreso() != null) {
			empleado.setFechaIngreso(cambios.getFechaIngreso());
		}
		if (cambios.getDepartamentoId() != null) {
			exigirTexto(cambios.getDepartamentoId(), "El departamentoId es obligatorio");
			if (!cambios.getDepartamentoId().equals(empleado.getDepartamentoId())) {
				EstadoEmpleado validado = validarDepartamentoConCircuitBreaker(cambios.getDepartamentoId());
				empleado.setDepartamentoId(cambios.getDepartamentoId());
				if (empleado.getEstado() != EstadoEmpleado.RETIRADO
						&& empleado.getEstado() != EstadoEmpleado.EN_VACACIONES) {
					empleado.setEstado(validado);
				}
			}
		}
	}

	private void exigirTexto(String valor, String mensaje) {
		if (valor.isBlank()) {
			throw new BadRequestException(mensaje);
		}
	}

	/**
	 * El commit ocurre al salir del método transaccional. El evento se publica
	 * después, para no anunciar una fila que todavía puede revertirse. Si no hay
	 * transacción activa (tests unitarios), publica en el momento.
	 */
	private void publicarDespuesDelCommit(Runnable publicacion) {
		if (TransactionSynchronizationManager.isSynchronizationActive()) {
			TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
				@Override
				public void afterCommit() {
					ejecutarPublicacion(publicacion);
				}
			});
			return;
		}
		ejecutarPublicacion(publicacion);
	}

	private void ejecutarPublicacion(Runnable publicacion) {
		try {
			publicacion.run();
		} catch (RuntimeException ex) {
			log.error("No se pudo publicar el evento de empleado; la fila ya quedó persistida: {}", ex.getMessage());
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
	 * Sin parámetros devuelve todos. Con {@code estado} filtra por ese estado.
	 * {@code desde} y {@code hasta} acotan {@code fechaRetiro} por fecha UTC,
	 * ambos extremos inclusive, y deben ir juntos.
	 */
	public List<Empleado> listar(EstadoEmpleado estado, LocalDate desde, LocalDate hasta) {
		if ((desde == null) != (hasta == null)) {
			throw new BadRequestException("Los parámetros desde y hasta deben enviarse juntos");
		}
		if (desde != null && hasta.isBefore(desde)) {
			throw new BadRequestException("El parámetro desde no puede ser posterior a hasta");
		}
		log.debug("Listando empleados estado={} desde={} hasta={}", estado, desde, hasta);
		List<Empleado> empleados = estado == null
				? empleadoRepository.findAll()
				: empleadoRepository.findByEstado(estado);
		if (desde == null) {
			return empleados;
		}
		return empleados.stream()
				.filter(empleado -> retiroEnRango(empleado, desde, hasta))
				.toList();
	}

	private boolean retiroEnRango(Empleado empleado, LocalDate desde, LocalDate hasta) {
		if (empleado.getFechaRetiro() == null) {
			return false;
		}
		LocalDate dia = empleado.getFechaRetiro().atZone(ZoneOffset.UTC).toLocalDate();
		return !dia.isBefore(desde) && !dia.isAfter(hasta);
	}
}

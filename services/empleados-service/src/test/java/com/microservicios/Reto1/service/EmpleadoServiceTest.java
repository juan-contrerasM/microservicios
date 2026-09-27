package com.microservicios.Reto1.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.microservicios.Reto1.client.DepartamentoClient;
import com.microservicios.Reto1.dto.ActualizarEmpleadoRequest;
import com.microservicios.Reto1.event.EmpleadoEventPublisher;
import com.microservicios.Reto1.exception.BadRequestException;
import com.microservicios.Reto1.exception.ConflictException;
import com.microservicios.Reto1.exception.EmpleadoNoEncontradoException;
import com.microservicios.Reto1.exception.ServiceUnavailableException;
import com.microservicios.Reto1.model.Empleado;
import com.microservicios.Reto1.model.EstadoEmpleado;
import com.microservicios.Reto1.repository.EmpleadoRepository;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreaker.State;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;

@ExtendWith(MockitoExtension.class)
class EmpleadoServiceTest {

	@Mock
	private EmpleadoRepository empleadoRepository;
	@Mock
	private DepartamentoClient departamentoClient;
	@Mock
	private EmpleadoEventPublisher eventPublisher;

	private CircuitBreaker circuitBreaker;
	private EmpleadoService empleadoService;
	private final Clock clock = Clock.fixed(Instant.parse("2026-09-26T14:05:00Z"), ZoneOffset.UTC);

	@BeforeEach
	void setUp() {
		CircuitBreakerConfig config = CircuitBreakerConfig.custom()
				.slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
				.slidingWindowSize(3)
				.minimumNumberOfCalls(3)
				.failureRateThreshold(100)
				.waitDurationInOpenState(Duration.ofSeconds(30))
				.permittedNumberOfCallsInHalfOpenState(1)
				.automaticTransitionFromOpenToHalfOpenEnabled(true)
				.recordExceptions(ServiceUnavailableException.class)
				.build();
		CircuitBreakerRegistry circuitBreakerRegistry = CircuitBreakerRegistry.of(config);
		circuitBreaker = circuitBreakerRegistry.circuitBreaker(EmpleadoService.CIRCUIT_BREAKER_NAME);
		empleadoService = new EmpleadoService(empleadoRepository, departamentoClient, circuitBreakerRegistry,
				eventPublisher, clock);
		org.mockito.Mockito.lenient().when(empleadoRepository.save(any(Empleado.class)))
				.thenAnswer(invocation -> invocation.getArgument(0));
	}

	@AfterEach
	void limpiarSincronizacion() {
		if (TransactionSynchronizationManager.isSynchronizationActive()) {
			TransactionSynchronizationManager.clearSynchronization();
		}
	}

	private Empleado nuevoEmpleado() {
		Empleado empleado = new Empleado();
		empleado.setId("E001");
		empleado.setNombre("Juan");
		empleado.setApellido("Pérez");
		empleado.setEmail("juan.perez@empresa.com");
		empleado.setNumeroEmpleado("EMP-2026-001");
		empleado.setCargo("Desarrollador Senior");
		empleado.setArea("Tecnología");
		empleado.setDepartamentoId("IT");
		empleado.setFechaIngreso(LocalDate.of(2026, 2, 10));
		return empleado;
	}

	@Test
	void registrarDejaElEmpleadoEnEstadoActivo() {
		Empleado empleado = nuevoEmpleado();
		when(empleadoRepository.existsById(empleado.getId())).thenReturn(false);
		when(empleadoRepository.existsByEmail(empleado.getEmail())).thenReturn(false);
		when(empleadoRepository.existsByNumeroEmpleado(empleado.getNumeroEmpleado())).thenReturn(false);
		when(empleadoRepository.save(any(Empleado.class))).thenAnswer(invocation -> invocation.getArgument(0));

		Empleado registrado = empleadoService.registrar(empleado);

		assertThat(registrado.getEstado()).isEqualTo(EstadoEmpleado.ACTIVO);
		verify(departamentoClient).validarExistencia("IT");
		verify(empleadoRepository).save(empleado);
	}

	@Test
	void registrarConIdExistenteLanzaConflictException() {
		Empleado empleado = nuevoEmpleado();
		when(empleadoRepository.existsById(empleado.getId())).thenReturn(true);

		assertThatThrownBy(() -> empleadoService.registrar(empleado))
				.isInstanceOf(ConflictException.class)
				.hasMessageContaining("id");
	}

	@Test
	void registrarConEmailExistenteLanzaConflictException() {
		Empleado empleado = nuevoEmpleado();
		when(empleadoRepository.existsById(empleado.getId())).thenReturn(false);
		when(empleadoRepository.existsByEmail(empleado.getEmail())).thenReturn(true);

		assertThatThrownBy(() -> empleadoService.registrar(empleado))
				.isInstanceOf(ConflictException.class)
				.hasMessageContaining("email");
	}

	@Test
	void registrarConNumeroEmpleadoExistenteLanzaConflictException() {
		Empleado empleado = nuevoEmpleado();
		when(empleadoRepository.existsById(empleado.getId())).thenReturn(false);
		when(empleadoRepository.existsByEmail(empleado.getEmail())).thenReturn(false);
		when(empleadoRepository.existsByNumeroEmpleado(empleado.getNumeroEmpleado())).thenReturn(true);

		assertThatThrownBy(() -> empleadoService.registrar(empleado))
				.isInstanceOf(ConflictException.class)
				.hasMessageContaining("numeroEmpleado");
	}

	@Test
	void registrarConDepartamentoInexistenteNoGuardaElEmpleado() {
		Empleado empleado = nuevoEmpleado();
		when(empleadoRepository.existsById(empleado.getId())).thenReturn(false);
		when(empleadoRepository.existsByEmail(empleado.getEmail())).thenReturn(false);
		when(empleadoRepository.existsByNumeroEmpleado(empleado.getNumeroEmpleado())).thenReturn(false);
		org.mockito.Mockito.doThrow(new BadRequestException("El departamento con id IT no existe"))
				.when(departamentoClient).validarExistencia("IT");

		assertThatThrownBy(() -> empleadoService.registrar(empleado))
				.isInstanceOf(BadRequestException.class)
				.hasMessageContaining("IT");
		verify(empleadoRepository, never()).save(any(Empleado.class));
		verify(eventPublisher, never()).publicarCreado(any());
		assertThat(circuitBreaker.getState()).isEqualTo(State.CLOSED);
	}

	@Test
	void restriccionUnicaDeBaseDeDatosSeTraduceAErrorDescriptivo() {
		Empleado empleado = nuevoEmpleado();
		when(empleadoRepository.existsById(empleado.getId())).thenReturn(false);
		when(empleadoRepository.existsByEmail(empleado.getEmail())).thenReturn(false);
		when(empleadoRepository.existsByNumeroEmpleado(empleado.getNumeroEmpleado())).thenReturn(false);
		when(empleadoRepository.save(empleado)).thenThrow(new DataIntegrityViolationException(
				"duplicate", new RuntimeException("constraint uk_empleados_email")));

		assertThatThrownBy(() -> empleadoService.registrar(empleado))
				.isInstanceOf(ConflictException.class)
				.hasMessage("Ya existe un empleado registrado con ese email");
	}

	@Test
	void consultarPorIdDevuelveElEmpleadoCuandoExiste() {
		Empleado empleado = nuevoEmpleado();
		when(empleadoRepository.findById("E001")).thenReturn(java.util.Optional.of(empleado));

		Empleado encontrado = empleadoService.consultarPorId("E001");

		assertThat(encontrado).isEqualTo(empleado);
	}

	@Test
	void consultarPorIdInexistenteLanzaEmpleadoNoEncontradoException() {
		when(empleadoRepository.findById("E999")).thenReturn(java.util.Optional.empty());

		assertThatThrownBy(() -> empleadoService.consultarPorId("E999"))
				.isInstanceOf(EmpleadoNoEncontradoException.class)
				.hasMessageContaining("E999");
	}

	@Test
	void listarTodosDevuelveLosEmpleadosDelRepositorio() {
		Empleado empleado = nuevoEmpleado();
		when(empleadoRepository.findAll()).thenReturn(java.util.List.of(empleado));

		java.util.List<Empleado> empleados = empleadoService.listar(null, null, null);

		assertThat(empleados).containsExactly(empleado);
	}

	@Test
	void listarTodosDevuelveListaVaciaSinEmpleados() {
		when(empleadoRepository.findAll()).thenReturn(java.util.List.of());

		assertThat(empleadoService.listar(null, null, null)).isEmpty();
	}

	@Test
	void registrarConCircuitoOpenNoLlamaARedYQuedaPendienteDeValidacion() {
		circuitBreaker.transitionToOpenState();
		Empleado empleado = nuevoEmpleado();

		Empleado registrado = empleadoService.registrar(empleado);

		assertThat(registrado.getEstado()).isEqualTo(EstadoEmpleado.PENDIENTE_VALIDACION);
		verify(departamentoClient, never()).validarExistencia(any());
		verify(empleadoRepository).save(empleado);
		verify(eventPublisher).publicarCreado(registrado);
	}

	@Test
	void tresFallosDeRedAbrenElCircuitoYElSiguienteRegistroNoTocaLaRed() {
		org.mockito.Mockito.doThrow(new ServiceUnavailableException("departamentos no disponible"))
				.when(departamentoClient).validarExistencia("IT");
		when(empleadoRepository.existsById(any())).thenReturn(false);
		when(empleadoRepository.existsByEmail(any())).thenReturn(false);
		when(empleadoRepository.existsByNumeroEmpleado(any())).thenReturn(false);

		for (int i = 0; i < 3; i++) {
			Empleado registrado = empleadoService.registrar(nuevoEmpleado());
			assertThat(registrado.getEstado()).isEqualTo(EstadoEmpleado.PENDIENTE_VALIDACION);
		}
		assertThat(circuitBreaker.getState()).isEqualTo(State.OPEN);
		verify(departamentoClient, times(3)).validarExistencia("IT");

		Empleado registradoConCircuitoAbierto = empleadoService.registrar(nuevoEmpleado());

		assertThat(registradoConCircuitoAbierto.getEstado()).isEqualTo(EstadoEmpleado.PENDIENTE_VALIDACION);
		verify(departamentoClient, times(3)).validarExistencia("IT");
	}

	@Test
	void enHalfOpenUnaLlamadaExitosaCierraElCircuitoYActivaAlEmpleado() {
		circuitBreaker.transitionToOpenState();
		circuitBreaker.transitionToHalfOpenState();
		Empleado empleado = nuevoEmpleado();

		Empleado registrado = empleadoService.registrar(empleado);

		assertThat(registrado.getEstado()).isEqualTo(EstadoEmpleado.ACTIVO);
		assertThat(circuitBreaker.getState()).isEqualTo(State.CLOSED);
		verify(departamentoClient).validarExistencia("IT");
	}

	@Test
	void enHalfOpenUnDepartamentoInexistenteCuentaComoRespuestaYTambienCierraElCircuito() {
		circuitBreaker.transitionToOpenState();
		circuitBreaker.transitionToHalfOpenState();
		Empleado empleado = nuevoEmpleado();
		org.mockito.Mockito.doThrow(new BadRequestException("El departamento con id IT no existe"))
				.when(departamentoClient).validarExistencia("IT");

		assertThatThrownBy(() -> empleadoService.registrar(empleado))
				.isInstanceOf(BadRequestException.class)
				.hasMessageContaining("IT");

		assertThat(circuitBreaker.getState()).isEqualTo(State.CLOSED);
		verify(empleadoRepository, never()).save(any(Empleado.class));
	}

	@Test
	void estadoCircuitBreakerReflejaElNombreYEstadoActual() {
		var estado = empleadoService.estadoCircuitBreaker();

		assertThat(estado.getName()).isEqualTo("departamentos");
		assertThat(estado.getState()).isEqualTo("CLOSED");
	}

	@Test
	void reconciliarPendientesActivaSoloLosQueYaTienenDepartamentoValido() {
		Empleado pendienteConDepartamentoValido = nuevoEmpleado();
		pendienteConDepartamentoValido.setId("E010");
		pendienteConDepartamentoValido.setEstado(EstadoEmpleado.PENDIENTE_VALIDACION);
		Empleado pendienteSinDepartamento = nuevoEmpleado();
		pendienteSinDepartamento.setId("E011");
		pendienteSinDepartamento.setDepartamentoId("NO-EXISTE");
		pendienteSinDepartamento.setEstado(EstadoEmpleado.PENDIENTE_VALIDACION);
		when(empleadoRepository.findByEstado(EstadoEmpleado.PENDIENTE_VALIDACION))
				.thenReturn(java.util.List.of(pendienteConDepartamentoValido, pendienteSinDepartamento));
		org.mockito.Mockito.doNothing().when(departamentoClient).validarExistencia("IT");
		org.mockito.Mockito.doThrow(new BadRequestException("El departamento con id NO-EXISTE no existe"))
				.when(departamentoClient).validarExistencia("NO-EXISTE");

		var resultado = empleadoService.reconciliarPendientes();

		assertThat(resultado.getPendientesEvaluados()).isEqualTo(2);
		assertThat(resultado.getReconciliados()).isEqualTo(1);
		assertThat(pendienteConDepartamentoValido.getEstado()).isEqualTo(EstadoEmpleado.ACTIVO);
		assertThat(pendienteSinDepartamento.getEstado()).isEqualTo(EstadoEmpleado.PENDIENTE_VALIDACION);
		verify(empleadoRepository).save(pendienteConDepartamentoValido);
		verify(empleadoRepository, never()).save(pendienteSinDepartamento);
	}

	@Test
	void reconciliarPendientesSinPendientesNoLlamaARed() {
		when(empleadoRepository.findByEstado(EstadoEmpleado.PENDIENTE_VALIDACION))
				.thenReturn(java.util.List.of());

		var resultado = empleadoService.reconciliarPendientes();

		assertThat(resultado.getPendientesEvaluados()).isZero();
		assertThat(resultado.getReconciliados()).isZero();
		verify(departamentoClient, never()).validarExistencia(any());
	}

	@Test
	void registrarPublicaCreadoDespuesDeGuardar() {
		Empleado empleado = prepararAlta();
		TransactionSynchronizationManager.initSynchronization();

		empleadoService.registrar(empleado);

		verify(eventPublisher, never()).publicarCreado(any());
		confirmarTransaccion();
		InOrder orden = org.mockito.Mockito.inOrder(empleadoRepository, eventPublisher);
		orden.verify(empleadoRepository).save(empleado);
		orden.verify(eventPublisher).publicarCreado(empleado);
	}

	@Test
	void falloDelPublicadorNoRompeElAltaYaGuardada() {
		Empleado empleado = prepararAlta();
		org.mockito.Mockito.doThrow(new RuntimeException("broker caído"))
				.when(eventPublisher).publicarCreado(any());
		TransactionSynchronizationManager.initSynchronization();

		Empleado registrado = empleadoService.registrar(empleado);
		confirmarTransaccion();

		assertThat(registrado.getId()).isEqualTo("E001");
		assertThat(registrado.getEstado()).isEqualTo(EstadoEmpleado.ACTIVO);
		verify(empleadoRepository).save(empleado);
	}

	@Test
	void actualizarPublicaActualizadoDespuesDeGuardar() {
		Empleado empleado = nuevoEmpleado();
		when(empleadoRepository.findById("E001")).thenReturn(java.util.Optional.of(empleado));
		ActualizarEmpleadoRequest cambios = new ActualizarEmpleadoRequest();
		cambios.setNombre("Juan Carlos");
		TransactionSynchronizationManager.initSynchronization();

		Empleado actualizado = empleadoService.actualizar("E001", cambios);
		verify(eventPublisher, never()).publicarActualizado(any());
		confirmarTransaccion();

		assertThat(actualizado.getNombre()).isEqualTo("Juan Carlos");
		assertThat(actualizado.getEmail()).isEqualTo("juan.perez@empresa.com");
		verify(eventPublisher).publicarActualizado(actualizado);
	}

	@Test
	void actualizarInexistenteNoPublica() {
		when(empleadoRepository.findById("E999")).thenReturn(java.util.Optional.empty());

		assertThatThrownBy(() -> empleadoService.actualizar("E999", new ActualizarEmpleadoRequest()))
				.isInstanceOf(EmpleadoNoEncontradoException.class)
				.hasMessage("El empleado con id E999 no existe");
		verify(eventPublisher, never()).publicarActualizado(any());
		verify(empleadoRepository, never()).save(any());
	}

	@Test
	void actualizarEmailDeOtroEmpleadoRespondeConflictoYNoPublica() {
		Empleado empleado = nuevoEmpleado();
		Empleado otro = nuevoEmpleado();
		otro.setId("E002");
		when(empleadoRepository.findById("E001")).thenReturn(java.util.Optional.of(empleado));
		when(empleadoRepository.findByEmail("otro@empresa.com")).thenReturn(java.util.Optional.of(otro));
		ActualizarEmpleadoRequest cambios = new ActualizarEmpleadoRequest();
		cambios.setEmail("otro@empresa.com");

		assertThatThrownBy(() -> empleadoService.actualizar("E001", cambios))
				.isInstanceOf(ConflictException.class)
				.hasMessage("Ya existe un empleado registrado con ese email");
		verify(eventPublisher, never()).publicarActualizado(any());
	}

	@Test
	void retirarPublicaRetiradoConFechaUtc() {
		Empleado empleado = nuevoEmpleado();
		when(empleadoRepository.findById("E001")).thenReturn(java.util.Optional.of(empleado));
		TransactionSynchronizationManager.initSynchronization();

		Empleado retirado = empleadoService.retirar("E001");
		verify(eventPublisher, never()).publicarRetirado(any());
		confirmarTransaccion();

		assertThat(retirado.getEstado()).isEqualTo(EstadoEmpleado.RETIRADO);
		assertThat(retirado.getFechaRetiro()).isEqualTo(Instant.parse("2026-09-26T14:05:00Z"));
		verify(eventPublisher).publicarRetirado(retirado);
	}

	@Test
	void segundoRetiroNoPublica() {
		Empleado empleado = nuevoEmpleado();
		empleado.setEstado(EstadoEmpleado.RETIRADO);
		empleado.setFechaRetiro(Instant.parse("2026-09-01T00:00:00Z"));
		when(empleadoRepository.findById("E001")).thenReturn(java.util.Optional.of(empleado));

		assertThatThrownBy(() -> empleadoService.retirar("E001"))
				.isInstanceOf(BadRequestException.class)
				.hasMessage("El empleado con id E001 ya está retirado");
		verify(eventPublisher, never()).publicarRetirado(any());
		verify(empleadoRepository, never()).save(any());
	}

	@Test
	void falloDelPublicadorNoRompeElRetiroYaGuardado() {
		Empleado empleado = nuevoEmpleado();
		when(empleadoRepository.findById("E001")).thenReturn(java.util.Optional.of(empleado));
		org.mockito.Mockito.doThrow(new RuntimeException("broker caído"))
				.when(eventPublisher).publicarRetirado(any());
		TransactionSynchronizationManager.initSynchronization();

		Empleado retirado = empleadoService.retirar("E001");
		confirmarTransaccion();

		assertThat(retirado.getEstado()).isEqualTo(EstadoEmpleado.RETIRADO);
	}

	@Test
	void listarRetiradosEnRangoComparaLaFechaUtcYNoLaHora() {
		Empleado dentro = nuevoEmpleado();
		dentro.setEstado(EstadoEmpleado.RETIRADO);
		dentro.setFechaRetiro(Instant.parse("2026-09-26T23:30:00Z"));
		Empleado fuera = nuevoEmpleado();
		fuera.setId("E002");
		fuera.setEstado(EstadoEmpleado.RETIRADO);
		fuera.setFechaRetiro(Instant.parse("2026-09-27T00:30:00Z"));
		when(empleadoRepository.findByEstado(EstadoEmpleado.RETIRADO))
				.thenReturn(java.util.List.of(dentro, fuera));

		java.util.List<Empleado> encontrados = empleadoService.listar(
				EstadoEmpleado.RETIRADO, LocalDate.of(2026, 9, 26), LocalDate.of(2026, 9, 26));

		assertThat(encontrados).containsExactly(dentro);
	}

	private Empleado prepararAlta() {
		Empleado empleado = nuevoEmpleado();
		when(empleadoRepository.existsById(empleado.getId())).thenReturn(false);
		when(empleadoRepository.existsByEmail(empleado.getEmail())).thenReturn(false);
		when(empleadoRepository.existsByNumeroEmpleado(empleado.getNumeroEmpleado())).thenReturn(false);
		return empleado;
	}

	private void confirmarTransaccion() {
		for (TransactionSynchronization sincronizacion : TransactionSynchronizationManager.getSynchronizations()) {
			sincronizacion.afterCommit();
		}
	}
}

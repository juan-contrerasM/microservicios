package com.microservicios.Reto1.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import com.microservicios.Reto1.client.DepartamentoClient;
import com.microservicios.Reto1.exception.BadRequestException;
import com.microservicios.Reto1.exception.ConflictException;
import com.microservicios.Reto1.exception.EmpleadoNoEncontradoException;
import com.microservicios.Reto1.model.Empleado;
import com.microservicios.Reto1.model.EstadoEmpleado;
import com.microservicios.Reto1.repository.EmpleadoRepository;

@ExtendWith(MockitoExtension.class)
class EmpleadoServiceTest {

	@Mock
	private EmpleadoRepository empleadoRepository;
	@Mock
	private DepartamentoClient departamentoClient;

	private EmpleadoService empleadoService;

	@BeforeEach
	void setUp() {
		empleadoService = new EmpleadoService(empleadoRepository, departamentoClient);
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
}

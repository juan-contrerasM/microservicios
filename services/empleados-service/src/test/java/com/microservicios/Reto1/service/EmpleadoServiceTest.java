package com.microservicios.Reto1.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.microservicios.Reto1.exception.ConflictException;
import com.microservicios.Reto1.exception.EmpleadoNoEncontradoException;
import com.microservicios.Reto1.model.Empleado;
import com.microservicios.Reto1.model.EstadoEmpleado;
import com.microservicios.Reto1.repository.EmpleadoRepository;

@ExtendWith(MockitoExtension.class)
class EmpleadoServiceTest {

	@Mock
	private EmpleadoRepository empleadoRepository;

	private EmpleadoService empleadoService;

	@BeforeEach
	void setUp() {
		empleadoService = new EmpleadoService(empleadoRepository);
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

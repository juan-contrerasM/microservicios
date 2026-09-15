package com.microservicios.Reto1.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.time.LocalDate;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import com.microservicios.Reto1.model.Empleado;
import com.microservicios.Reto1.service.EmpleadoService;

@ExtendWith(MockitoExtension.class)
class EmpleadoControllerTest {

	@Mock
	private EmpleadoService empleadoService;

	private EmpleadoController empleadoController;

	@BeforeEach
	void setUp() {
		empleadoController = new EmpleadoController(empleadoService);
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
	void registrarDevuelve200ConElEmpleadoCreado() {
		Empleado empleado = nuevoEmpleado();
		when(empleadoService.registrar(empleado)).thenReturn(empleado);

		ResponseEntity<Empleado> respuesta = empleadoController.registrar(empleado);

		assertThat(respuesta.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(respuesta.getBody()).isEqualTo(empleado);
	}

	@Test
	void consultarDevuelve200ConElEmpleadoEncontrado() {
		Empleado empleado = nuevoEmpleado();
		when(empleadoService.consultarPorId("E001")).thenReturn(empleado);

		ResponseEntity<Empleado> respuesta = empleadoController.consultar("E001");

		assertThat(respuesta.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(respuesta.getBody()).isEqualTo(empleado);
	}
}

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

import com.microservicios.Reto1.dto.CircuitBreakerStatus;
import com.microservicios.Reto1.dto.ReconciliacionResultado;
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
	void registrarDevuelve201ConElEmpleadoCreado() {
		Empleado empleado = nuevoEmpleado();
		when(empleadoService.registrar(empleado)).thenReturn(empleado);

		ResponseEntity<Empleado> respuesta = empleadoController.registrar(empleado);

		assertThat(respuesta.getStatusCode()).isEqualTo(HttpStatus.CREATED);
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

	@Test
	void listarDevuelve200ConTodosLosEmpleados() {
		Empleado empleado = nuevoEmpleado();
		when(empleadoService.listarTodos()).thenReturn(java.util.List.of(empleado));

		ResponseEntity<java.util.List<Empleado>> respuesta = empleadoController.listar();

		assertThat(respuesta.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(respuesta.getBody()).containsExactly(empleado);
	}

	@Test
	void estadoCircuitBreakerDevuelve200ConElEstadoDelServicio() {
		CircuitBreakerStatus estado = new CircuitBreakerStatus("departamentos", "CLOSED");
		when(empleadoService.estadoCircuitBreaker()).thenReturn(estado);

		ResponseEntity<CircuitBreakerStatus> respuesta = empleadoController.estadoCircuitBreaker();

		assertThat(respuesta.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(respuesta.getBody()).isEqualTo(estado);
	}

	@Test
	void reconciliarDevuelve200ConElResultadoDelBarrido() {
		ReconciliacionResultado resultado = new ReconciliacionResultado(2, 1);
		when(empleadoService.reconciliarPendientes()).thenReturn(resultado);

		ResponseEntity<ReconciliacionResultado> respuesta = empleadoController.reconciliar();

		assertThat(respuesta.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(respuesta.getBody()).isEqualTo(resultado);
	}
}

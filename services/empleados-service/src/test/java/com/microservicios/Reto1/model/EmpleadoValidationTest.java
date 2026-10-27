package com.microservicios.Reto1.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.Set;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class EmpleadoValidationTest {

	private static ValidatorFactory validatorFactory;
	private static Validator validator;

	@BeforeAll
	static void setUpValidator() {
		validatorFactory = Validation.buildDefaultValidatorFactory();
		validator = validatorFactory.getValidator();
	}

	@AfterAll
	static void cerrarValidator() {
		validatorFactory.close();
	}

	private Empleado empleadoValido() {
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
	void empleadoConTodosLosCamposEsValido() {
		Set<ConstraintViolation<Empleado>> violaciones = validator.validate(empleadoValido());

		assertThat(violaciones).isEmpty();
	}

	@Test
	void idEnBlancoGeneraViolacion() {
		Empleado empleado = empleadoValido();
		empleado.setId(" ");

		assertThat(mensajesDe(empleado)).contains("El id es obligatorio");
	}

	@Test
	void nombreEnBlancoGeneraViolacion() {
		Empleado empleado = empleadoValido();
		empleado.setNombre("");

		assertThat(mensajesDe(empleado)).contains("El nombre es obligatorio");
	}

	@Test
	void apellidoEnBlancoGeneraViolacion() {
		Empleado empleado = empleadoValido();
		empleado.setApellido(null);

		assertThat(mensajesDe(empleado)).contains("El apellido es obligatorio");
	}

	@Test
	void emailEnBlancoGeneraViolacion() {
		Empleado empleado = empleadoValido();
		empleado.setEmail("");

		assertThat(mensajesDe(empleado)).contains("El email es obligatorio");
	}

	@Test
	void emailConFormatoInvalidoGeneraViolacion() {
		Empleado empleado = empleadoValido();
		empleado.setEmail("no-es-un-email");

		assertThat(mensajesDe(empleado)).contains("El email no tiene un formato válido");
	}

	@Test
	void numeroEmpleadoEnBlancoGeneraViolacion() {
		Empleado empleado = empleadoValido();
		empleado.setNumeroEmpleado(null);

		assertThat(mensajesDe(empleado)).contains("El numeroEmpleado es obligatorio");
	}

	@Test
	void cargoEnBlancoGeneraViolacion() {
		Empleado empleado = empleadoValido();
		empleado.setCargo("");

		assertThat(mensajesDe(empleado)).contains("El cargo es obligatorio");
	}

	@Test
	void areaEnBlancoGeneraViolacion() {
		Empleado empleado = empleadoValido();
		empleado.setArea(null);

		assertThat(mensajesDe(empleado)).contains("El area es obligatoria");
	}

	@Test
	void departamentoIdEnBlancoGeneraViolacion() {
		Empleado empleado = empleadoValido();
		empleado.setDepartamentoId("");

		assertThat(mensajesDe(empleado)).contains("El departamentoId es obligatorio");
	}

	@Test
	void fechaIngresoNulaGeneraViolacion() {
		Empleado empleado = empleadoValido();
		empleado.setFechaIngreso(null);

		assertThat(mensajesDe(empleado)).contains("La fechaIngreso es obligatoria");
	}

	@Test
	void estadoPorDefectoEsActivo() {
		assertThat(new Empleado().getEstado()).isEqualTo(EstadoEmpleado.ACTIVO);
	}

	private Set<String> mensajesDe(Empleado empleado) {
		return validator.validate(empleado).stream()
				.map(ConstraintViolation::getMessage)
				.collect(java.util.stream.Collectors.toSet());
	}
}

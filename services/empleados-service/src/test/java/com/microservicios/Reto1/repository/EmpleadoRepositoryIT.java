package com.microservicios.Reto1.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.microservicios.Reto1.PostgresTestcontainersConfig;
import com.microservicios.Reto1.model.Empleado;
import com.microservicios.Reto1.model.EstadoEmpleado;

/**
 * Persistencia contra el Postgres real del esquema Liquibase. Cada prueba usa
 * ids únicos para no depender del orden ni de lo que dejó otra prueba.
 */
@SpringBootTest
@Import(PostgresTestcontainersConfig.class)
@Testcontainers(disabledWithoutDocker = true)
class EmpleadoRepositoryIT {

	@Autowired
	private EmpleadoRepository repository;

	@Test
	void emailDuplicadoLoRechazaLaRestriccionDelEsquema() {
		String sufijo = sufijo();
		repository.saveAndFlush(empleado("A" + sufijo, "dup" + sufijo + "@empresa.com", "N-A" + sufijo));

		Empleado otro = empleado("B" + sufijo, "dup" + sufijo + "@empresa.com", "N-B" + sufijo);

		// El nombre de la restricción es el que EmpleadoService.traducirViolacionDeUnicidad busca.
		assertThatThrownBy(() -> repository.saveAndFlush(otro))
				.isInstanceOf(DataIntegrityViolationException.class)
				.satisfies(ex -> assertThat(((DataIntegrityViolationException) ex).getMostSpecificCause().getMessage())
						.contains("uk_empleados_email"));
	}

	@Test
	void numeroEmpleadoDuplicadoLoRechazaLaRestriccionDelEsquema() {
		String sufijo = sufijo();
		repository.saveAndFlush(empleado("C" + sufijo, "c" + sufijo + "@empresa.com", "N-" + sufijo));

		Empleado otro = empleado("D" + sufijo, "d" + sufijo + "@empresa.com", "N-" + sufijo);

		assertThatThrownBy(() -> repository.saveAndFlush(otro))
				.isInstanceOf(DataIntegrityViolationException.class)
				.satisfies(ex -> assertThat(((DataIntegrityViolationException) ex).getMostSpecificCause().getMessage())
						.contains("uk_empleados_numero_empleado"));
	}

	@Test
	void unRetiradoSePersisteConFechaRetiroYApareceEnElFiltroPorEstado() {
		String sufijo = sufijo();
		Empleado activo = empleado("F" + sufijo, "f" + sufijo + "@empresa.com", "N-F" + sufijo);
		Empleado retirado = empleado("G" + sufijo, "g" + sufijo + "@empresa.com", "N-G" + sufijo);
		Instant fechaRetiro = Instant.parse("2026-09-26T14:05:00Z");
		retirado.setEstado(EstadoEmpleado.RETIRADO);
		retirado.setFechaRetiro(fechaRetiro);
		repository.saveAndFlush(activo);
		repository.saveAndFlush(retirado);

		assertThat(repository.findByEstado(EstadoEmpleado.RETIRADO))
				.extracting(Empleado::getId)
				.contains(retirado.getId())
				.doesNotContain(activo.getId());
		Empleado leido = repository.findById(retirado.getId()).orElseThrow();
		assertThat(leido.getFechaRetiro().truncatedTo(ChronoUnit.SECONDS)).isEqualTo(fechaRetiro);
	}

	@Test
	void existsByEmailYNumeroEmpleadoConsultanLaBase() {
		String sufijo = sufijo();
		repository.saveAndFlush(empleado("H" + sufijo, "h" + sufijo + "@empresa.com", "N-H" + sufijo));

		assertThat(repository.existsByEmail("h" + sufijo + "@empresa.com")).isTrue();
		assertThat(repository.existsByNumeroEmpleado("N-H" + sufijo)).isTrue();
		assertThat(repository.existsByEmail("nadie" + sufijo + "@empresa.com")).isFalse();
	}

	private static String sufijo() {
		return UUID.randomUUID().toString().substring(0, 8);
	}

	private static Empleado empleado(String id, String email, String numero) {
		Empleado empleado = new Empleado();
		empleado.setId(id);
		empleado.setNombre("Prueba");
		empleado.setApellido("Integración");
		empleado.setEmail(email);
		empleado.setNumeroEmpleado(numero);
		empleado.setCargo("QA");
		empleado.setArea("Tecnología");
		empleado.setDepartamentoId("IT");
		empleado.setFechaIngreso(LocalDate.of(2026, 2, 10));
		return empleado;
	}
}

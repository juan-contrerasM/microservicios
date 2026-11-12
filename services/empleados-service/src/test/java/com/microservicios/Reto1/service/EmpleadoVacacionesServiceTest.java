package com.microservicios.Reto1.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.microservicios.Reto1.model.Empleado;
import com.microservicios.Reto1.model.EstadoEmpleado;
import com.microservicios.Reto1.model.EventoProcesado;
import com.microservicios.Reto1.repository.EmpleadoRepository;
import com.microservicios.Reto1.repository.EventoProcesadoRepository;

@ExtendWith(MockitoExtension.class)
class EmpleadoVacacionesServiceTest {

	@Mock
	private EmpleadoRepository empleados;

	@Mock
	private EventoProcesadoRepository eventos;

	private EmpleadoVacacionesService servicio;

	@BeforeEach
	void setUp() {
		servicio = new EmpleadoVacacionesService(empleados, eventos,
				Clock.fixed(Instant.parse("2027-03-15T08:00:00Z"), ZoneOffset.UTC));
	}

	private Empleado empleado(EstadoEmpleado estado) {
		Empleado e = new Empleado();
		e.setId("E001");
		e.setEstado(estado);
		return e;
	}

	@Test
	void vacacionesIniciadasPasaUnActivoAEnVacaciones() {
		Empleado e = empleado(EstadoEmpleado.ACTIVO);
		when(empleados.findById("E001")).thenReturn(Optional.of(e));

		assertThat(servicio.procesar("evt-1", "vacaciones.iniciadas", Map.of("empleadoId", "E001"))).isTrue();

		assertThat(e.getEstado()).isEqualTo(EstadoEmpleado.EN_VACACIONES);
		verify(empleados).save(e);
		verify(eventos).save(any(EventoProcesado.class));
	}

	@Test
	void vacacionesFinalizadasDevuelveAActivo() {
		Empleado e = empleado(EstadoEmpleado.EN_VACACIONES);
		when(empleados.findById("E001")).thenReturn(Optional.of(e));

		servicio.procesar("evt-2", "vacaciones.finalizadas", Map.of("empleadoId", "E001"));

		assertThat(e.getEstado()).isEqualTo(EstadoEmpleado.ACTIVO);
	}

	@Test
	void unRetiradoNoVuelveAActivoNiEntraEnVacaciones() {
		Empleado e = empleado(EstadoEmpleado.RETIRADO);
		when(empleados.findById("E001")).thenReturn(Optional.of(e));

		servicio.procesar("evt-3", "vacaciones.finalizadas", Map.of("empleadoId", "E001"));
		servicio.procesar("evt-4", "vacaciones.iniciadas", Map.of("empleadoId", "E001"));

		assertThat(e.getEstado()).isEqualTo(EstadoEmpleado.RETIRADO);
		verify(empleados, never()).save(any());
	}

	@Test
	void unEventoRepetidoNoTieneEfecto() {
		when(eventos.existsById("evt-1")).thenReturn(true);

		assertThat(servicio.procesar("evt-1", "vacaciones.iniciadas", Map.of("empleadoId", "E001"))).isFalse();

		verify(empleados, never()).findById(any());
		verify(eventos, never()).save(any());
	}

	@Test
	void sinIdOEmpleadoSeConfirmaSinEfecto() {
		assertThat(servicio.procesar(" ", "vacaciones.iniciadas", Map.of())).isFalse();
		when(empleados.findById("E404")).thenReturn(Optional.empty());

		assertThat(servicio.procesar("evt-5", "vacaciones.iniciadas", Map.of("empleadoId", "E404"))).isTrue();
		assertThat(servicio.procesar("evt-6", "vacaciones.iniciadas", Map.of())).isTrue();
		verify(empleados, never()).save(any());
	}
}

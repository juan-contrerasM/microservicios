package com.microservicios.perfiles.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.microservicios.perfiles.model.EventoProcesado;
import com.microservicios.perfiles.model.Perfil;
import com.microservicios.perfiles.repository.EventoProcesadoRepository;
import com.microservicios.perfiles.repository.PerfilRepository;

@ExtendWith(MockitoExtension.class)
class PerfilEventoServiceTest {

	@Mock
	private PerfilRepository perfiles;

	@Mock
	private EventoProcesadoRepository eventos;

	private PerfilEventoService service;

	@BeforeEach
	void setUp() {
		Clock clock = Clock.fixed(Instant.parse("2026-09-26T14:00:01Z"), ZoneOffset.UTC);
		service = new PerfilEventoService(perfiles, eventos, clock);
	}

	@Test
	void creadoInsertaUnPerfilPorDefecto() {
		when(eventos.existsById("evt-1")).thenReturn(false);
		when(perfiles.findByEmpleadoId("E001")).thenReturn(Optional.empty());

		service.procesar("evt-1", "empleado.creado", alta());

		ArgumentCaptor<Perfil> guardado = ArgumentCaptor.forClass(Perfil.class);
		verify(perfiles).save(guardado.capture());
		Perfil perfil = guardado.getValue();
		assertThat(perfil.getEmpleadoId()).isEqualTo("E001");
		assertThat(perfil.getNombre()).isEqualTo("Juan");
		assertThat(perfil.getEmail()).isEqualTo("juan.perez@empresa.com");
		assertThat(perfil.getTelefono()).isEmpty();
		assertThat(perfil.getDireccion()).isEmpty();
		assertThat(perfil.getCiudad()).isEmpty();
		assertThat(perfil.getBiografia()).isEmpty();
		assertThat(perfil.isArchivado()).isFalse();
		assertThat(perfil.getFechaCreacion()).isEqualTo(Instant.parse("2026-09-26T14:00:01Z"));
		verify(eventos).save(any(EventoProcesado.class));
	}

	@Test
	void elMismoIdDeEventoNoInsertaDosPerfiles() {
		when(eventos.existsById("evt-1")).thenReturn(true);

		service.procesar("evt-1", "empleado.creado", alta());

		verify(perfiles, never()).save(any());
		verify(eventos, never()).save(any());
	}

	@Test
	void actualizadoCopiaNombreYEmailYNoPisaElTelefono() {
		Perfil perfil = perfilConTelefono();
		when(eventos.existsById("evt-2")).thenReturn(false);
		when(perfiles.findByEmpleadoId("E001")).thenReturn(Optional.of(perfil));

		Map<String, Object> data = new HashMap<>();
		data.put("empleadoId", "E001");
		data.put("nombre", "Juan Carlos");
		data.put("email", "juan.perez@empresa.com");
		data.put("cargo", "Tech Lead");

		service.procesar("evt-2", "empleado.actualizado", data);

		assertThat(perfil.getNombre()).isEqualTo("Juan Carlos");
		assertThat(perfil.getEmail()).isEqualTo("juan.perez@empresa.com");
		assertThat(perfil.getTelefono()).isEqualTo("3001234567");
		assertThat(perfil.isArchivado()).isFalse();
		verify(perfiles, never()).save(any());
	}

	@Test
	void retiradoArchivaSinBorrar() {
		Perfil perfil = perfilConTelefono();
		when(eventos.existsById("evt-3")).thenReturn(false);
		when(perfiles.findByEmpleadoId("E001")).thenReturn(Optional.of(perfil));

		service.procesar("evt-3", "empleado.retirado", Map.of("empleadoId", "E001", "motivo", "RENUNCIA"));

		assertThat(perfil.isArchivado()).isTrue();
		assertThat(perfil.getTelefono()).isEqualTo("3001234567");
		verify(perfiles, never()).delete(any());
	}

	private static Map<String, Object> alta() {
		Map<String, Object> data = new HashMap<>();
		data.put("empleadoId", "E001");
		data.put("nombre", "Juan");
		data.put("apellido", "Pérez");
		data.put("email", "juan.perez@empresa.com");
		return data;
	}

	private static Perfil perfilConTelefono() {
		Perfil perfil = new Perfil();
		perfil.setId("perfil-1");
		perfil.setEmpleadoId("E001");
		perfil.setNombre("Juan");
		perfil.setEmail("juan.perez@empresa.com");
		perfil.setTelefono("3001234567");
		perfil.setDireccion("");
		perfil.setCiudad("Armenia");
		perfil.setBiografia("Ingeniero de sistemas");
		perfil.setArchivado(false);
		perfil.setFechaCreacion(Instant.parse("2026-09-26T14:00:01Z"));
		return perfil;
	}
}

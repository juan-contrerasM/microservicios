package com.microservicios.perfiles.service;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.microservicios.perfiles.model.EventoProcesado;
import com.microservicios.perfiles.model.Perfil;
import com.microservicios.perfiles.repository.EventoProcesadoRepository;
import com.microservicios.perfiles.repository.PerfilRepository;

@Service
public class PerfilEventoService {

	private static final Logger log = LoggerFactory.getLogger(PerfilEventoService.class);

	private final PerfilRepository perfiles;
	private final EventoProcesadoRepository eventos;
	private final Clock clock;

	public PerfilEventoService(PerfilRepository perfiles, EventoProcesadoRepository eventos, Clock clock) {
		this.perfiles = perfiles;
		this.eventos = eventos;
		this.clock = clock;
	}

	@Transactional
	public void procesar(String eventId, String type, Map<String, Object> data) {
		if (eventId == null || eventId.isBlank()) {
			log.error("Evento sin id; se confirma sin efecto");
			return;
		}
		if (eventos.existsById(eventId)) {
			return;
		}
		Map<String, Object> carga = data == null ? Map.of() : data;
		switch (type == null ? "" : type) {
			case "empleado.creado" -> crear(carga);
			case "empleado.actualizado" -> sincronizar(carga);
			case "empleado.retirado" -> archivar(carga);
			default -> log.info("Evento {} ignorado por perfiles", type);
		}
		eventos.save(new EventoProcesado(eventId, Instant.now(clock)));
	}

	private void crear(Map<String, Object> data) {
		String empleadoId = texto(data.get("empleadoId"));
		String email = texto(data.get("email"));
		if (empleadoId == null || email == null) {
			log.error("empleado.creado incompleto; se confirma sin perfil");
			return;
		}
		if (perfiles.findByEmpleadoId(empleadoId).isPresent()) {
			return;
		}
		Perfil perfil = new Perfil();
		perfil.setId(UUID.randomUUID().toString());
		perfil.setEmpleadoId(empleadoId);
		perfil.setNombre(textoOVacio(data.get("nombre")));
		perfil.setEmail(email);
		perfil.setTelefono("");
		perfil.setDireccion("");
		perfil.setCiudad("");
		perfil.setBiografia("");
		perfil.setFechaCreacion(Instant.now(clock));
		perfil.setArchivado(false);
		perfiles.save(perfil);
	}

	private void sincronizar(Map<String, Object> data) {
		String empleadoId = texto(data.get("empleadoId"));
		if (empleadoId == null) {
			log.error("empleado.actualizado sin empleadoId; se confirma sin efecto");
			return;
		}
		perfiles.findByEmpleadoId(empleadoId).ifPresentOrElse(perfil -> {
			if (data.containsKey("nombre") && texto(data.get("nombre")) != null) {
				perfil.setNombre(texto(data.get("nombre")));
			}
			if (data.containsKey("email") && texto(data.get("email")) != null) {
				perfil.setEmail(texto(data.get("email")));
			}
		}, () -> log.error("empleado.actualizado sin perfil para {}; se confirma sin fila", empleadoId));
	}

	private void archivar(Map<String, Object> data) {
		String empleadoId = texto(data.get("empleadoId"));
		if (empleadoId == null) {
			log.error("empleado.retirado sin empleadoId; se confirma sin efecto");
			return;
		}
		perfiles.findByEmpleadoId(empleadoId).ifPresentOrElse(
				perfil -> perfil.setArchivado(true),
				() -> log.error("empleado.retirado sin perfil para {}; se confirma sin fila", empleadoId));
	}

	private static String texto(Object valor) {
		if (valor == null) {
			return null;
		}
		String texto = valor.toString().trim();
		return texto.isEmpty() ? null : texto;
	}

	private static String textoOVacio(Object valor) {
		String texto = texto(valor);
		return texto == null ? "" : texto;
	}
}

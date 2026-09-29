package com.microservicios.perfiles.service;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.microservicios.perfiles.dto.ActualizarPerfilRequest;
import com.microservicios.perfiles.exception.PerfilNoEncontradoException;
import com.microservicios.perfiles.model.Perfil;
import com.microservicios.perfiles.repository.PerfilRepository;

@Service
public class PerfilConsultaService {

	private final PerfilRepository perfiles;

	public PerfilConsultaService(PerfilRepository perfiles) {
		this.perfiles = perfiles;
	}

	@Transactional(readOnly = true)
	public List<Perfil> listar() {
		return perfiles.findAll();
	}

	@Transactional(readOnly = true)
	public Perfil obtener(String empleadoId) {
		return perfiles.findByEmpleadoId(empleadoId)
				.orElseThrow(() -> new PerfilNoEncontradoException(empleadoId));
	}

	@Transactional
	public Perfil actualizar(String empleadoId, ActualizarPerfilRequest cambios) {
		ActualizarPerfilRequest recibidos = cambios == null
				? new ActualizarPerfilRequest(null, null, null, null)
				: cambios;
		Perfil perfil = obtener(empleadoId);
		if (recibidos.telefono() != null) {
			perfil.setTelefono(recibidos.telefono());
		}
		if (recibidos.direccion() != null) {
			perfil.setDireccion(recibidos.direccion());
		}
		if (recibidos.ciudad() != null) {
			perfil.setCiudad(recibidos.ciudad());
		}
		if (recibidos.biografia() != null) {
			perfil.setBiografia(recibidos.biografia());
		}
		return perfil;
	}
}

package com.microservicios.perfiles.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.microservicios.perfiles.model.Perfil;

public interface PerfilRepository extends JpaRepository<Perfil, String> {

	Optional<Perfil> findByEmpleadoId(String empleadoId);
}

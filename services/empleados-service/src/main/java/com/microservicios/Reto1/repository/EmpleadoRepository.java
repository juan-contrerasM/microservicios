package com.microservicios.Reto1.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.microservicios.Reto1.model.Empleado;

public interface EmpleadoRepository extends JpaRepository<Empleado, String> {

	boolean existsByEmail(String email);

	boolean existsByNumeroEmpleado(String numeroEmpleado);

	Optional<Empleado> findByEmail(String email);

	Optional<Empleado> findByNumeroEmpleado(String numeroEmpleado);
}

package com.microservicios.perfiles.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.microservicios.perfiles.model.EventoProcesado;

public interface EventoProcesadoRepository extends JpaRepository<EventoProcesado, String> {
}

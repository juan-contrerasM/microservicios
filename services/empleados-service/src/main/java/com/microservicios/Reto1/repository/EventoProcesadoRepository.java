package com.microservicios.Reto1.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.microservicios.Reto1.model.EventoProcesado;

public interface EventoProcesadoRepository extends JpaRepository<EventoProcesado, String> {
}

package com.microservicios.perfiles.model;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "eventos_procesados")
public class EventoProcesado {

	@Id
	private String id;

	@Column(name = "procesado_en", nullable = false)
	private Instant procesadoEn;

	public EventoProcesado() {
	}

	public EventoProcesado(String id, Instant procesadoEn) {
		this.id = id;
		this.procesadoEn = procesadoEn;
	}

	public String getId() {
		return id;
	}

	public Instant getProcesadoEn() {
		return procesadoEn;
	}
}

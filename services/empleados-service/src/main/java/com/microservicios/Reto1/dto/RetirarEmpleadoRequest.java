package com.microservicios.Reto1.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Cuerpo opcional del DELETE. Si no viene, el evento usa el motivo por defecto del catálogo.
 */
@Schema(description = "Motivo opcional del retiro. Si se omite, el evento publica RENUNCIA.")
public class RetirarEmpleadoRequest {

	@Schema(example = "RENUNCIA")
	private String motivo;

	public String getMotivo() {
		return motivo;
	}

	public void setMotivo(String motivo) {
		this.motivo = motivo;
	}
}

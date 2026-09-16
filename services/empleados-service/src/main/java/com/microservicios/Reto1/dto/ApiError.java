package com.microservicios.Reto1.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Error de la API con un mensaje descriptivo")
public class ApiError {

	@Schema(description = "Descripción del error", example = "Ya existe un empleado registrado con ese email")
	private final String mensaje;

	public ApiError(String mensaje) {
		this.mensaje = mensaje;
	}

	public String getMensaje() {
		return mensaje;
	}
}

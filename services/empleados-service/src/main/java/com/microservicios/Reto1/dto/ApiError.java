package com.microservicios.Reto1.dto;

public class ApiError {

	private final String mensaje;

	public ApiError(String mensaje) {
		this.mensaje = mensaje;
	}

	public String getMensaje() {
		return mensaje;
	}
}

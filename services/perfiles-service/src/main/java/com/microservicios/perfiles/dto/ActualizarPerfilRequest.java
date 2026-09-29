package com.microservicios.perfiles.dto;

public record ActualizarPerfilRequest(
		String telefono,
		String direccion,
		String ciudad,
		String biografia) {
}

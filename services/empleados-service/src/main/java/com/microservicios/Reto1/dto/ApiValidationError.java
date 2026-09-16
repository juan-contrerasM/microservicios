package com.microservicios.Reto1.dto;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Respuesta para solicitudes con uno o más campos inválidos.
 */
@Schema(description = "Error de validación: mensaje general más el detalle por campo")
public class ApiValidationError extends ApiError {

	@Schema(description = "Mapa campo → motivo", example = "{\"nombre\":\"El nombre es obligatorio\"}")
	private final Map<String, String> errores;

	public ApiValidationError(String mensaje, Map<String, String> errores) {
		super(mensaje);
		this.errores = Collections.unmodifiableMap(new LinkedHashMap<>(errores));
	}

	public Map<String, String> getErrores() {
		return errores;
	}
}

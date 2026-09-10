package com.microservicios.Reto1.dto;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Respuesta para solicitudes con uno o más campos inválidos.
 */
public class ApiValidationError extends ApiError {

	private final Map<String, String> errores;

	public ApiValidationError(String mensaje, Map<String, String> errores) {
		super(mensaje);
		this.errores = Collections.unmodifiableMap(new LinkedHashMap<>(errores));
	}

	public Map<String, String> getErrores() {
		return errores;
	}
}

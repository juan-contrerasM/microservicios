package com.microservicios.Reto1.exception;

/**
 * Conflicto de unicidad: id, email o numeroEmpleado ya registrados. Responde 409
 * según el contrato C3.
 */
public class ConflictException extends RuntimeException {

	public ConflictException(String message) {
		super(message);
	}
}

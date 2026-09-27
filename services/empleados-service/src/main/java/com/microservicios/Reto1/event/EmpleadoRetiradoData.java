package com.microservicios.Reto1.event;

import java.time.Instant;

/**
 * Carga de {@code empleado.retirado}.
 */
public record EmpleadoRetiradoData(
		String empleadoId,
		String nombre,
		String apellido,
		String email,
		Instant fechaRetiro,
		String estado) {
}

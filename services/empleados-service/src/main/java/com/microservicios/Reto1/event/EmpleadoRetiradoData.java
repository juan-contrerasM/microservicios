package com.microservicios.Reto1.event;

import java.time.Instant;

/**
 * Carga de {@code empleado.retirado} según el catálogo oficial.
 */
public record EmpleadoRetiradoData(
		String empleadoId,
		String email,
		Instant fechaRetiro,
		String motivo) {
}

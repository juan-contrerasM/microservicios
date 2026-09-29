package com.microservicios.Reto1.event;

/**
 * Carga de {@code empleado.actualizado} según el catálogo oficial.
 * No viajan numeroEmpleado, fechaIngreso ni estado: perfiles solo replica nombre y email.
 */
public record EmpleadoActualizadoData(
		String empleadoId,
		String nombre,
		String apellido,
		String email,
		String cargo,
		String area,
		String departamentoId) {
}

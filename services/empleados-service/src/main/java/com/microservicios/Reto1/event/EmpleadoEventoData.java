package com.microservicios.Reto1.event;

import java.time.LocalDate;

/**
 * Carga de {@code empleado.creado} y {@code empleado.actualizado}.
 * En el actualizado van los campos ya persistidos, no el diff.
 */
public record EmpleadoEventoData(
		String empleadoId,
		String nombre,
		String apellido,
		String email,
		String numeroEmpleado,
		String cargo,
		String area,
		String departamentoId,
		LocalDate fechaIngreso,
		String estado) {
}

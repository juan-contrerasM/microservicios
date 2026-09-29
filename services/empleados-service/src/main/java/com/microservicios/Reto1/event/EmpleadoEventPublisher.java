package com.microservicios.Reto1.event;

import com.microservicios.Reto1.model.Empleado;

/**
 * Publica los hechos de empleados después de que la fila ya quedó confirmada.
 * Una falla del broker no debe propagarse a quien llamó al API.
 */
public interface EmpleadoEventPublisher {

	void publicarCreado(Empleado empleado);

	void publicarActualizado(Empleado empleado);

	void publicarRetirado(Empleado empleado, String motivo);
}

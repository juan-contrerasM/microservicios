package com.microservicios.Reto1.model;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Estado del empleado. ACTIVO cuando departamentos valida el departamento; "
		+ "PENDIENTE_VALIDACION cuando el Circuit Breaker está OPEN o la llamada falló (Reto 3).")
public enum EstadoEmpleado {
	ACTIVO,
	PENDIENTE_VALIDACION,
	EN_VACACIONES,
	RETIRADO
}

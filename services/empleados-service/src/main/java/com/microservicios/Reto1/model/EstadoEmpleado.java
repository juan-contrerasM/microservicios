package com.microservicios.Reto1.model;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Estado del empleado. En este reto el registro siempre queda ACTIVO.")
public enum EstadoEmpleado {
	ACTIVO,
	EN_VACACIONES,
	RETIRADO
}

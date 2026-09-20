package com.microservicios.Reto1.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Resultado de un barrido de reconciliación de empleados PENDIENTE_VALIDACION")
public class ReconciliacionResultado {

	@Schema(description = "Empleados en PENDIENTE_VALIDACION antes del barrido", example = "3")
	private final int pendientesEvaluados;

	@Schema(description = "Empleados que pasaron a ACTIVO tras revalidar el departamento", example = "2")
	private final int reconciliados;

	public ReconciliacionResultado(int pendientesEvaluados, int reconciliados) {
		this.pendientesEvaluados = pendientesEvaluados;
		this.reconciliados = reconciliados;
	}

	public int getPendientesEvaluados() {
		return pendientesEvaluados;
	}

	public int getReconciliados() {
		return reconciliados;
	}
}

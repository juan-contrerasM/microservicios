package com.microservicios.Reto1.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Estado observable del Circuit Breaker que protege la llamada a departamentos")
public class CircuitBreakerStatus {

	@Schema(description = "Nombre de la instancia del circuito", example = "departamentos")
	private final String name;

	@Schema(description = "Estado del circuito", example = "CLOSED")
	private final String state;

	public CircuitBreakerStatus(String name, String state) {
		this.name = name;
		this.state = state;
	}

	public String getName() {
		return name;
	}

	public String getState() {
		return state;
	}
}

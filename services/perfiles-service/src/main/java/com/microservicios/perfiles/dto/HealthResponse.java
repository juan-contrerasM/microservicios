package com.microservicios.perfiles.dto;

import java.util.Map;

public record HealthResponse(String status, String service, Map<String, String> checks) {
	public HealthResponse(String status) {
		this(status, "perfiles-service", Map.of("database", status));
	}
}

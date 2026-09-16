package com.microservicios.Reto1.controller;

import java.sql.Connection;
import java.sql.SQLException;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.microservicios.Reto1.dto.HealthResponse;

/**
 * Health check real: solo reporta UP cuando PostgreSQL acepta conexiones.
 */
@RestController
public class HealthController {

	private static final Logger log = LoggerFactory.getLogger(HealthController.class);
	private static final int DATABASE_VALIDATION_TIMEOUT_SECONDS = 2;

	private final DataSource dataSource;

	public HealthController(DataSource dataSource) {
		this.dataSource = dataSource;
	}

	@GetMapping("/health")
	public ResponseEntity<HealthResponse> health() {
		try (Connection connection = dataSource.getConnection()) {
			if (connection.isValid(DATABASE_VALIDATION_TIMEOUT_SECONDS)) {
				return ResponseEntity.ok(new HealthResponse("UP"));
			}
		} catch (SQLException ex) {
			log.warn("Health check de PostgreSQL falló: {}", ex.getMessage());
		}

		return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
				.body(new HealthResponse("DOWN"));
	}
}

package com.microservicios.perfiles.controller;

import java.sql.Connection;
import java.sql.SQLException;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.microservicios.perfiles.dto.HealthResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@Tag(name = "Salud", description = "Health check contra PostgreSQL")
public class HealthController {

	private static final Logger log = LoggerFactory.getLogger(HealthController.class);

	private final DataSource dataSource;

	public HealthController(DataSource dataSource) {
		this.dataSource = dataSource;
	}

	@GetMapping("/health")
	@Operation(summary = "Health check. 200 solo si PostgreSQL responde.")
	public ResponseEntity<HealthResponse> health() {
		try (Connection connection = dataSource.getConnection()) {
			if (connection.isValid(2)) {
				return ResponseEntity.ok(new HealthResponse("UP"));
			}
		} catch (SQLException ex) {
			log.warn("Health check de PostgreSQL falló: {}", ex.getMessage());
		}
		return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(new HealthResponse("DOWN"));
	}
}

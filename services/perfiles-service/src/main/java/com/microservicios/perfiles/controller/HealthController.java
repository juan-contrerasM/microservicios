package com.microservicios.perfiles.controller;

import java.sql.Connection;
import java.sql.SQLException;

import javax.sql.DataSource;

import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
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
	private final CachingConnectionFactory brokerConnectionFactory;

	public HealthController(DataSource dataSource) {
		this(dataSource, null);
	}

	@Autowired
	public HealthController(DataSource dataSource,
			@Qualifier("brokerConnectionFactory") CachingConnectionFactory brokerConnectionFactory) {
		this.dataSource = dataSource;
		this.brokerConnectionFactory = brokerConnectionFactory;
	}

	@GetMapping("/health")
	@Operation(summary = "Health check. 200 solo si PostgreSQL responde.")
	public ResponseEntity<HealthResponse> health() {
		boolean databaseUp = false;
		try (Connection connection = dataSource.getConnection()) {
			if (connection.isValid(2)) {
				databaseUp = true;
			}
		} catch (SQLException ex) {
			log.warn("Health check de PostgreSQL falló: {}", ex.getMessage());
		}
		boolean brokerUp = brokerConnectionFactory == null || brokerDisponible();
		String status = databaseUp && brokerUp ? "UP" : "DOWN";
		HealthResponse response = new HealthResponse(status, "perfiles-service", java.util.Map.of(
				"database", databaseUp ? "UP" : "DOWN", "messageBroker", brokerUp ? "UP" : "DOWN"));
		return databaseUp && brokerUp ? ResponseEntity.ok(response)
				: ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(response);
	}

	private boolean brokerDisponible() {
		try (org.springframework.amqp.rabbit.connection.Connection connection = brokerConnectionFactory.createConnection()) {
			return connection.isOpen();
		} catch (RuntimeException ex) {
			log.warn("Health check de RabbitMQ falló: {}", ex.getMessage());
			return false;
		}
	}
}

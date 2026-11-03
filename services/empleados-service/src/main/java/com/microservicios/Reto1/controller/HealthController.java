package com.microservicios.Reto1.controller;

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

import com.microservicios.Reto1.dto.HealthResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * Health check real: solo reporta UP cuando PostgreSQL acepta conexiones.
 */
@RestController
@Tag(name = "Salud", description = "Health check real contra PostgreSQL")
public class HealthController {

	private static final Logger log = LoggerFactory.getLogger(HealthController.class);
	private static final int DATABASE_VALIDATION_TIMEOUT_SECONDS = 2;
	private static final String JSON = "application/json";

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
	@Operation(
			summary = "Health check",
			description = "Verifica la conexión a PostgreSQL. Docker Compose usa este endpoint "
					+ "como healthcheck del contenedor.")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "El servicio y PostgreSQL están disponibles",
					content = @Content(mediaType = JSON, schema = @Schema(implementation = HealthResponse.class),
							examples = @ExampleObject(value = "{\"status\":\"UP\"}"))),
			@ApiResponse(responseCode = "503", description = "No hay conexión a PostgreSQL",
					content = @Content(mediaType = JSON, schema = @Schema(implementation = HealthResponse.class),
							examples = @ExampleObject(value = "{\"status\":\"DOWN\"}")))
	})
	public ResponseEntity<HealthResponse> health() {
		boolean databaseUp = false;
		try (Connection connection = dataSource.getConnection()) {
			if (connection.isValid(DATABASE_VALIDATION_TIMEOUT_SECONDS)) {
				databaseUp = true;
			}
		} catch (SQLException ex) {
			log.warn("Health check de PostgreSQL falló: {}", ex.getMessage());
		}

		boolean brokerUp = brokerConnectionFactory == null || brokerDisponible();
		String status = databaseUp && brokerUp ? "UP" : "DOWN";
		HealthResponse response = new HealthResponse(status, "empleados-service", java.util.Map.of(
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

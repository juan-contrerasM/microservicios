package com.microservicios.perfiles.controller;

import java.sql.Connection;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.microservicios.perfiles.dto.HealthResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.PreDestroy;

/**
 * Health check real (contrato C2): PostgreSQL y RabbitMQ, con un tiempo máximo
 * por check.
 *
 * <p>Mismo riesgo que el hallazgo de la Fase 1 en empleados-service: con PostgreSQL
 * detenido {@code dataSource.getConnection()} se queda esperando a Hikari (hasta
 * {@code connection-timeout}, 30 s por defecto) y la conexión a RabbitMQ puede
 * tardar otro tanto. Por eso cada check corre en un hilo propio y el
 * controlador espera como máximo {@code health.check-timeout-ms} (2 s) en total:
 * lo que no conteste a tiempo cuenta como DOWN y el endpoint responde 503 en
 * menos de 3 s aunque la dependencia cuelgue. El pool de hilos es acotado; si
 * hay demasiados checks colgados, el nuevo se rechaza y cuenta como DOWN en vez
 * de acumular hilos bloqueados.
 */
@RestController
@Tag(name = "Salud", description = "Health check real contra PostgreSQL y RabbitMQ (contrato C2)")
public class HealthController {

	private static final Logger log = LoggerFactory.getLogger(HealthController.class);
	private static final String SERVICE = "perfiles-service";
	private static final String JSON = "application/json";
	private static final int DATABASE_VALIDATION_TIMEOUT_SECONDS = 2;
	private static final int MAX_CHECKS_EN_CURSO = 8;
	private static final String UP = "UP";
	private static final String DOWN = "DOWN";

	private final DataSource dataSource;
	private final CachingConnectionFactory brokerConnectionFactory;
	private final String version;
	private final Duration timeout;
	private final ExecutorService executor = crearExecutor();

	@Autowired
	public HealthController(DataSource dataSource,
			@Qualifier("brokerConnectionFactory") CachingConnectionFactory brokerConnectionFactory,
			@Value("${service.version:desconocida}") String version,
			@Value("${health.check-timeout-ms:2000}") long timeoutMs) {
		this.dataSource = dataSource;
		this.brokerConnectionFactory = brokerConnectionFactory;
		this.version = version;
		this.timeout = Duration.ofMillis(timeoutMs);
	}

	@GetMapping("/health")
	@Operation(
			summary = "Health check",
			description = "Verifica PostgreSQL (database) y RabbitMQ (messageBroker) con un tiempo máximo "
					+ "de 2 s en total: responde en menos de 3 s aunque una dependencia cuelgue. "
					+ "No requiere JWT; Docker Compose lo usa como healthcheck del contenedor.")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Todas las dependencias están UP",
					content = @Content(mediaType = JSON, schema = @Schema(implementation = HealthResponse.class),
							examples = @ExampleObject(value = """
									{"status":"UP","service":"perfiles-service","version":"0.0.1-SNAPSHOT",\
									"checks":{"database":"UP","messageBroker":"UP"}}"""))),
			@ApiResponse(responseCode = "503", description = "Alguna dependencia está DOWN o no respondió a tiempo",
					content = @Content(mediaType = JSON, schema = @Schema(implementation = HealthResponse.class),
							examples = @ExampleObject(value = """
									{"status":"DOWN","service":"perfiles-service","version":"0.0.1-SNAPSHOT",\
									"checks":{"database":"DOWN","messageBroker":"UP"}}""")))
	})
	public ResponseEntity<HealthResponse> health() {
		long limite = System.nanoTime() + timeout.toNanos();
		// Se lanzan todos los checks antes de esperar: corren en paralelo y comparten el plazo.
		Map<String, Future<Boolean>> enCurso = new LinkedHashMap<>();
		enCurso.put("database", iniciar(this::baseDeDatosDisponible));
		if (brokerConnectionFactory != null) {
			enCurso.put("messageBroker", iniciar(this::brokerDisponible));
		}

		Map<String, String> checks = new LinkedHashMap<>();
		enCurso.forEach((nombre, futuro) -> checks.put(nombre, esperar(nombre, futuro, limite) ? UP : DOWN));

		boolean todoUp = checks.values().stream().allMatch(UP::equals);
		HealthResponse response = new HealthResponse(todoUp ? UP : DOWN, SERVICE, version, checks);
		return todoUp ? ResponseEntity.ok(response)
				: ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(response);
	}

	@PreDestroy
	void cerrar() {
		executor.shutdownNow();
	}

	private boolean baseDeDatosDisponible() throws Exception {
		try (Connection connection = dataSource.getConnection()) {
			return connection.isValid(DATABASE_VALIDATION_TIMEOUT_SECONDS);
		}
	}

	private boolean brokerDisponible() {
		try (org.springframework.amqp.rabbit.connection.Connection connection =
				brokerConnectionFactory.createConnection()) {
			return connection.isOpen();
		}
	}

	private Future<Boolean> iniciar(Callable<Boolean> check) {
		try {
			return executor.submit(check);
		} catch (RejectedExecutionException ex) {
			log.warn("Health check rechazado: hay {} checks colgados esperando a sus dependencias",
					MAX_CHECKS_EN_CURSO);
			return CompletableFuture.completedFuture(false);
		}
	}

	private boolean esperar(String nombre, Future<Boolean> futuro, long limiteNanos) {
		try {
			long restante = Math.max(0, limiteNanos - System.nanoTime());
			return Boolean.TRUE.equals(futuro.get(restante, TimeUnit.NANOSECONDS));
		} catch (TimeoutException ex) {
			futuro.cancel(true);
			log.warn("Health check de {} no respondió en {} ms", nombre, timeout.toMillis());
			return false;
		} catch (ExecutionException ex) {
			log.warn("Health check de {} falló: {}", nombre, ex.getCause() != null
					? ex.getCause().getMessage() : ex.getMessage());
			return false;
		} catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			return false;
		}
	}

	private static ExecutorService crearExecutor() {
		AtomicInteger contador = new AtomicInteger();
		return new ThreadPoolExecutor(0, MAX_CHECKS_EN_CURSO, 60, TimeUnit.SECONDS, new SynchronousQueue<>(),
				tarea -> {
					Thread hilo = new Thread(tarea, "health-check-" + contador.incrementAndGet());
					hilo.setDaemon(true);
					return hilo;
				});
	}
}

package com.microservicios.perfiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.microservicios.perfiles.repository.EventoProcesadoRepository;
import com.microservicios.perfiles.repository.PerfilRepository;

import tools.jackson.databind.ObjectMapper;

/**
 * Fase 8 (E14): integración contra PostgreSQL y RabbitMQ reales. Liquibase crea el esquema,
 * el listener consume del broker de verdad y la API se consulta por HTTP en un puerto real.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers(disabledWithoutDocker = true)
class PerfilesIT {

	private static final String USUARIO = "it";
	private static final String CLAVE = "it-pass";

	@Container
	static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16.4-alpine");

	@Container
	static final GenericContainer<?> RABBIT = new GenericContainer<>("rabbitmq:3.13.7-alpine")
			.withEnv("RABBITMQ_DEFAULT_USER", USUARIO)
			.withEnv("RABBITMQ_DEFAULT_PASS", CLAVE)
			.withExposedPorts(5672)
			.waitingFor(Wait.forLogMessage(".*Server startup complete.*", 1));

	@DynamicPropertySource
	static void propiedades(DynamicPropertyRegistry registro) {
		registro.add("spring.datasource.url", POSTGRES::getJdbcUrl);
		registro.add("spring.datasource.username", POSTGRES::getUsername);
		registro.add("spring.datasource.password", POSTGRES::getPassword);
		registro.add("broker.url", () -> "amqp://" + RABBIT.getHost() + ":" + RABBIT.getMappedPort(5672));
		registro.add("broker.username", () -> USUARIO);
		registro.add("broker.password", () -> CLAVE);
	}

	@Value("${local.server.port}")
	private int puerto;

	@Autowired
	private RabbitTemplate rabbit;

	@Autowired
	private AmqpAdmin admin;

	@Autowired
	private PerfilRepository perfiles;

	@Autowired
	private EventoProcesadoRepository eventos;

	@Autowired
	private ObjectMapper json;

	private final HttpClient http = HttpClient.newHttpClient();

	@Test
	void ciclo_de_vida_del_perfil_por_eventos_reales_y_api_http() throws Exception {
		String empleadoId = "E-IT-" + UUID.randomUUID().toString().substring(0, 6);
		String creado = publicar("empleado.creado", Map.of("empleadoId", empleadoId,
				"email", "it@empresa.com", "nombre", "Ana", "apellido", "Ruiz", "cargo", "Dev"));
		publicarConId(creado, "empleado.creado", Map.of("empleadoId", empleadoId, "email", "otro@empresa.com"));

		await().atMost(Duration.ofSeconds(20)).until(() -> perfiles.findByEmpleadoId(empleadoId).isPresent());
		await().atMost(Duration.ofSeconds(20)).until(() -> mensajes("q.perfiles") == 0);
		assertThat(eventos.existsById(creado)).isTrue();
		assertThat(perfiles.findByEmpleadoId(empleadoId).orElseThrow().getEmail())
				.as("el duplicado no sobrescribe").isEqualTo("it@empresa.com");

		HttpResponse<String> consulta = get("/perfiles/" + empleadoId);
		assertThat(consulta.statusCode()).isEqualTo(200);
		assertThat(json.readTree(consulta.body()).get("nombre").asString()).isEqualTo("Ana");
		assertThat(get("/perfiles").body()).contains(empleadoId);
		assertThat(get("/perfiles/NO-EXISTE").statusCode()).isEqualTo(404);

		HttpResponse<String> edicion = http.send(HttpRequest.newBuilder(uri("/perfiles/" + empleadoId))
				.header("Content-Type", "application/json")
				.PUT(HttpRequest.BodyPublishers.ofString("{\"ciudad\":\"Bogotá\",\"telefono\":\"+57 300\"}"))
				.build(), HttpResponse.BodyHandlers.ofString());
		assertThat(edicion.statusCode()).isEqualTo(200);
		assertThat(perfiles.findByEmpleadoId(empleadoId).orElseThrow().getCiudad()).isEqualTo("Bogotá");

		publicar("empleado.actualizado", Map.of("empleadoId", empleadoId, "cargo", "Líder"));
		await().atMost(Duration.ofSeconds(20))
				.until(() -> "Líder".equals(perfiles.findByEmpleadoId(empleadoId).orElseThrow().getCargo()));

		publicar("empleado.retirado", Map.of("empleadoId", empleadoId));
		await().atMost(Duration.ofSeconds(20))
				.until(() -> perfiles.findByEmpleadoId(empleadoId).orElseThrow().isArchivado());
		assertThat(json.readTree(get("/perfiles/" + empleadoId).body()).get("archivado").asBoolean()).isTrue();
	}

	@Test
	void un_mensaje_poison_termina_en_la_dlq_real() {
		long antes = mensajes("q.perfiles.dlq");
		MessageProperties propiedades = new MessageProperties();
		propiedades.setContentType("application/json");
		rabbit.send("onboarding.eventos", "empleado.creado", new Message("{no es json".getBytes(), propiedades));
		await().atMost(Duration.ofSeconds(20)).until(() -> mensajes("q.perfiles.dlq") == antes + 1);
	}

	@Test
	void health_reporta_base_y_broker_reales_up() throws Exception {
		HttpResponse<String> salud = get("/health");
		assertThat(salud.statusCode()).isEqualTo(200);
		assertThat(json.readTree(salud.body()).get("status").asString()).isEqualTo("UP");
	}

	private String publicar(String tipo, Map<String, Object> data) throws Exception {
		return publicarConId(UUID.randomUUID().toString(), tipo, data);
	}

	private String publicarConId(String id, String tipo, Map<String, Object> data) throws Exception {
		Map<String, Object> envelope = Map.of("id", id, "type", tipo, "version", 1,
				"occurredAt", Instant.now().toString(), "producer", "empleados-service", "data", data);
		MessageProperties propiedades = new MessageProperties();
		propiedades.setContentType("application/json");
		rabbit.send("onboarding.eventos", tipo, new Message(json.writeValueAsBytes(envelope), propiedades));
		return id;
	}

	private long mensajes(String cola) {
		Properties info = admin.getQueueProperties(cola);
		return info == null ? -1 : ((Number) info.get(RabbitAdmin.QUEUE_MESSAGE_COUNT)).longValue();
	}

	private HttpResponse<String> get(String ruta) throws Exception {
		return http.send(HttpRequest.newBuilder(uri(ruta)).GET().build(), HttpResponse.BodyHandlers.ofString());
	}

	private URI uri(String ruta) {
		return URI.create("http://localhost:" + puerto + ruta);
	}
}

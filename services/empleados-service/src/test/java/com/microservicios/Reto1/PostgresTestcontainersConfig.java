package com.microservicios.Reto1;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Postgres real para las pruebas de integración (*IT). El mismo motor que en
 * Compose: así Liquibase corre los changelogs de verdad y las restricciones
 * UNIQUE del esquema se ejercitan, cosa que H2 no garantiza. Spring reutiliza
 * el contexto entre clases IT, así que el contenedor se levanta una sola vez.
 */
@TestConfiguration(proxyBeanMethods = false)
public class PostgresTestcontainersConfig {

	@Bean
	@ServiceConnection
	PostgreSQLContainer postgres() {
		return new PostgreSQLContainer("postgres:16-alpine");
	}
}

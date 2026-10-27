package com.microservicios.Reto1;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * El contexto completo arranca contra Postgres: Liquibase aplica los changelogs
 * y Hibernate valida (ddl-auto=validate) que la entidad coincide con el esquema.
 * Sin Docker la clase se omite en lugar de fallar.
 */
@SpringBootTest
@Import(PostgresTestcontainersConfig.class)
@Testcontainers(disabledWithoutDocker = true)
class Reto1ApplicationIT {

	@Test
	void contextLoads() {
	}

}

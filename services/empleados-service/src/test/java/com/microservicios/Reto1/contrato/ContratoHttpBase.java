package com.microservicios.Reto1.contrato;

import javax.sql.DataSource;

import org.springdoc.core.configuration.SpringDocConfiguration;
import org.springdoc.core.properties.SpringDocConfigProperties;
import org.springdoc.webmvc.core.configuration.MultipleOpenApiSupportConfiguration;
import org.springdoc.webmvc.core.configuration.SpringDocWebMvcConfiguration;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.microservicios.Reto1.config.OpenApiConfig;
import com.microservicios.Reto1.controller.EmpleadoController;
import com.microservicios.Reto1.controller.HealthController;
import com.microservicios.Reto1.service.EmpleadoService;

/**
 * Slice de Spring MVC con los controladores reales, el {@code @RestControllerAdvice}
 * y Springdoc, sin PostgreSQL, RabbitMQ ni Docker. Las dependencias de los
 * controladores son dobles de Mockito. Las subclases comparten el mismo contexto
 * (caché de Spring Test), así que solo se arranca una vez.
 */
@WebMvcTest(controllers = { EmpleadoController.class, HealthController.class })
@Import(OpenApiConfig.class)
@ImportAutoConfiguration({
		SpringDocConfiguration.class,
		SpringDocConfigProperties.class,
		SpringDocWebMvcConfiguration.class,
		MultipleOpenApiSupportConfiguration.class })
abstract class ContratoHttpBase {

	@Autowired
	protected MockMvc mockMvc;

	@MockitoBean
	protected EmpleadoService empleadoService;

	@MockitoBean
	protected DataSource dataSource;

	@MockitoBean(name = "brokerConnectionFactory")
	protected CachingConnectionFactory brokerConnectionFactory;
}

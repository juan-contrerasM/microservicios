package com.microservicios.perfiles.contrato;

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

import com.microservicios.perfiles.config.OpenApiConfig;
import com.microservicios.perfiles.controller.HealthController;
import com.microservicios.perfiles.controller.PerfilController;
import com.microservicios.perfiles.service.PerfilConsultaService;

/**
 * Slice de Spring MVC con los controladores reales, el {@code @RestControllerAdvice}
 * y Springdoc, sin PostgreSQL, RabbitMQ ni Docker. El consumidor de eventos no se
 * carga en este slice. Las subclases comparten el mismo contexto.
 */
@WebMvcTest(controllers = { PerfilController.class, HealthController.class })
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
	protected PerfilConsultaService perfilConsultaService;

	@MockitoBean
	protected DataSource dataSource;

	@MockitoBean(name = "brokerConnectionFactory")
	protected CachingConnectionFactory brokerConnectionFactory;
}

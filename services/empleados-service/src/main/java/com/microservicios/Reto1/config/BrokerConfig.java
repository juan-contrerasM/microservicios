package com.microservicios.Reto1.config;

import java.time.Clock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Conexión al broker y exchange topic durable. Las colas las declaran los consumidores.
 * Si el broker no está, el arranque sigue: el fallo se ve al publicar, no al levantar el API.
 */
@Configuration
@EnableConfigurationProperties(BrokerProperties.class)
public class BrokerConfig {

	private static final Logger log = LoggerFactory.getLogger(BrokerConfig.class);

	@Bean
	public CachingConnectionFactory brokerConnectionFactory(BrokerProperties broker) {
		CachingConnectionFactory factory = new CachingConnectionFactory();
		try {
			factory.setUri(broker.getUrl());
		} catch (Exception ex) {
			throw new IllegalStateException("BROKER_URL inválida: " + broker.getUrl(), ex);
		}
		factory.setUsername(broker.getUsername());
		factory.setPassword(broker.getPassword());
		factory.setConnectionTimeout(broker.getConnectionTimeoutMs());
		return factory;
	}

	@Bean
	public TopicExchange onboardingExchange(BrokerProperties broker) {
		return new TopicExchange(broker.getExchange(), true, false);
	}

	@Bean
	public RabbitAdmin rabbitAdmin(CachingConnectionFactory brokerConnectionFactory) {
		RabbitAdmin admin = new RabbitAdmin(brokerConnectionFactory);
		admin.setIgnoreDeclarationExceptions(true);
		return admin;
	}

	@Bean
	public Clock clock() {
		return Clock.systemUTC();
	}

	/**
	 * Declara el exchange al arrancar. Si el broker no responde, el API sigue
	 * en pie: el fallo queda en el log y se reintenta en el próximo publish.
	 */
	@Bean
	public ApplicationRunner declareOnboardingExchange(RabbitAdmin rabbitAdmin, TopicExchange onboardingExchange) {
		return args -> {
			try {
				rabbitAdmin.declareExchange(onboardingExchange);
				log.info("Exchange declarado: {}", onboardingExchange.getName());
			} catch (RuntimeException ex) {
				log.warn("No se pudo declarar el exchange {} al arrancar: {}",
						onboardingExchange.getName(), ex.getMessage());
			}
		};
	}
}

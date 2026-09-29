package com.microservicios.perfiles.config;

import java.time.Clock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(BrokerProperties.class)
public class BrokerConfig {

	private static final Logger log = LoggerFactory.getLogger(BrokerConfig.class);

	static final String CREADO = "empleado.creado";
	static final String ACTUALIZADO = "empleado.actualizado";
	static final String RETIRADO = "empleado.retirado";

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
	public Queue perfilesQueue(BrokerProperties broker) {
		return QueueBuilder.durable(broker.getQueue()).build();
	}

	@Bean
	public Binding bindingCreado(Queue perfilesQueue, TopicExchange onboardingExchange) {
		return BindingBuilder.bind(perfilesQueue).to(onboardingExchange).with(CREADO);
	}

	@Bean
	public Binding bindingActualizado(Queue perfilesQueue, TopicExchange onboardingExchange) {
		return BindingBuilder.bind(perfilesQueue).to(onboardingExchange).with(ACTUALIZADO);
	}

	@Bean
	public Binding bindingRetirado(Queue perfilesQueue, TopicExchange onboardingExchange) {
		return BindingBuilder.bind(perfilesQueue).to(onboardingExchange).with(RETIRADO);
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

	@Bean
	public ApplicationRunner declarePerfilesTopology(RabbitAdmin rabbitAdmin, TopicExchange onboardingExchange,
			Queue perfilesQueue, Binding bindingCreado, Binding bindingActualizado, Binding bindingRetirado) {
		return args -> {
			try {
				rabbitAdmin.declareExchange(onboardingExchange);
				rabbitAdmin.declareQueue(perfilesQueue);
				rabbitAdmin.declareBinding(bindingCreado);
				rabbitAdmin.declareBinding(bindingActualizado);
				rabbitAdmin.declareBinding(bindingRetirado);
				log.info("Cola declarada: {} en {}", perfilesQueue.getName(), onboardingExchange.getName());
			} catch (RuntimeException ex) {
				log.warn("No se pudo declarar q.perfiles al arrancar: {}", ex.getMessage());
			}
		};
	}
}

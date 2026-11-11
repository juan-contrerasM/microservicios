package com.microservicios.perfiles.config;

import java.time.Clock;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Topología común de los consumidores (ver docs/eventos.md):
 * <ul>
 * <li>{@code q.perfiles}: dead-letter a {@code onboarding.dlx} con routing key {@code q.perfiles}.
 * Un reject sin requeue termina en la DLQ.</li>
 * <li>{@code q.perfiles.retry}: TTL 5 s y dead-letter al exchange por defecto ({@code ""}) con
 * routing key {@code q.perfiles}. El reintento vuelve solo a esta cola; si volviera al topic con
 * la routing key del evento, se re-difundiría a Auth, Notificaciones y Vacaciones.</li>
 * <li>{@code q.perfiles.dlq}: enlazada a {@code onboarding.dlx} con routing key {@code q.perfiles}.</li>
 * </ul>
 * Todo son beans {@code Declarable}: el {@link RabbitAdmin} los vuelve a declarar en cada
 * reconexión.
 */
@Configuration
@EnableConfigurationProperties(BrokerProperties.class)
public class BrokerConfig {

	static final String CREADO = "empleado.creado";
	static final String ACTUALIZADO = "empleado.actualizado";
	static final String RETIRADO = "empleado.retirado";
	public static final String DLX_EXCHANGE = "onboarding.dlx";
	public static final int RETRY_TTL_MS = 5000;

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
	public DirectExchange dlxExchange() {
		return new DirectExchange(DLX_EXCHANGE, true, false);
	}

	@Bean
	public Queue perfilesQueue(BrokerProperties broker) {
		return colaPrincipal(broker.getQueue());
	}

	@Bean
	public Queue perfilesRetryQueue(BrokerProperties broker) {
		return colaRetry(broker.getQueue());
	}

	@Bean
	public Queue perfilesDlq(BrokerProperties broker) {
		return QueueBuilder.durable(broker.getQueue() + ".dlq").build();
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
	public Binding bindingDlq(Queue perfilesDlq, DirectExchange dlxExchange, BrokerProperties broker) {
		return BindingBuilder.bind(perfilesDlq).to(dlxExchange).with(broker.getQueue());
	}

	@Bean
	public RabbitAdmin rabbitAdmin(CachingConnectionFactory brokerConnectionFactory) {
		RabbitAdmin admin = new RabbitAdmin(brokerConnectionFactory);
		// Si el broker no está o una cola vieja no coincide, el arranque sigue; la migración
		// de abajo recrea las colas vacías y el contenedor reintenta la conexión.
		admin.setIgnoreDeclarationExceptions(true);
		return admin;
	}

	@Bean
	public Clock clock() {
		return Clock.systemUTC();
	}

	@Bean
	public MigradorColas migradorColas(CachingConnectionFactory brokerConnectionFactory, RabbitAdmin rabbitAdmin) {
		return new MigradorColas(brokerConnectionFactory, rabbitAdmin);
	}

	/**
	 * Recrea al arrancar las colas que existan con argumentos antiguos (PRECONDITION_FAILED),
	 * solo si están vacías. Con mensajes dentro se registra el error y se deja la decisión
	 * a una persona (ver la sección Migración de docs/eventos.md).
	 */
	@Bean
	public ApplicationRunner declarePerfilesTopology(MigradorColas migradorColas, Queue perfilesQueue,
			Queue perfilesRetryQueue, Queue perfilesDlq) {
		return args -> migradorColas.asegurar(perfilesQueue, perfilesRetryQueue, perfilesDlq);
	}

	static Queue colaPrincipal(String nombre) {
		return QueueBuilder.durable(nombre)
				.deadLetterExchange(DLX_EXCHANGE)
				.deadLetterRoutingKey(nombre)
				.build();
	}

	static Queue colaRetry(String nombre) {
		return QueueBuilder.durable(nombre + ".retry")
				.ttl(RETRY_TTL_MS)
				.deadLetterExchange("")
				.deadLetterRoutingKey(nombre)
				.build();
	}
}

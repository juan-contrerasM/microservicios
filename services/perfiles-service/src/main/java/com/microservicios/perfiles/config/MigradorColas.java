package com.microservicios.perfiles.config;

import java.io.IOException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.connection.Connection;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.ShutdownSignalException;

/**
 * Cambiar los argumentos de una cola durable que ya existe hace que RabbitMQ responda
 * {@code PRECONDITION_FAILED} (406) y la cola se queda con los argumentos viejos. Esta clase
 * detecta ese caso al arrancar y recrea la cola solo si está vacía: borrar una cola con
 * mensajes perdería eventos, así que esa decisión se deja a una persona.
 */
public class MigradorColas {

	private static final Logger log = LoggerFactory.getLogger(MigradorColas.class);
	static final int PRECONDITION_FAILED = 406;

	private final ConnectionFactory connectionFactory;
	private final RabbitAdmin rabbitAdmin;

	public MigradorColas(ConnectionFactory connectionFactory, RabbitAdmin rabbitAdmin) {
		this.connectionFactory = connectionFactory;
		this.rabbitAdmin = rabbitAdmin;
	}

	/** Asegura cada cola con sus argumentos y vuelve a declarar la topología si recreó alguna. */
	public void asegurar(Queue... colas) {
		boolean recreada = false;
		for (Queue cola : colas) {
			try {
				recreada |= asegurarCola(cola);
			} catch (RuntimeException | IOException ex) {
				log.error("No se pudo asegurar la cola {}: {}", cola.getName(), ex.getMessage());
			}
		}
		if (recreada) {
			// Los bindings de una cola borrada desaparecen con ella: se declaran de nuevo todos.
			rabbitAdmin.initialize();
		}
	}

	/** @return true si la cola existía con otros argumentos y se recreó. */
	boolean asegurarCola(Queue cola) throws IOException {
		Connection conexion = connectionFactory.createConnection();
		Channel canal = conexion.createChannel(false);
		try {
			canal.queueDeclare(cola.getName(), true, false, false, cola.getArguments());
			return false;
		} catch (IOException ex) {
			if (!esPreconditionFailed(ex)) {
				throw ex;
			}
		} finally {
			cerrar(canal);
		}
		log.warn("La cola {} existe con argumentos distintos; se recrea si está vacía", cola.getName());
		Channel otro = conexion.createChannel(false);
		try {
			try {
				otro.queueDelete(cola.getName(), false, true);
			} catch (IOException ex) {
				throw new IllegalStateException("La cola " + cola.getName()
						+ " tiene mensajes y argumentos antiguos; vacíala o bórrala desde la UI de RabbitMQ"
						+ " (ver docs/eventos.md, sección Migración)", ex);
			}
			otro.queueDeclare(cola.getName(), true, false, false, cola.getArguments());
			log.warn("Cola {} recreada con los argumentos vigentes", cola.getName());
			return true;
		} finally {
			cerrar(otro);
		}
	}

	static boolean esPreconditionFailed(Throwable error) {
		for (Throwable actual = error; actual != null; actual = actual.getCause()) {
			if (actual instanceof ShutdownSignalException senal
					&& senal.getReason() instanceof AMQP.Channel.Close cierre
					&& cierre.getReplyCode() == PRECONDITION_FAILED) {
				return true;
			}
			if (actual.getMessage() != null && actual.getMessage().contains("PRECONDITION_FAILED")) {
				return true;
			}
		}
		return false;
	}

	private static void cerrar(Channel canal) {
		try {
			if (canal != null && canal.isOpen()) {
				canal.close();
			}
		} catch (Exception ex) {
			log.debug("El canal ya estaba cerrado: {}", ex.getMessage());
		}
	}
}

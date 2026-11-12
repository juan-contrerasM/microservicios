package com.microservicios.Reto1.config;

import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import io.micrometer.core.instrument.Metrics;

/**
 * Fase 11 (E20): métricas de negocio y de consumo de eventos. {@link Metrics#globalRegistry}
 * incluye el registro Prometheus de Actuator, así que aparecen en {@code /actuator/prometheus}
 * como {@code eventos_procesados_total}, {@code eventos_procesados_total{tipo}}, etc. Las etiquetas
 * son de baja cardinalidad: nunca ids, emails ni trace ids.
 */
public final class Metricas {

	private Metricas() {
	}

	/** Incrementa cuando la transacción confirma; si no hay transacción, en el acto. */
	public static void alConfirmar(String nombre) {
		if (TransactionSynchronizationManager.isSynchronizationActive()) {
			TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
				@Override
				public void afterCommit() {
					Metrics.counter(nombre).increment();
				}
			});
			return;
		}
		Metrics.counter(nombre).increment();
	}

	/**
	 * Registra en 0 las series de cada tipo consumido: {@code increase()} necesita ver el 0 para
	 * detectar el primer mensaje que llega a la DLQ (si la serie nace en 1, la alerta no se dispara).
	 */
	public static void inicializarEventos(Iterable<String> tipos) {
		for (String tipo : tipos) {
			for (String resultado : new String[] { "procesados", "duplicados", "dlq" }) {
				Metrics.counter("eventos." + resultado, "tipo", tipo);
			}
		}
	}

	/** {@code eventos_procesados_total}, {@code eventos_duplicados_total} o {@code eventos_dlq_total}. */
	public static void evento(String resultado, String tipo) {
		Metrics.counter("eventos." + resultado, "tipo", tipo == null || tipo.isBlank() ? "desconocido" : tipo)
				.increment();
	}
}

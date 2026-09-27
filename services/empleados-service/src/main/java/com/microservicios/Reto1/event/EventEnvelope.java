package com.microservicios.Reto1.event;

import java.time.Instant;

/**
 * Envelope común de los eventos de onboarding. El {@code id} es la clave de deduplicación.
 */
public record EventEnvelope(
		String id,
		String type,
		int version,
		Instant occurredAt,
		String producer,
		Object data) {
}

package com.microservicios.perfiles.event;

/**
 * El mensaje no cumple el contrato C1. Reintentarlo no lo arregla: va directo a la DLQ.
 */
public class EnvelopeInvalidoException extends RuntimeException {

	public EnvelopeInvalidoException(String mensaje) {
		super(mensaje);
	}
}

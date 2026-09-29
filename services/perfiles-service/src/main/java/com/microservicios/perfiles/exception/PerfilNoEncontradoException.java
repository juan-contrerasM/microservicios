package com.microservicios.perfiles.exception;

public class PerfilNoEncontradoException extends RuntimeException {

	public PerfilNoEncontradoException(String empleadoId) {
		super("El perfil del empleado " + empleadoId + " no existe");
	}
}

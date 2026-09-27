package com.microservicios.Reto1.dto;

import java.time.LocalDate;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;

/**
 * Actualización parcial. Un campo ausente o nulo no se modifica.
 * El id y el estado no viajan aquí: el id sale de la ruta y RETIRADO solo lo pone DELETE.
 */
@Schema(description = "Campos opcionales de un empleado. Los omitidos conservan su valor.")
public class ActualizarEmpleadoRequest {

	@Schema(example = "Juan Carlos")
	private String nombre;

	@Schema(example = "Pérez")
	private String apellido;

	@Email(message = "El email no tiene un formato válido")
	@Schema(example = "juan.perez@empresa.com")
	private String email;

	@Schema(example = "EMP-2026-001")
	private String numeroEmpleado;

	@Schema(example = "Desarrollador Senior")
	private String cargo;

	@Schema(example = "Tecnología")
	private String area;

	@Schema(example = "IT")
	private String departamentoId;

	@Schema(example = "2026-03-01")
	private LocalDate fechaIngreso;

	public String getNombre() {
		return nombre;
	}

	public void setNombre(String nombre) {
		this.nombre = nombre;
	}

	public String getApellido() {
		return apellido;
	}

	public void setApellido(String apellido) {
		this.apellido = apellido;
	}

	public String getEmail() {
		return email;
	}

	public void setEmail(String email) {
		this.email = email;
	}

	public String getNumeroEmpleado() {
		return numeroEmpleado;
	}

	public void setNumeroEmpleado(String numeroEmpleado) {
		this.numeroEmpleado = numeroEmpleado;
	}

	public String getCargo() {
		return cargo;
	}

	public void setCargo(String cargo) {
		this.cargo = cargo;
	}

	public String getArea() {
		return area;
	}

	public void setArea(String area) {
		this.area = area;
	}

	public String getDepartamentoId() {
		return departamentoId;
	}

	public void setDepartamentoId(String departamentoId) {
		this.departamentoId = departamentoId;
	}

	public LocalDate getFechaIngreso() {
		return fechaIngreso;
	}

	public void setFechaIngreso(LocalDate fechaIngreso) {
		this.fechaIngreso = fechaIngreso;
	}
}

package com.microservicios.perfiles.model;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "perfiles")
public class Perfil {

	@Id
	private String id;

	@Column(name = "empleado_id", nullable = false, unique = true)
	private String empleadoId;

	@Column(nullable = false)
	private String nombre;

	@Column(nullable = false)
	private String email;

	@Column(nullable = false)
	private String telefono;

	@Column(nullable = false)
	private String direccion;

	@Column(nullable = false)
	private String ciudad;

	@Column(nullable = false)
	private String biografia;

	@Column(name = "fecha_creacion", nullable = false)
	private Instant fechaCreacion;

	@Column(nullable = false)
	private boolean archivado;

	public String getId() {
		return id;
	}

	public void setId(String id) {
		this.id = id;
	}

	public String getEmpleadoId() {
		return empleadoId;
	}

	public void setEmpleadoId(String empleadoId) {
		this.empleadoId = empleadoId;
	}

	public String getNombre() {
		return nombre;
	}

	public void setNombre(String nombre) {
		this.nombre = nombre;
	}

	public String getEmail() {
		return email;
	}

	public void setEmail(String email) {
		this.email = email;
	}

	public String getTelefono() {
		return telefono;
	}

	public void setTelefono(String telefono) {
		this.telefono = telefono;
	}

	public String getDireccion() {
		return direccion;
	}

	public void setDireccion(String direccion) {
		this.direccion = direccion;
	}

	public String getCiudad() {
		return ciudad;
	}

	public void setCiudad(String ciudad) {
		this.ciudad = ciudad;
	}

	public String getBiografia() {
		return biografia;
	}

	public void setBiografia(String biografia) {
		this.biografia = biografia;
	}

	public Instant getFechaCreacion() {
		return fechaCreacion;
	}

	public void setFechaCreacion(Instant fechaCreacion) {
		this.fechaCreacion = fechaCreacion;
	}

	public boolean isArchivado() {
		return archivado;
	}

	public void setArchivado(boolean archivado) {
		this.archivado = archivado;
	}
}

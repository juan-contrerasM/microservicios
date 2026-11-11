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
	private String apellido;

	@Column(nullable = false)
	private String email;

	@Column(nullable = false)
	private String telefono;

	@Column(nullable = false)
	private String direccion;

	@Column(nullable = false)
	private String ciudad;

	@Column(name = "codigo_postal", nullable = false)
	private String codigoPostal;

	@Column(nullable = false)
	private String cargo;

	@Column(nullable = false)
	private String area;

	@Column(name = "numero_empleado", nullable = false)
	private String numeroEmpleado;

	@Column(name = "foto_url", nullable = false)
	private String fotoUrl;

	@Column(name = "redes_sociales", columnDefinition = "TEXT", nullable = false)
	private String redesSociales;

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

	public String getApellido() { return apellido; }
	public void setApellido(String apellido) { this.apellido = apellido; }
	public String getCodigoPostal() { return codigoPostal; }
	public void setCodigoPostal(String codigoPostal) { this.codigoPostal = codigoPostal; }
	public String getCargo() { return cargo; }
	public void setCargo(String cargo) { this.cargo = cargo; }
	public String getArea() { return area; }
	public void setArea(String area) { this.area = area; }
	public String getNumeroEmpleado() { return numeroEmpleado; }
	public void setNumeroEmpleado(String numeroEmpleado) { this.numeroEmpleado = numeroEmpleado; }
	public String getFotoUrl() { return fotoUrl; }
	public void setFotoUrl(String fotoUrl) { this.fotoUrl = fotoUrl; }
	public String getRedesSociales() { return redesSociales; }
	public void setRedesSociales(String redesSociales) { this.redesSociales = redesSociales; }

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

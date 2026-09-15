package com.microservicios.Reto1.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.microservicios.Reto1.model.Empleado;
import com.microservicios.Reto1.service.EmpleadoService;

import jakarta.validation.Valid;

/**
 * Endpoints HTTP para el registro y consulta de empleados.
 */
@RestController
@RequestMapping("/empleados")
public class EmpleadoController {

	private final EmpleadoService empleadoService;

	public EmpleadoController(EmpleadoService empleadoService) {
		this.empleadoService = empleadoService;
	}

	/**
	 * Registra un nuevo empleado en estado ACTIVO.
	 *
	 * @param empleado datos del empleado a registrar
	 * @return el empleado registrado con código 200, o 409 si el id,
	 *         el email o el numeroEmpleado ya existen
	 */
	@PostMapping
	public ResponseEntity<Empleado> registrar(@Valid @RequestBody Empleado empleado) {
		Empleado registrado = empleadoService.registrar(empleado);
		return ResponseEntity.ok(registrado);
	}

	/**
	 * Consulta un empleado por su id.
	 *
	 * @param id identificador del empleado
	 * @return el empleado encontrado con código 200, o 404 si no existe
	 */
	@GetMapping("/{id}")
	public ResponseEntity<Empleado> consultar(@PathVariable String id) {
		Empleado empleado = empleadoService.consultarPorId(id);
		return ResponseEntity.ok(empleado);
	}
}

package com.microservicios.perfiles.controller;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.microservicios.perfiles.dto.ActualizarPerfilRequest;
import com.microservicios.perfiles.dto.ApiError;
import com.microservicios.perfiles.model.Perfil;
import com.microservicios.perfiles.service.PerfilConsultaService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@Tag(name = "Perfiles", description = "Perfil de empleado replicado por eventos")
@SecurityRequirement(name = "BearerAuth")
public class PerfilController {

	private final PerfilConsultaService perfiles;

	public PerfilController(PerfilConsultaService perfiles) {
		this.perfiles = perfiles;
	}

	@GetMapping("/perfiles")
	@Operation(summary = "Listar perfiles")
	public List<Perfil> listar() {
		return perfiles.listar();
	}

	@GetMapping("/perfiles/{empleadoId}")
	@Operation(summary = "Obtener el perfil de un empleado")
	@ApiResponse(responseCode = "404", description = "No hay perfil para ese empleado",
			content = @Content(schema = @Schema(implementation = ApiError.class)))
	public Perfil obtener(@PathVariable String empleadoId) {
		return perfiles.obtener(empleadoId);
	}

	@PutMapping("/perfiles/{empleadoId}")
	@Operation(summary = "Editar teléfono, dirección, ciudad y biografía. No publica eventos.")
	@ApiResponse(responseCode = "404", description = "No hay perfil para ese empleado",
			content = @Content(schema = @Schema(implementation = ApiError.class)))
	public Perfil actualizar(@PathVariable String empleadoId, @RequestBody ActualizarPerfilRequest cambios) {
		return perfiles.actualizar(empleadoId, cambios);
	}
}

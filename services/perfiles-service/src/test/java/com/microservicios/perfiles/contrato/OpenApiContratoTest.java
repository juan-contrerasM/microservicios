package com.microservicios.perfiles.contrato;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import com.fasterxml.jackson.databind.JsonNode;

import io.swagger.v3.core.util.Yaml;

/**
 * E08: el {@code openapi.yaml} versionado en la raíz del servicio es exactamente
 * el que genera Springdoc (sin drift), y la especificación documenta todas las
 * rutas reales con su esquema de seguridad y sus códigos de error.
 *
 * <p>Para regenerar el archivo tras cambiar un endpoint:
 * {@code ./mvnw test -Dtest=OpenApiContratoTest -Dopenapi.actualizar=true}.
 */
class OpenApiContratoTest extends ContratoHttpBase {

	private static final Path OPENAPI_VERSIONADO = Path.of("openapi.yaml");
	private static final Set<String> SIN_JWT = Set.of("/health");

	@Autowired
	@Qualifier("requestMappingHandlerMapping")
	private RequestMappingHandlerMapping handlerMapping;

	private String generado;
	private JsonNode spec;

	@BeforeEach
	void generar() throws Exception {
		generado = normalizar(mockMvc.perform(get("/v3/api-docs.yaml"))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
		spec = Yaml.mapper().readTree(generado);
		if (Boolean.getBoolean("openapi.actualizar")) {
			Files.writeString(OPENAPI_VERSIONADO, generado, StandardCharsets.UTF_8);
		}
	}

	@Test
	void openapiVersionadoCoincideConElGenerado() throws IOException {
		assertThat(OPENAPI_VERSIONADO).as("falta services/perfiles-service/openapi.yaml").exists();
		String versionado = normalizar(Files.readString(OPENAPI_VERSIONADO, StandardCharsets.UTF_8));
		assertThat(versionado)
				.as("openapi.yaml está desactualizado: ejecute "
						+ "./mvnw test -Dtest=OpenApiContratoTest -Dopenapi.actualizar=true y versione el cambio")
				.isEqualTo(generado);
	}

	@Test
	void cadaRutaRegistradaEstaDocumentada() {
		List<String> faltantes = new ArrayList<>();
		handlerMapping.getHandlerMethods().forEach((info, metodo) -> {
			if (!metodo.getBeanType().getPackageName().startsWith("com.microservicios.perfiles")) {
				return;
			}
			for (String ruta : info.getPathPatternsCondition().getPatternValues()) {
				for (RequestMethod verbo : info.getMethodsCondition().getMethods()) {
					String clave = verbo.name().toLowerCase(Locale.ROOT);
					if (spec.path("paths").path(ruta).path(clave).isMissingNode()) {
						faltantes.add(verbo + " " + ruta);
					}
				}
			}
		});
		assertThat(faltantes).as("operaciones sin documentar en el OpenAPI").isEmpty();
	}

	@Test
	void bearerAuthEsHttpBearerJwt() {
		JsonNode esquema = spec.path("components").path("securitySchemes").path("bearerAuth");
		assertThat(esquema.path("type").asText()).isEqualTo("http");
		assertThat(esquema.path("scheme").asText()).isEqualTo("bearer");
		assertThat(esquema.path("bearerFormat").asText()).isEqualTo("JWT");
	}

	@Test
	void operacionesDeNegocioExigenBearerYDocumentanSusErrores() {
		spec.path("paths").properties().forEach(ruta -> ruta.getValue().properties().forEach(operacion -> {
			String nombre = operacion.getKey().toUpperCase(Locale.ROOT) + " " + ruta.getKey();
			JsonNode respuestas = operacion.getValue().path("responses");
			if (SIN_JWT.contains(ruta.getKey())) {
				assertThat(operacion.getValue().path("security").isMissingNode()).as(nombre).isTrue();
				assertThat(respuestas.has("200")).as(nombre + " 200").isTrue();
				assertThat(respuestas.has("503")).as(nombre + " 503").isTrue();
				return;
			}
			assertThat(operacion.getValue().path("security").toString()).as(nombre).contains("bearerAuth");
			assertThat(respuestas.has("401")).as(nombre + " 401").isTrue();
			assertThat(respuestas.has("500")).as(nombre + " 500").isTrue();
			if (!"get".equals(operacion.getKey())) {
				assertThat(respuestas.has("403")).as(nombre + " 403").isTrue();
			}
			respuestas.properties().forEach(respuesta -> {
				if (respuesta.getKey().startsWith("4") || respuesta.getKey().startsWith("5")) {
					assertThat(respuesta.getValue().toString()).as(nombre + " " + respuesta.getKey())
							.contains("#/components/schemas/ApiError");
				}
			});
		}));
	}

	@Test
	void apiErrorTieneLosCamposDelContratoC3() {
		JsonNode propiedades = spec.path("components").path("schemas").path("ApiError").path("properties");
		assertThat(propiedades.has("timestamp")).isTrue();
		assertThat(propiedades.has("status")).isTrue();
		assertThat(propiedades.has("error")).isTrue();
		assertThat(propiedades.has("message")).isTrue();
		assertThat(propiedades.has("path")).isTrue();
		assertThat(propiedades.has("traceId")).isTrue();
	}

	private static String normalizar(String yaml) {
		return yaml.replace("\r\n", "\n").stripTrailing() + "\n";
	}
}

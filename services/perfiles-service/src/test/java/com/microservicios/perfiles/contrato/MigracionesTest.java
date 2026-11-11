package com.microservicios.perfiles.contrato;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import javax.xml.parsers.DocumentBuilderFactory;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * E05 sin base de datos: cada changeset incluido en el changelog maestro declara
 * {@code <rollback>} y el esquema mantiene un único perfil por empleado
 * (UNIQUE en empleado_id) y la clave primaria de eventos_procesados, que es la
 * deduplicación persistente del consumidor.
 */
class MigracionesTest {

	private static final String MAESTRO = "db/changelog/db.changelog-master.xml";

	private static Document leer(String recurso) throws Exception {
		DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
		factory.setNamespaceAware(true);
		factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
		try (InputStream entrada = MigracionesTest.class.getClassLoader().getResourceAsStream(recurso)) {
			assertThat(entrada).as("no existe " + recurso).isNotNull();
			return factory.newDocumentBuilder().parse(entrada);
		}
	}

	private static List<Element> changeSetsIncluidos() throws Exception {
		List<Element> changeSets = new ArrayList<>();
		NodeList includes = leer(MAESTRO).getElementsByTagNameNS("*", "include");
		assertThat(includes.getLength()).isPositive();
		for (int i = 0; i < includes.getLength(); i++) {
			String archivo = ((Element) includes.item(i)).getAttribute("file");
			NodeList nodos = leer(archivo).getElementsByTagNameNS("*", "changeSet");
			for (int j = 0; j < nodos.getLength(); j++) {
				changeSets.add((Element) nodos.item(j));
			}
		}
		return changeSets;
	}

	private static Element restriccionesDeColumna(String tabla, String columna) throws Exception {
		for (Element changeSet : changeSetsIncluidos()) {
			NodeList tablas = changeSet.getElementsByTagNameNS("*", "createTable");
			for (int i = 0; i < tablas.getLength(); i++) {
				Element createTable = (Element) tablas.item(i);
				if (!tabla.equals(createTable.getAttribute("tableName"))) {
					continue;
				}
				NodeList columnas = createTable.getElementsByTagNameNS("*", "column");
				for (int j = 0; j < columnas.getLength(); j++) {
					Element columnaXml = (Element) columnas.item(j);
					if (columna.equals(columnaXml.getAttribute("name"))) {
						return (Element) columnaXml.getElementsByTagNameNS("*", "constraints").item(0);
					}
				}
			}
		}
		return null;
	}

	@Test
	void cadaChangesetDeclaraRollback() throws Exception {
		List<String> sinRollback = new ArrayList<>();
		for (Element changeSet : changeSetsIncluidos()) {
			if (changeSet.getElementsByTagNameNS("*", "rollback").getLength() == 0) {
				sinRollback.add(changeSet.getAttribute("id"));
			}
		}
		assertThat(sinRollback).as("changesets sin <rollback>").isEmpty();
	}

	@Test
	void unPerfilPorEmpleadoEsUnicoEnElEsquema() throws Exception {
		Element restricciones = restriccionesDeColumna("perfiles", "empleado_id");
		assertThat(restricciones).isNotNull();
		assertThat(restricciones.getAttribute("unique")).isEqualTo("true");
	}

	@Test
	void eventosProcesadosUsaElIdDelEventoComoClavePrimaria() throws Exception {
		Element restricciones = restriccionesDeColumna("eventos_procesados", "id");
		assertThat(restricciones).isNotNull();
		assertThat(restricciones.getAttribute("primaryKey")).isEqualTo("true");
	}
}

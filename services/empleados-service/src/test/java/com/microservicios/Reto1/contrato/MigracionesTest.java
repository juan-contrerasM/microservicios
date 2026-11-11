package com.microservicios.Reto1.contrato;

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
 * {@code <rollback>} y el esquema mantiene las restricciones UNIQUE de email y
 * numero_empleado. La aplicación real de los changelogs (y el rollback) la prueba
 * {@code Reto1ApplicationIT} contra PostgreSQL en Testcontainers.
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
	void emailYNumeroEmpleadoSonUnicosEnElEsquema() throws Exception {
		List<String> columnasUnicas = new ArrayList<>();
		for (Element changeSet : changeSetsIncluidos()) {
			NodeList restricciones = changeSet.getElementsByTagNameNS("*", "addUniqueConstraint");
			for (int i = 0; i < restricciones.getLength(); i++) {
				Element restriccion = (Element) restricciones.item(i);
				if ("empleados".equals(restriccion.getAttribute("tableName"))) {
					columnasUnicas.add(restriccion.getAttribute("columnNames"));
				}
			}
		}
		assertThat(columnasUnicas).contains("email", "numero_empleado");
	}
}

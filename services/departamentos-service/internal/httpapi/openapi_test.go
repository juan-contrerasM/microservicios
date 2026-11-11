package httpapi

import (
	"bytes"
	"fmt"
	"net/http"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"testing"

	"github.com/go-chi/chi/v5"
	"gopkg.in/yaml.v3"
)

// openapiExportado es el contrato versionado en la raíz del servicio (E08). El
// servicio embebe una copia en swagger/openapi.yaml porque go:embed no puede leer
// fuera del paquete y el Dockerfile solo copia cmd/ e internal/.
var openapiExportado = filepath.Join("..", "..", "openapi.yaml")

// rutasTecnicas no son operaciones de negocio y no forman parte del contrato:
// métricas para Prometheus y la propia documentación.
var rutasTecnicas = map[string]bool{
	"/metrics":            true,
	"/openapi.yaml":       true,
	"/swagger":            true,
	"/swagger/":           true,
	"/swagger/index.html": true,
}

func normalizar(b []byte) []byte {
	return bytes.ReplaceAll(b, []byte("\r\n"), []byte("\n"))
}

func cargarSpec(t *testing.T) map[string]any {
	t.Helper()
	data, err := os.ReadFile(openapiExportado)
	if err != nil {
		t.Fatalf("no se pudo leer %s: %v", openapiExportado, err)
	}
	var spec map[string]any
	if err := yaml.Unmarshal(data, &spec); err != nil {
		t.Fatalf("openapi.yaml no es YAML válido: %v", err)
	}
	return spec
}

func mapa(t *testing.T, v any, donde string) map[string]any {
	t.Helper()
	m, ok := v.(map[string]any)
	if !ok {
		t.Fatalf("%s no es un objeto: %#v", donde, v)
	}
	return m
}

func TestOpenAPIEmbebidoIgualAlExportado(t *testing.T) {
	exportado, err := os.ReadFile(openapiExportado)
	if err != nil {
		t.Fatalf("falta services/departamentos-service/openapi.yaml: %v", err)
	}
	embebido, err := swaggerFS.ReadFile("swagger/openapi.yaml")
	if err != nil {
		t.Fatalf("falta la copia embebida: %v", err)
	}
	if !bytes.Equal(normalizar(exportado), normalizar(embebido)) {
		t.Fatal("internal/httpapi/swagger/openapi.yaml difiere de openapi.yaml: " +
			"edite openapi.yaml en la raíz del servicio y cópielo a internal/httpapi/swagger/")
	}
}

func TestOpenAPIEsValidoYVersionCoincide(t *testing.T) {
	spec := cargarSpec(t)
	if v := fmt.Sprint(spec["openapi"]); !strings.HasPrefix(v, "3.") {
		t.Errorf("versión de OpenAPI = %q", v)
	}
	info := mapa(t, spec["info"], "info")
	if info["version"] != Version {
		t.Errorf("info.version = %v, Version = %s: deben coincidir", info["version"], Version)
	}
	esquema := mapa(t, mapa(t, mapa(t, spec["components"], "components")["securitySchemes"], "securitySchemes")["bearerAuth"], "bearerAuth")
	if esquema["type"] != "http" || esquema["scheme"] != "bearer" || esquema["bearerFormat"] != "JWT" {
		t.Errorf("bearerAuth = %v", esquema)
	}
	error := mapa(t, mapa(t, mapa(t, spec["components"], "components")["schemas"], "schemas")["ApiError"], "ApiError")
	props := mapa(t, error["properties"], "ApiError.properties")
	for _, campo := range []string{"timestamp", "status", "error", "message", "path", "traceId"} {
		if _, ok := props[campo]; !ok {
			t.Errorf("ApiError no documenta %q (contrato C3)", campo)
		}
	}
}

func TestCadaRutaRegistradaEstaDocumentada(t *testing.T) {
	paths := mapa(t, cargarSpec(t)["paths"], "paths")
	var faltantes []string
	err := chi.Walk(rutas(nil, nil), func(metodo, ruta string, _ http.Handler, _ ...func(http.Handler) http.Handler) error {
		if rutasTecnicas[ruta] {
			return nil
		}
		if len(ruta) > 1 {
			ruta = strings.TrimSuffix(ruta, "/")
		}
		item, ok := paths[ruta].(map[string]any)
		if !ok {
			faltantes = append(faltantes, metodo+" "+ruta)
			return nil
		}
		if _, ok := item[strings.ToLower(metodo)]; !ok {
			faltantes = append(faltantes, metodo+" "+ruta)
		}
		return nil
	})
	if err != nil {
		t.Fatalf("recorriendo rutas: %v", err)
	}
	sort.Strings(faltantes)
	if len(faltantes) > 0 {
		t.Errorf("operaciones sin documentar en openapi.yaml: %v", faltantes)
	}
}

func TestOperacionesDeNegocioExigenBearerYDocumentanSusErrores(t *testing.T) {
	spec := cargarSpec(t)
	seguridadGlobal := fmt.Sprint(spec["security"])
	for ruta, v := range mapa(t, spec["paths"], "paths") {
		for metodo, o := range mapa(t, v, ruta) {
			if metodo == "parameters" {
				continue
			}
			nombre := strings.ToUpper(metodo) + " " + ruta
			operacion := mapa(t, o, nombre)
			respuestas := mapa(t, operacion["responses"], nombre+" responses")
			seguridad, propia := operacion["security"]
			if ruta == "/health" {
				if !propia || len(seguridad.([]any)) != 0 {
					t.Errorf("%s debe ser público (security: [])", nombre)
				}
				for _, codigo := range []string{"200", "503"} {
					if _, ok := respuestas[codigo]; !ok {
						t.Errorf("%s no documenta %s", nombre, codigo)
					}
				}
				continue
			}
			efectiva := seguridadGlobal
			if propia {
				efectiva = fmt.Sprint(seguridad)
			}
			if !strings.Contains(efectiva, "bearerAuth") {
				t.Errorf("%s no exige bearerAuth", nombre)
			}
			esperados := []string{"401", "500", "503"}
			if metodo != "get" {
				esperados = append(esperados, "403")
			}
			for _, codigo := range esperados {
				if _, ok := respuestas[codigo]; !ok {
					t.Errorf("%s no documenta %s", nombre, codigo)
				}
			}
		}
	}
}

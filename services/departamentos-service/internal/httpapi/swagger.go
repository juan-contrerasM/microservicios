package httpapi

import (
	"embed"
	"net/http"
)

// Spec OpenAPI estática (Etapa 4). Se eligió un YAML versionado en el repo
// en vez de swaggo/codegen: no añade una herramienta de generación al
// arranque ni al Dockerfile, y la spec queda revisable en el mismo PR que
// cambia un endpoint. Swagger UI se sirve en /swagger/index.html; el YAML
// en /openapi.yaml. Los JS/CSS de swagger-ui-dist los carga el navegador.
//
//go:embed swagger/openapi.yaml swagger/index.html
var swaggerFS embed.FS

func serveOpenAPISpec(w http.ResponseWriter, _ *http.Request) {
	data, err := swaggerFS.ReadFile("swagger/openapi.yaml")
	if err != nil {
		writeError(w, http.StatusInternalServerError, "no se pudo leer la especificación OpenAPI")
		return
	}
	w.Header().Set("Content-Type", "application/yaml; charset=utf-8")
	w.WriteHeader(http.StatusOK)
	_, _ = w.Write(data)
}

func serveSwaggerUI(w http.ResponseWriter, _ *http.Request) {
	data, err := swaggerFS.ReadFile("swagger/index.html")
	if err != nil {
		writeError(w, http.StatusInternalServerError, "no se pudo leer Swagger UI")
		return
	}
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	w.WriteHeader(http.StatusOK)
	_, _ = w.Write(data)
}

func redirectSwagger(w http.ResponseWriter, r *http.Request) {
	http.Redirect(w, r, "/swagger/index.html", http.StatusMovedPermanently)
}

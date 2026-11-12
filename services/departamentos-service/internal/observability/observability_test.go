package observability

import (
	"net/http"
	"net/http/httptest"
	"testing"
)

func TestMetricsMiddlewareAndRoutes(t *testing.T) {
	h := Handler(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) { w.WriteHeader(http.StatusCreated) }))
	r := httptest.NewRequest(http.MethodPost, "/departamentos/IT", nil)
	w := httptest.NewRecorder()
	h.ServeHTTP(w, r)
	if w.Code != http.StatusCreated {
		t.Fatalf("status: %d", w.Code)
	}
	if got := normalizedRoute("/departamentos/IT"); got != "/departamentos/{id}" {
		t.Fatalf("ruta: %s", got)
	}
	if got := normalizedRoute("/health"); got != "/health" {
		t.Fatalf("ruta: %s", got)
	}
}

func TestMetricsEndpointBypassesInstrumentation(t *testing.T) {
	called := false
	h := metrics(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) { called = true; w.WriteHeader(http.StatusNoContent) }))
	w := httptest.NewRecorder()
	h.ServeHTTP(w, httptest.NewRequest(http.MethodGet, "/metrics", nil))
	if !called || w.Code != http.StatusNoContent {
		t.Fatalf("called=%v status=%d", called, w.Code)
	}
	if MetricsHandler() == nil {
		t.Fatal("handler de Prometheus nil")
	}
}

func TestGetenv(t *testing.T) {
	t.Setenv("OBS_TEST", "valor")
	if getenv("OBS_TEST", "fallback") != "valor" {
		t.Fatal("no leyó el entorno")
	}
	t.Setenv("OBS_TEST", "")
	if getenv("OBS_TEST", "fallback") != "fallback" {
		t.Fatal("no aplicó fallback")
	}
}

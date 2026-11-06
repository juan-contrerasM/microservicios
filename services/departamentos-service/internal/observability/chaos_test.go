package observability

import (
	"net/http"
	"net/http/httptest"
	"testing"
	"time"
)

func okHandler() http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) { w.WriteHeader(http.StatusNoContent) })
}

func TestChaosLatencyDisabledByDefault(t *testing.T) {
	for _, value := range []string{"", "0", "-5", "abc"} {
		t.Setenv("CHAOS_LATENCY_MS", value)
		start := time.Now()
		rec := httptest.NewRecorder()
		ChaosLatencyFromEnv()(okHandler()).ServeHTTP(rec, httptest.NewRequest(http.MethodGet, "/departamentos", nil))
		if rec.Code != http.StatusNoContent {
			t.Fatalf("CHAOS_LATENCY_MS=%q: status %d", value, rec.Code)
		}
		if elapsed := time.Since(start); elapsed > 50*time.Millisecond {
			t.Fatalf("CHAOS_LATENCY_MS=%q no debía retrasar, tardó %s", value, elapsed)
		}
	}
}

func TestChaosLatencyDelaysRequest(t *testing.T) {
	t.Setenv("CHAOS_LATENCY_MS", "120")
	start := time.Now()
	rec := httptest.NewRecorder()
	ChaosLatencyFromEnv()(okHandler()).ServeHTTP(rec, httptest.NewRequest(http.MethodGet, "/departamentos", nil))
	if rec.Code != http.StatusNoContent {
		t.Fatalf("status %d", rec.Code)
	}
	if elapsed := time.Since(start); elapsed < 120*time.Millisecond {
		t.Fatalf("se esperaba un retardo de al menos 120ms, tardó %s", elapsed)
	}
}

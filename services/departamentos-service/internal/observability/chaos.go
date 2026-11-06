package observability

import (
	"log/slog"
	"net/http"
	"os"
	"strconv"
	"time"
)

// ChaosLatencyFromEnv lee CHAOS_LATENCY_MS y devuelve un middleware que retrasa
// cada petición ese tiempo fijo. Solo existe para el caos controlado del Reto 8
// (docs/reto8, Fase 6): con 0, vacío o un valor inválido no altera nada.
// El retardo es determinista (sin aleatoriedad) y se aplica únicamente donde el
// router lo monta, nunca sobre /health ni /metrics.
func ChaosLatencyFromEnv() func(http.Handler) http.Handler {
	ms, err := strconv.Atoi(os.Getenv("CHAOS_LATENCY_MS"))
	if err != nil || ms <= 0 {
		return func(next http.Handler) http.Handler { return next }
	}
	slog.Warn("caos controlado activo: retardo fijo en endpoints de negocio", "latencyMs", ms)
	return ChaosLatency(time.Duration(ms) * time.Millisecond)
}

// ChaosLatency retrasa la petición el tiempo indicado, o hasta que el cliente
// cancele, y luego delega en el siguiente handler.
func ChaosLatency(delay time.Duration) func(http.Handler) http.Handler {
	return func(next http.Handler) http.Handler {
		return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			timer := time.NewTimer(delay)
			defer timer.Stop()
			select {
			case <-timer.C:
				next.ServeHTTP(w, r)
			case <-r.Context().Done():
			}
		})
	}
}

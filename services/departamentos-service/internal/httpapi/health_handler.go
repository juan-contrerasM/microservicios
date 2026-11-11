package httpapi

import (
	"context"
	"log/slog"
	"net/http"
	"os"
	"time"
)

// Version es la versión del servicio que publica GET /health (contrato C2).
// Coincide con info.version de openapi.yaml (lo verifica openapi_test.go). Se
// puede fijar al compilar con -ldflags "-X departamentos-service/internal/httpapi.Version=x.y.z"
// y SERVICE_VERSION la sobrescribe en tiempo de ejecución (por ejemplo, con el
// tag de la imagen desplegada).
var Version = "1.0.0"

// healthCheckTimeout es el plazo total del check de MySQL. El contrato C2 exige
// responder en menos de 3 s aunque la base cuelgue.
const healthCheckTimeout = 2 * time.Second

// pinger es lo único que el health check necesita de la base: *sql.DB lo
// cumple y las pruebas lo reemplazan por un doble que falla o se cuelga.
type pinger interface {
	PingContext(ctx context.Context) error
}

// HealthHandler expone GET /health, usado como healthcheck de Docker del
// propio servicio (ver docker-compose.yml raíz).
type HealthHandler struct {
	db      pinger
	timeout time.Duration
	version string
}

// NewHealthHandler construye el handler sobre la misma conexión que usa el
// resto del servicio (no abre una conexión aparte).
func NewHealthHandler(db pinger) *HealthHandler {
	version := Version
	if v := os.Getenv("SERVICE_VERSION"); v != "" {
		version = v
	}
	return &HealthHandler{db: db, timeout: healthCheckTimeout, version: version}
}

type healthResponse struct {
	Status  string            `json:"status"`
	Service string            `json:"service"`
	Version string            `json:"version"`
	Checks  map[string]string `json:"checks"`
}

// Check hace un PING real a MySQL y responde 200 ("UP") si funciona o 503
// ("DOWN") si falla o no contesta dentro de healthCheckTimeout. El ping corre
// en su propia goroutine: aunque el driver no respetara el contexto (por
// ejemplo, un servidor que acepta TCP y nunca responde), el handler deja de
// esperar al vencer el plazo.
func (h *HealthHandler) Check(w http.ResponseWriter, r *http.Request) {
	database := "UP"
	if err := conPlazo(r.Context(), h.timeout, h.db.PingContext); err != nil {
		slog.Warn("health check de MySQL falló", "error", err)
		database = "DOWN"
	}

	status, code := "UP", http.StatusOK
	if database != "UP" {
		status, code = "DOWN", http.StatusServiceUnavailable
	}
	writeJSON(w, code, healthResponse{
		Status:  status,
		Service: "departamentos-service",
		Version: h.version,
		Checks:  map[string]string{"database": database},
	})
}

// conPlazo ejecuta check con un contexto que vence en timeout y devuelve
// context.DeadlineExceeded si no terminó a tiempo.
func conPlazo(ctx context.Context, timeout time.Duration, check func(context.Context) error) error {
	ctx, cancel := context.WithTimeout(ctx, timeout)
	defer cancel()
	resultado := make(chan error, 1) // con buffer: la goroutine nunca queda bloqueada al escribir
	go func() { resultado <- check(ctx) }()
	select {
	case err := <-resultado:
		return err
	case <-ctx.Done():
		return ctx.Err()
	}
}

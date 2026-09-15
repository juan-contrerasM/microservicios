package httpapi

import (
	"database/sql"
	"net/http"
)

// HealthHandler expone GET /health, usado como healthcheck de Docker del
// propio servicio (ver docker-compose.yml raíz) y por empleados-service para
// verificar disponibilidad antes de reintentar.
type HealthHandler struct {
	db *sql.DB
}

// NewHealthHandler construye el handler sobre la misma conexión que usa el
// resto del servicio (no abre una conexión aparte).
func NewHealthHandler(db *sql.DB) *HealthHandler {
	return &HealthHandler{db: db}
}

// Check hace un PING real a la base de datos y responde 200 ("UP") si
// funciona, o 503 ("DOWN") si no. A diferencia de un healthcheck que solo
// comprueba que el proceso responde, este sí refleja si el servicio puede
// realmente atender peticiones.
func (h *HealthHandler) Check(w http.ResponseWriter, r *http.Request) {
	if err := h.db.PingContext(r.Context()); err != nil {
		writeJSON(w, http.StatusServiceUnavailable, map[string]string{
			"status": "DOWN",
			"error":  "no se pudo conectar a la base de datos",
		})
		return
	}
	writeJSON(w, http.StatusOK, map[string]string{"status": "UP"})
}

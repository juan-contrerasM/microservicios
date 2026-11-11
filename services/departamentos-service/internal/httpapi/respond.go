package httpapi

import (
	"context"
	"database/sql"
	"database/sql/driver"
	"encoding/json"
	"errors"
	"log/slog"
	"net"
	"net/http"
	"time"

	"go.opentelemetry.io/otel/trace"
)

// apiError es el error HTTP uniforme del contrato C3 del proyecto final, el
// mismo en los siete servicios. Nunca lleva trazas, SQL ni detalles internos:
// el detalle técnico va solo al log.
type apiError struct {
	Timestamp string `json:"timestamp"`
	Status    int    `json:"status"`
	Error     string `json:"error"`
	Message   string `json:"message"`
	Path      string `json:"path"`
	TraceID   string `json:"traceId,omitempty"`
}

const (
	mensajeErrorInterno    = "error interno del servidor"
	mensajeBDNoDisponible  = "la base de datos no está disponible"
	mensajeRutaInexistente = "recurso no encontrado"
)

// writeJSON escribe status como código HTTP y body codificado como JSON.
// Un body nil solo escribe el status, sin cuerpo.
func writeJSON(w http.ResponseWriter, status int, body any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	if body == nil {
		return
	}
	if err := json.NewEncoder(w).Encode(body); err != nil {
		slog.Error("error codificando respuesta JSON", "error", err)
	}
}

// writeError responde con el formato de error C3. traceId sale del span que
// abre otelhttp (observability.Handler); si la petición no tiene span válido
// se omite.
func writeError(w http.ResponseWriter, r *http.Request, status int, mensaje string) {
	body := apiError{
		Timestamp: time.Now().UTC().Format(time.RFC3339Nano),
		Status:    status,
		Error:     http.StatusText(status),
		Message:   mensaje,
		Path:      r.URL.Path,
	}
	if sc := trace.SpanContextFromContext(r.Context()); sc.HasTraceID() {
		body.TraceID = sc.TraceID().String()
	}
	writeJSON(w, status, body)
}

// writeInternalError separa un fallo de la base de datos (503: dependencia
// caída) de un error inesperado (500). En ambos casos el detalle se registra
// en el log y el cliente solo recibe un mensaje genérico.
func writeInternalError(w http.ResponseWriter, r *http.Request, operacion string, err error) {
	if baseDeDatosNoDisponible(err) {
		slog.Warn("MySQL no disponible", "operacion", operacion, "error", err)
		writeError(w, r, http.StatusServiceUnavailable, mensajeBDNoDisponible)
		return
	}
	slog.Error("error inesperado", "operacion", operacion, "error", err)
	writeError(w, r, http.StatusInternalServerError, mensajeErrorInterno)
}

// baseDeDatosNoDisponible reconoce los errores de conexión del pool y del
// driver (conexión rechazada, host inexistente, conexión rota o plazo vencido).
func baseDeDatosNoDisponible(err error) bool {
	var opErr *net.OpError
	return errors.Is(err, driver.ErrBadConn) ||
		errors.Is(err, sql.ErrConnDone) ||
		errors.Is(err, context.DeadlineExceeded) ||
		errors.As(err, &opErr)
}

// notFound y methodNotAllowed reemplazan las respuestas en texto plano de chi
// para que una ruta o un método inexistentes también respeten el contrato C3.
func notFound(w http.ResponseWriter, r *http.Request) {
	writeError(w, r, http.StatusNotFound, mensajeRutaInexistente)
}

func methodNotAllowed(w http.ResponseWriter, r *http.Request) {
	writeError(w, r, http.StatusMethodNotAllowed, "método no permitido para este recurso")
}

// recoverer convierte un panic en un 500 con el formato C3 (middleware.Recoverer
// de chi respondía sin cuerpo). El valor del panic va al log, no al cliente.
func recoverer(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		defer func() {
			if rec := recover(); rec != nil {
				if rec == http.ErrAbortHandler {
					panic(rec)
				}
				slog.Error("panic atendiendo la petición", "method", r.Method, "path", r.URL.Path, "panic", rec)
				writeError(w, r, http.StatusInternalServerError, mensajeErrorInterno)
			}
		}()
		next.ServeHTTP(w, r)
	})
}

package httpapi

import (
	"encoding/json"
	"log"
	"net/http"
)

// apiError sigue el mismo formato de error que empleados-service
// ({"mensaje": "..."}) para que ambos servicios sean consistentes de cara al
// cliente.
type apiError struct {
	Mensaje string `json:"mensaje"`
}

// writeJSON escribe status como código HTTP y body codificado como JSON.
// Un body nil solo escribe el status, sin cuerpo (para respuestas 204/sin
// contenido, si algún endpoint futuro lo necesita).
func writeJSON(w http.ResponseWriter, status int, body any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	if body == nil {
		return
	}
	if err := json.NewEncoder(w).Encode(body); err != nil {
		log.Printf("error codificando respuesta JSON: %v", err)
	}
}

// writeError responde con el formato de error estándar {"mensaje": "..."}.
func writeError(w http.ResponseWriter, status int, mensaje string) {
	writeJSON(w, status, apiError{Mensaje: mensaje})
}

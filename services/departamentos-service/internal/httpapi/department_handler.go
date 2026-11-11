package httpapi

import (
	"encoding/json"
	"errors"
	"net/http"

	"github.com/go-chi/chi/v5"

	"departamentos-service/internal/department"
)

// DepartmentHandler expone los endpoints HTTP de /departamentos. Traduce
// entrada/salida JSON y errores de dominio (department.Err*) a códigos de
// estado HTTP; toda la lógica de negocio vive en department.Service.
type DepartmentHandler struct {
	svc *department.Service
}

// NewDepartmentHandler construye el handler a partir del servicio de dominio
// que va a usar para atender cada petición.
func NewDepartmentHandler(svc *department.Service) *DepartmentHandler {
	return &DepartmentHandler{svc: svc}
}

// Create atiende POST /departamentos: decodifica el cuerpo JSON, delega el
// registro en el servicio y responde 201 con el departamento creado, 400 si
// los datos son inválidos o 409 si el id ya existe (contrato C3).
func (h *DepartmentHandler) Create(w http.ResponseWriter, r *http.Request) {
	var input department.Department
	if err := json.NewDecoder(r.Body).Decode(&input); err != nil {
		writeError(w, r, http.StatusBadRequest, "cuerpo de la petición inválido")
		return
	}

	created, err := h.svc.Register(r.Context(), input)
	switch {
	case err == nil:
		writeJSON(w, http.StatusCreated, created)
	case errors.Is(err, department.ErrValidation):
		writeError(w, r, http.StatusBadRequest, "el departamento requiere al menos 'id' y 'nombre'")
	case errors.Is(err, department.ErrAlreadyExists):
		writeError(w, r, http.StatusConflict, "ya existe un departamento registrado con ese id")
	default:
		writeInternalError(w, r, "registrando departamento", err)
	}
}

// GetByID atiende GET /departamentos/{id}: responde 200 con el departamento
// si existe, o 404 con un mensaje descriptivo si no.
func (h *DepartmentHandler) GetByID(w http.ResponseWriter, r *http.Request) {
	id := chi.URLParam(r, "id")

	found, err := h.svc.Get(r.Context(), id)
	switch {
	case err == nil:
		writeJSON(w, http.StatusOK, found)
	case errors.Is(err, department.ErrNotFound):
		writeError(w, r, http.StatusNotFound, "el departamento con id "+id+" no existe")
	default:
		writeInternalError(w, r, "consultando departamento", err)
	}
}

// List atiende GET /departamentos: responde 200 con todos los departamentos
// registrados (arreglo vacío si no hay ninguno).
func (h *DepartmentHandler) List(w http.ResponseWriter, r *http.Request) {
	all, err := h.svc.List(r.Context())
	if err != nil {
		writeInternalError(w, r, "listando departamentos", err)
		return
	}
	writeJSON(w, http.StatusOK, all)
}

// Update atiende PUT /departamentos/{id}: el id de la ruta manda sobre el del
// cuerpo. Responde 200, 400 si falta el nombre o 404 si no existe.
func (h *DepartmentHandler) Update(w http.ResponseWriter, r *http.Request) {
	id := chi.URLParam(r, "id")
	var input department.Department
	if err := json.NewDecoder(r.Body).Decode(&input); err != nil {
		writeError(w, r, http.StatusBadRequest, "cuerpo de la petición inválido")
		return
	}
	updated, err := h.svc.Update(r.Context(), id, input)
	switch {
	case err == nil:
		writeJSON(w, http.StatusOK, updated)
	case errors.Is(err, department.ErrValidation):
		writeError(w, r, http.StatusBadRequest, "el departamento requiere 'nombre'")
	case errors.Is(err, department.ErrNotFound):
		writeError(w, r, http.StatusNotFound, "el departamento con id "+id+" no existe")
	default:
		writeInternalError(w, r, "actualizando departamento", err)
	}
}

// Delete atiende DELETE /departamentos/{id}: responde 204 sin cuerpo o 404.
func (h *DepartmentHandler) Delete(w http.ResponseWriter, r *http.Request) {
	id := chi.URLParam(r, "id")
	err := h.svc.Delete(r.Context(), id)
	switch {
	case err == nil:
		w.WriteHeader(http.StatusNoContent)
	case errors.Is(err, department.ErrValidation):
		writeError(w, r, http.StatusBadRequest, "el id del departamento es obligatorio")
	case errors.Is(err, department.ErrNotFound):
		writeError(w, r, http.StatusNotFound, "el departamento con id "+id+" no existe")
	default:
		writeInternalError(w, r, "eliminando departamento", err)
	}
}

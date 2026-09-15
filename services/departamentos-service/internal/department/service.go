package department

import (
	"context"
	"errors"
	"strings"
)

// ErrValidation cubre datos de entrada incompletos o inválidos (por ejemplo,
// falta el id o el nombre) antes de intentar tocar la base de datos.
var ErrValidation = errors.New("datos de departamento inválidos")

// repository es el contrato mínimo que Service necesita del acceso a datos.
// Se define aquí (y no en repository.go) para que Service dependa de una
// interfaz propia, no del *Repository concreto — facilita reemplazarlo por
// un doble de prueba si en el futuro se agregan tests unitarios del
// servicio.
type repository interface {
	Create(ctx context.Context, d Department) (Department, error)
	GetByID(ctx context.Context, id string) (Department, error)
	List(ctx context.Context) ([]Department, error)
}

// Service contiene las reglas de negocio de departamentos: validación de
// entrada y delegación al repositorio. Los handlers HTTP (internal/httpapi)
// solo conocen este tipo, nunca el repositorio directamente.
type Service struct {
	repo repository
}

// NewService construye el servicio sobre cualquier implementación de
// repository (normalmente *Repository, ver repository.go).
func NewService(repo repository) *Service {
	return &Service{repo: repo}
}

// Register valida y registra un departamento nuevo. Recorta espacios en
// blanco de id/nombre y exige que ambos vengan no vacíos antes de llegar a
// la base de datos; la unicidad de id la garantiza el repositorio (ver
// repository.go), no esta validación.
func (s *Service) Register(ctx context.Context, d Department) (Department, error) {
	d.ID = strings.TrimSpace(d.ID)
	d.Nombre = strings.TrimSpace(d.Nombre)
	if d.ID == "" || d.Nombre == "" {
		return Department{}, ErrValidation
	}
	return s.repo.Create(ctx, d)
}

// Get busca un departamento por id; propaga ErrNotFound del repositorio si
// no existe.
func (s *Service) Get(ctx context.Context, id string) (Department, error) {
	return s.repo.GetByID(ctx, id)
}

// List devuelve todos los departamentos registrados.
func (s *Service) List(ctx context.Context) ([]Department, error) {
	return s.repo.List(ctx)
}

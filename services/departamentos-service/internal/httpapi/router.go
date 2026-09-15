// Package httpapi contiene la capa HTTP del servicio: el router, los
// handlers y el formato común de respuesta/error. No conoce detalles de la
// base de datos más allá de recibir un *sql.DB para construir el
// repositorio; toda la lógica de negocio vive en internal/department.
package httpapi

import (
	"database/sql"
	"net/http"

	"github.com/go-chi/chi/v5"
	"github.com/go-chi/chi/v5/middleware"

	"departamentos-service/internal/department"
)

// NewRouter arma el árbol de dependencias del servicio (repositorio →
// servicio de dominio → handlers) y devuelve el http.Handler final que
// cmd/api/main.go pasa a http.ListenAndServe.
func NewRouter(db *sql.DB) http.Handler {
	repo := department.NewRepository(db)
	svc := department.NewService(repo)
	deptHandler := NewDepartmentHandler(svc)
	healthHandler := NewHealthHandler(db)

	r := chi.NewRouter()
	r.Use(middleware.Logger)    // log de cada petición (método, ruta, status, duración)
	r.Use(middleware.Recoverer) // convierte un panic en 500 en vez de tumbar el proceso

	// Healthcheck propio del servicio: lo usa Docker Compose
	// (docker-compose.yml raíz) para no marcar el contenedor "healthy"
	// hasta que la conexión a MySQL responde.
	r.Get("/health", healthHandler.Check)

	// Endpoints de negocio del Reto 2 (§3 del enunciado).
	r.Route("/departamentos", func(r chi.Router) {
		r.Post("/", deptHandler.Create)     // POST   /departamentos      -> registrar
		r.Get("/", deptHandler.List)        // GET    /departamentos      -> listar
		r.Get("/{id}", deptHandler.GetByID) // GET    /departamentos/{id} -> consultar por id
	})

	return r
}

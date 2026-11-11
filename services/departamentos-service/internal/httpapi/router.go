// Package httpapi contiene la capa HTTP del servicio: el router, los
// handlers y el formato común de respuesta/error. No conoce detalles de la
// base de datos más allá de recibir un *sql.DB para construir el
// repositorio; toda la lógica de negocio vive en internal/department.
package httpapi

import (
	"database/sql"
	"net/http"

	"github.com/go-chi/chi/v5"

	"departamentos-service/internal/department"
	"departamentos-service/internal/observability"
)

// NewRouter arma el árbol de dependencias del servicio (repositorio →
// servicio de dominio → handlers) y devuelve el http.Handler final que
// cmd/api/main.go pasa a http.ListenAndServe.
func NewRouter(db *sql.DB) http.Handler {
	return observability.Handler(newMux(db))
}

// newMux arma los handlers reales sobre MySQL.
func newMux(db *sql.DB) *chi.Mux {
	repo := department.NewRepository(db)
	svc := department.NewService(repo)
	return rutas(NewDepartmentHandler(svc), NewHealthHandler(db))
}

// rutas registra las rutas. Está separado de newMux para que las pruebas usen
// dobles en lugar de MySQL y recorran las rutas reales con chi.Walk
// (openapi_test.go).
func rutas(deptHandler *DepartmentHandler, healthHandler *HealthHandler) *chi.Mux {
	r := chi.NewRouter()
	r.Use(recoverer) // convierte un panic en 500 con el formato C3 en vez de tumbar el proceso
	r.NotFound(notFound)
	r.MethodNotAllowed(methodNotAllowed)

	// Healthcheck propio del servicio (contrato C2): lo usa Docker Compose
	// (docker-compose.yml raíz) para no marcar el contenedor "healthy"
	// hasta que la conexión a MySQL responde.
	r.Get("/health", healthHandler.Check)
	r.Handle("/metrics", observability.MetricsHandler())

	// OpenAPI / Swagger UI (Etapa 4): spec estática embebida, sin codegen.
	r.Get("/openapi.yaml", serveOpenAPISpec)
	r.Get("/swagger", redirectSwagger)
	r.Get("/swagger/", redirectSwagger)
	r.Get("/swagger/index.html", serveSwaggerUI)

	// Endpoints de negocio del Reto 2 (§3 del enunciado).
	r.Route("/departamentos", func(r chi.Router) {
		// Caos controlado del Reto 8: inactivo salvo que CHAOS_LATENCY_MS > 0.
		r.Use(observability.ChaosLatencyFromEnv())
		r.Post("/", deptHandler.Create)       // POST   /departamentos      -> registrar
		r.Get("/", deptHandler.List)          // GET    /departamentos      -> listar
		r.Get("/{id}", deptHandler.GetByID)   // GET    /departamentos/{id} -> consultar por id
		r.Put("/{id}", deptHandler.Update)    // PUT    /departamentos/{id} -> actualizar
		r.Delete("/{id}", deptHandler.Delete) // DELETE /departamentos/{id} -> eliminar
	})

	return r
}

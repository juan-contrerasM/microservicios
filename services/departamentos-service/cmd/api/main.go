// Servicio de departamentos (Reto 2). Ver services/departamentos-service/README.md.
package main

import (
	"context"
	"database/sql"
	"errors"
	"log/slog"
	"net/http"
	"time"

	_ "github.com/go-sql-driver/mysql"

	"departamentos-service/internal/config"
	dbmigrate "departamentos-service/internal/db"
	"departamentos-service/internal/httpapi"
	"departamentos-service/internal/observability"
)

func main() {
	shutdownTelemetry, err := observability.Setup(context.Background())
	if err != nil {
		slog.Error("configurando observabilidad", "error", err)
		return
	}
	defer func() { _ = shutdownTelemetry(context.Background()) }()
	// 1. Configuración desde variables de entorno (ver internal/config).
	cfg := config.Load()

	// 2. Pool de conexiones a MySQL. sql.Open no conecta todavía; solo
	// valida el DSN y prepara el pool.
	db, err := sql.Open("mysql", cfg.DSN())
	if err != nil {
		slog.Error("abriendo conexión a MySQL", "error", err)
		return
	}
	defer db.Close()
	db.SetMaxOpenConns(10)
	db.SetConnMaxLifetime(5 * time.Minute)

	// 3. Confirmar que sí hay conexión real. El healthcheck de Docker
	// Compose garantiza que MySQL ya acepta conexiones antes de arrancar
	// este contenedor (depends_on: condition: service_healthy). Este ping
	// solo confirma que las credenciales/DSN son correctas al iniciar.
	if err := db.Ping(); err != nil {
		slog.Error("no se pudo conectar a MySQL en el arranque", "error", err)
		return
	}

	// 4. Esquema reproducible: aplicar los changelogs pendientes (ver
	// internal/db/migrate.go y db/migrations/). Es lo que hace que
	// `docker compose up --build` deje la base de datos lista sin pasos
	// manuales.
	if err := dbmigrate.Migrate(db); err != nil {
		slog.Error("no se pudieron aplicar los changelogs", "error", err)
		return
	}

	// 5. Router HTTP (endpoints de negocio + /health), ver internal/httpapi.
	router := httpapi.NewRouter(db)

	// 6. Servir peticiones hasta que el proceso se detenga.
	addr := ":" + cfg.Port
	slog.Info("departamentos-service escuchando", "address", addr)
	if err := http.ListenAndServe(addr, router); err != nil && !errors.Is(err, http.ErrServerClosed) {
		slog.Error("servidor HTTP detenido", "error", err)
	}
}

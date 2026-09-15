// Package db aplica los changelogs versionados de services/departamentos-service/db/migrations
// contra MySQL usando golang-migrate, para que el esquema se cree de forma
// reproducible al arrancar el contenedor (§5 del enunciado de Reto 2) y sea
// posible hacer rollback de una versión con Down().
package db

import (
	"database/sql"
	"errors"
	"fmt"

	"github.com/golang-migrate/migrate/v4"
	mysqlmigrate "github.com/golang-migrate/migrate/v4/database/mysql"
	_ "github.com/golang-migrate/migrate/v4/source/file"
)

// MigrationsPath es donde el Dockerfile copia db/migrations dentro de la
// imagen. Se puede sobreescribir en pruebas locales fuera de Docker.
const MigrationsPath = "file://db/migrations"

// Migrate aplica todas las migraciones pendientes contra la base de datos ya
// abierta en sqlDB. Es idempotente: si el esquema ya está en la última
// versión, no hace nada.
func Migrate(sqlDB *sql.DB) error {
	driver, err := mysqlmigrate.WithInstance(sqlDB, &mysqlmigrate.Config{})
	if err != nil {
		return fmt.Errorf("creando driver de migración de MySQL: %w", err)
	}

	m, err := migrate.NewWithDatabaseInstance(MigrationsPath, "mysql", driver)
	if err != nil {
		return fmt.Errorf("cargando changelogs desde %s: %w", MigrationsPath, err)
	}

	if err := m.Up(); err != nil && !errors.Is(err, migrate.ErrNoChange) {
		return fmt.Errorf("aplicando migraciones: %w", err)
	}
	return nil
}

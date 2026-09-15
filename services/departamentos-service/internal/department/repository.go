package department

import (
	"context"
	"database/sql"
	"errors"
	"fmt"

	"github.com/go-sql-driver/mysql"
)

// mysqlDuplicateEntry es el código de error de MySQL para violación de una
// restricción UNIQUE (incluida la primary key).
const mysqlDuplicateEntry = 1062

// Repository es el acceso a datos de departamentos sobre MySQL, usando
// database/sql directo (sin ORM) para evitar el auto-DDL que el enunciado
// del Reto 2 desaconseja como mecanismo de esquema definitivo.
type Repository struct {
	db *sql.DB
}

// NewRepository construye el repositorio sobre una conexión ya abierta
// (y con las migraciones ya aplicadas, ver internal/db/migrate.go).
func NewRepository(db *sql.DB) *Repository {
	return &Repository{db: db}
}

// Create inserta un departamento nuevo. La unicidad de id la garantiza la
// PRIMARY KEY de la tabla (ver db/migrations/0001_*.up.sql), no esta
// función: aquí solo se traduce el error 1062 de MySQL (entrada duplicada)
// a ErrAlreadyExists para que la capa HTTP responda 400 con un mensaje
// claro, en vez de un 500 genérico.
func (r *Repository) Create(ctx context.Context, d Department) (Department, error) {
	_, err := r.db.ExecContext(ctx,
		`INSERT INTO departamentos (id, nombre, descripcion) VALUES (?, ?, ?)`,
		d.ID, d.Nombre, d.Descripcion,
	)
	if err != nil {
		var mysqlErr *mysql.MySQLError
		if errors.As(err, &mysqlErr) && mysqlErr.Number == mysqlDuplicateEntry {
			return Department{}, ErrAlreadyExists
		}
		return Department{}, fmt.Errorf("insertando departamento: %w", err)
	}
	return d, nil
}

// GetByID busca un departamento por su id. Devuelve ErrNotFound si no existe
// ninguna fila con ese id (sql.ErrNoRows traducido al error de dominio).
func (r *Repository) GetByID(ctx context.Context, id string) (Department, error) {
	var d Department
	err := r.db.QueryRowContext(ctx,
		`SELECT id, nombre, descripcion FROM departamentos WHERE id = ?`, id,
	).Scan(&d.ID, &d.Nombre, &d.Descripcion)
	if errors.Is(err, sql.ErrNoRows) {
		return Department{}, ErrNotFound
	}
	if err != nil {
		return Department{}, fmt.Errorf("consultando departamento %s: %w", id, err)
	}
	return d, nil
}

// List devuelve todos los departamentos ordenados por id. Devuelve un slice
// vacío (nunca nil) cuando no hay ninguno, para que la respuesta JSON sea
// siempre "[]" y no "null".
func (r *Repository) List(ctx context.Context) ([]Department, error) {
	rows, err := r.db.QueryContext(ctx, `SELECT id, nombre, descripcion FROM departamentos ORDER BY id`)
	if err != nil {
		return nil, fmt.Errorf("listando departamentos: %w", err)
	}
	defer rows.Close()

	departamentos := make([]Department, 0)
	for rows.Next() {
		var d Department
		if err := rows.Scan(&d.ID, &d.Nombre, &d.Descripcion); err != nil {
			return nil, fmt.Errorf("leyendo fila de departamento: %w", err)
		}
		departamentos = append(departamentos, d)
	}
	return departamentos, rows.Err()
}

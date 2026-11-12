package department

import (
	"context"
	"database/sql"
	"errors"
	"testing"

	"github.com/DATA-DOG/go-sqlmock"
	"github.com/go-sql-driver/mysql"
)

func repositoryMock(t *testing.T) (*Repository, sqlmock.Sqlmock) {
	t.Helper()
	db, mock, err := sqlmock.New(sqlmock.QueryMatcherOption(sqlmock.QueryMatcherEqual))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = db.Close() })
	return NewRepository(db), mock
}

func TestRepositoryCreate(t *testing.T) {
	r, mock := repositoryMock(t)
	d := Department{ID: "IT", Nombre: "Tecnologia", Descripcion: "Sistemas"}
	mock.ExpectExec("INSERT INTO departamentos (id, nombre, descripcion) VALUES (?, ?, ?)").
		WithArgs(d.ID, d.Nombre, d.Descripcion).WillReturnResult(sqlmock.NewResult(1, 1))
	if got, err := r.Create(context.Background(), d); err != nil || got != d {
		t.Fatalf("Create: %#v, %v", got, err)
	}
	mock.ExpectExec("INSERT INTO departamentos (id, nombre, descripcion) VALUES (?, ?, ?)").
		WithArgs(d.ID, d.Nombre, d.Descripcion).WillReturnError(&mysql.MySQLError{Number: mysqlDuplicateEntry})
	if _, err := r.Create(context.Background(), d); !errors.Is(err, ErrAlreadyExists) {
		t.Fatalf("duplicado: %v", err)
	}
}

func TestRepositoryGetAndList(t *testing.T) {
	r, mock := repositoryMock(t)
	mock.ExpectQuery("SELECT id, nombre, descripcion FROM departamentos WHERE id = ?").WithArgs("IT").
		WillReturnRows(sqlmock.NewRows([]string{"id", "nombre", "descripcion"}).AddRow("IT", "Tecnologia", "Sistemas"))
	if got, err := r.GetByID(context.Background(), "IT"); err != nil || got.ID != "IT" {
		t.Fatalf("GetByID: %#v, %v", got, err)
	}
	mock.ExpectQuery("SELECT id, nombre, descripcion FROM departamentos WHERE id = ?").WithArgs("NO").WillReturnError(sql.ErrNoRows)
	if _, err := r.GetByID(context.Background(), "NO"); !errors.Is(err, ErrNotFound) {
		t.Fatalf("no encontrado: %v", err)
	}
	mock.ExpectQuery("SELECT id, nombre, descripcion FROM departamentos ORDER BY id").
		WillReturnRows(sqlmock.NewRows([]string{"id", "nombre", "descripcion"}).AddRow("IT", "Tecnologia", "Sistemas").AddRow("RH", "Talento", "Personas"))
	if got, err := r.List(context.Background()); err != nil || len(got) != 2 {
		t.Fatalf("List: %#v, %v", got, err)
	}
}

func TestRepositoryUpdateAndDelete(t *testing.T) {
	r, mock := repositoryMock(t)
	d := Department{ID: "IT", Nombre: "Innovacion", Descripcion: "Digital"}
	mock.ExpectExec("UPDATE departamentos SET nombre = ?, descripcion = ? WHERE id = ?").
		WithArgs(d.Nombre, d.Descripcion, d.ID).WillReturnResult(sqlmock.NewResult(0, 1))
	if got, err := r.Update(context.Background(), d); err != nil || got != d {
		t.Fatalf("Update: %#v, %v", got, err)
	}
	mock.ExpectExec("UPDATE departamentos SET nombre = ?, descripcion = ? WHERE id = ?").
		WithArgs(d.Nombre, d.Descripcion, d.ID).WillReturnResult(sqlmock.NewResult(0, 0))
	if _, err := r.Update(context.Background(), d); !errors.Is(err, ErrNotFound) {
		t.Fatalf("Update inexistente: %v", err)
	}
	mock.ExpectExec("DELETE FROM departamentos WHERE id = ?").WithArgs("IT").WillReturnResult(sqlmock.NewResult(0, 1))
	if err := r.Delete(context.Background(), "IT"); err != nil {
		t.Fatalf("Delete: %v", err)
	}
	mock.ExpectExec("DELETE FROM departamentos WHERE id = ?").WithArgs("NO").WillReturnResult(sqlmock.NewResult(0, 0))
	if err := r.Delete(context.Background(), "NO"); !errors.Is(err, ErrNotFound) {
		t.Fatalf("Delete inexistente: %v", err)
	}
}

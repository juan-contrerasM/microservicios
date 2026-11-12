//go:build integration

// Package integration prueba el servicio completo contra MySQL real (Fase 8, E14):
// migraciones up/down/up con golang-migrate y el CRUD por HTTP sobre el router de producción.
// Se ejecuta con `go test -tags integration ./internal/integration/...` y IT_MYSQL_DSN definida
// (lo hace scripts/test-integration.sh); sin la variable la prueba se omite.
package integration

import (
	"bytes"
	"database/sql"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"net/http/httptest"
	"os"
	"testing"
	"time"

	_ "github.com/go-sql-driver/mysql"
	"github.com/golang-migrate/migrate/v4"
	mysqlmigrate "github.com/golang-migrate/migrate/v4/database/mysql"

	dbmigrate "departamentos-service/internal/db"
	"departamentos-service/internal/httpapi"
)

func TestMain(m *testing.M) {
	// db.MigrationsPath es relativo a la raíz del módulo, igual que en la imagen.
	if err := os.Chdir("../.."); err != nil {
		panic(err)
	}
	os.Exit(m.Run())
}

func abrir(t *testing.T) *sql.DB {
	t.Helper()
	dsn := os.Getenv("IT_MYSQL_DSN")
	if dsn == "" {
		t.Skip("IT_MYSQL_DSN no definida")
	}
	db, err := sql.Open("mysql", dsn)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = db.Close() })
	if err := db.Ping(); err != nil {
		t.Fatalf("MySQL no responde: %v", err)
	}
	return db
}

func existeTabla(t *testing.T, db *sql.DB) bool {
	t.Helper()
	var n int
	if err := db.QueryRow(`SELECT COUNT(*) FROM information_schema.tables
		WHERE table_schema = DATABASE() AND table_name = 'departamentos'`).Scan(&n); err != nil {
		t.Fatal(err)
	}
	return n == 1
}

func TestMigracionesUpDownUpContraMySQLReal(t *testing.T) {
	db := abrir(t)
	if err := dbmigrate.Migrate(db); err != nil {
		t.Fatalf("up: %v", err)
	}
	if !existeTabla(t, db) {
		t.Fatal("up no creó la tabla departamentos")
	}
	driver, err := mysqlmigrate.WithInstance(db, &mysqlmigrate.Config{})
	if err != nil {
		t.Fatal(err)
	}
	m, err := migrate.NewWithDatabaseInstance(dbmigrate.MigrationsPath, "mysql", driver)
	if err != nil {
		t.Fatal(err)
	}
	if err := m.Down(); err != nil && !errors.Is(err, migrate.ErrNoChange) {
		t.Fatalf("down: %v", err)
	}
	if existeTabla(t, db) {
		t.Fatal("down no borró la tabla departamentos")
	}
	if err := dbmigrate.Migrate(db); err != nil {
		t.Fatalf("up tras rollback: %v", err)
	}
	if !existeTabla(t, db) {
		t.Fatal("la tabla no volvió tras el segundo up")
	}
}

func TestCrudHTTPContraMySQLReal(t *testing.T) {
	db := abrir(t)
	if err := dbmigrate.Migrate(db); err != nil {
		t.Fatal(err)
	}
	srv := httptest.NewServer(httpapi.NewRouter(db))
	defer srv.Close()
	id := fmt.Sprintf("IT-%d", time.Now().UnixNano())

	pedir := func(metodo, ruta string, cuerpo any) (int, map[string]any) {
		t.Helper()
		var buf bytes.Buffer
		if cuerpo != nil {
			_ = json.NewEncoder(&buf).Encode(cuerpo)
		}
		req, _ := http.NewRequest(metodo, srv.URL+ruta, &buf)
		req.Header.Set("Content-Type", "application/json")
		res, err := http.DefaultClient.Do(req)
		if err != nil {
			t.Fatal(err)
		}
		defer res.Body.Close()
		var salida map[string]any
		_ = json.NewDecoder(res.Body).Decode(&salida)
		return res.StatusCode, salida
	}

	if s, b := pedir(http.MethodGet, "/health", nil); s != http.StatusOK || b["status"] != "UP" {
		t.Fatalf("health: %d %v", s, b)
	}
	if s, _ := pedir(http.MethodPost, "/departamentos", map[string]string{"id": id, "nombre": "Ingeniería"}); s != http.StatusCreated {
		t.Fatalf("POST: %d", s)
	}
	if s, _ := pedir(http.MethodPost, "/departamentos", map[string]string{"id": id, "nombre": "Otro"}); s != http.StatusConflict {
		t.Fatalf("POST duplicado: %d, se esperaba 409", s)
	}
	if s, b := pedir(http.MethodGet, "/departamentos/"+id, nil); s != http.StatusOK || b["nombre"] != "Ingeniería" {
		t.Fatalf("GET: %d %v", s, b)
	}
	if s, b := pedir(http.MethodPut, "/departamentos/"+id, map[string]string{"nombre": "Plataforma", "descripcion": "Infra"}); s != http.StatusOK || b["nombre"] != "Plataforma" {
		t.Fatalf("PUT: %d %v", s, b)
	}
	res, err := http.Get(srv.URL + "/departamentos")
	if err != nil {
		t.Fatal(err)
	}
	var lista []map[string]any
	_ = json.NewDecoder(res.Body).Decode(&lista)
	res.Body.Close()
	encontrado := false
	for _, d := range lista {
		encontrado = encontrado || d["id"] == id
	}
	if !encontrado {
		t.Fatalf("GET /departamentos no lista %s", id)
	}
	if s, _ := pedir(http.MethodDelete, "/departamentos/"+id, nil); s != http.StatusNoContent {
		t.Fatalf("DELETE: %d", s)
	}
	if s, _ := pedir(http.MethodGet, "/departamentos/"+id, nil); s != http.StatusNotFound {
		t.Fatalf("GET tras DELETE: %d", s)
	}
}

package db

import (
	"os"
	"path/filepath"
	"regexp"
	"strings"
	"testing"
)

// Directorio real de los changelogs, relativo a este paquete.
var directorioMigraciones = filepath.Join("..", "..", "db", "migrations")

var nombreMigracion = regexp.MustCompile(`^(\d{4})_[a-z0-9_]+\.(up|down)\.sql$`)

// TestCadaMigracionTieneRollback (E05): golang-migrate solo puede revertir una
// versión si existe su .down.sql. Falla si se agrega un .up.sql sin pareja, un
// archivo con nombre fuera de la convención o una versión repetida.
func TestCadaMigracionTieneRollback(t *testing.T) {
	entradas, err := os.ReadDir(directorioMigraciones)
	if err != nil {
		t.Fatalf("leyendo %s: %v", directorioMigraciones, err)
	}
	ups, downs := map[string]string{}, map[string]string{}
	for _, entrada := range entradas {
		m := nombreMigracion.FindStringSubmatch(entrada.Name())
		if m == nil {
			t.Errorf("nombre fuera de la convención NNNN_descripcion.(up|down).sql: %s", entrada.Name())
			continue
		}
		destino := ups
		if m[2] == "down" {
			destino = downs
		}
		if previo, repetido := destino[m[1]]; repetido {
			t.Errorf("versión %s repetida: %s y %s", m[1], previo, entrada.Name())
		}
		destino[m[1]] = entrada.Name()
	}
	if len(ups) == 0 {
		t.Fatal("no hay migraciones")
	}
	for version, up := range ups {
		down, ok := downs[version]
		if !ok {
			t.Errorf("%s no tiene su .down.sql", up)
			continue
		}
		contenido, err := os.ReadFile(filepath.Join(directorioMigraciones, down))
		if err != nil || strings.TrimSpace(quitarComentarios(string(contenido))) == "" {
			t.Errorf("%s está vacío: el rollback no haría nada", down)
		}
	}
	for version, down := range downs {
		if _, ok := ups[version]; !ok {
			t.Errorf("%s no tiene su .up.sql", down)
		}
	}
}

// TestIdDeDepartamentoEsUnicoEnElEsquema: la unicidad del id la garantiza la
// PRIMARY KEY (la comprobación en código solo da un mensaje 409 descriptivo).
func TestIdDeDepartamentoEsUnicoEnElEsquema(t *testing.T) {
	contenido, err := os.ReadFile(filepath.Join(directorioMigraciones, "0001_create_departamentos.up.sql"))
	if err != nil {
		t.Fatalf("leyendo la migración inicial: %v", err)
	}
	if !strings.Contains(strings.ToUpper(quitarComentarios(string(contenido))), "PRIMARY KEY (ID)") {
		t.Error("departamentos.id debe ser PRIMARY KEY")
	}
}

func quitarComentarios(sql string) string {
	var lineas []string
	for _, linea := range strings.Split(sql, "\n") {
		if i := strings.Index(linea, "--"); i >= 0 {
			linea = linea[:i]
		}
		lineas = append(lineas, linea)
	}
	return strings.Join(lineas, "\n")
}

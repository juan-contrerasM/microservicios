package config

import (
	"os"
	"strings"
	"testing"
)

func TestLoadDefaultsAndDSN(t *testing.T) {
	for _, key := range []string{"PORT", "DB_HOST", "DB_PORT", "DB_NAME", "DB_USER", "DB_PASSWORD", "DB_PASSWORD_FILE"} {
		t.Setenv(key, "")
	}
	c := Load()
	if c.Port != "8081" || c.DBHost != "localhost" || c.DBPort != "3306" || c.DBName != "departamentos_db" || c.DBUser != "departamentos" {
		t.Fatalf("defaults inesperados: %#v", c)
	}
	if !strings.Contains(c.DSN(), "departamentos:@tcp(localhost:3306)/departamentos_db") {
		t.Fatalf("DSN inesperado: %s", c.DSN())
	}
}

func TestLoadReadsEnvironmentAndSecretFile(t *testing.T) {
	secret, err := os.CreateTemp(t.TempDir(), "db-secret")
	if err != nil {
		t.Fatal(err)
	}
	if _, err := secret.WriteString(" clave-segura\n"); err != nil {
		t.Fatal(err)
	}
	if err := secret.Close(); err != nil {
		t.Fatal(err)
	}
	t.Setenv("PORT", "9091")
	t.Setenv("DB_HOST", "mysql")
	t.Setenv("DB_PASSWORD", "")
	t.Setenv("DB_PASSWORD_FILE", secret.Name())
	c := Load()
	if c.Port != "9091" || c.DBHost != "mysql" || c.DBPassword != "clave-segura" {
		t.Fatalf("config inesperada: %#v", c)
	}
	t.Setenv("DB_PASSWORD", "directa")
	if got := Load().DBPassword; got != "directa" {
		t.Fatalf("la variable directa debe ganar: %q", got)
	}
}

func TestSecretFileMissingPanics(t *testing.T) {
	t.Setenv("DB_PASSWORD", "")
	t.Setenv("DB_PASSWORD_FILE", t.TempDir()+"/no-existe")
	defer func() {
		if recover() == nil {
			t.Fatal("se esperaba panic")
		}
	}()
	_ = Load()
}

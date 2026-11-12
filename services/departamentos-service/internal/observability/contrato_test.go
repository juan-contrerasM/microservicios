package observability

import (
	"bytes"
	"encoding/json"
	"log/slog"
	"strings"
	"testing"

	"github.com/prometheus/client_golang/prometheus"
	dto "github.com/prometheus/client_model/go"
)

func TestContratoC5RenombraTimeYMsg(t *testing.T) {
	var salida bytes.Buffer
	logger := slog.New(slog.NewJSONHandler(&salida, &slog.HandlerOptions{ReplaceAttr: contratoC5})).
		With("service", "departamentos-service")
	logger.WithGroup("http").Info("petición HTTP", "time", "dentro-de-grupo")

	var linea map[string]any
	if err := json.Unmarshal(salida.Bytes(), &linea); err != nil {
		t.Fatalf("no es JSON: %v", err)
	}
	for _, clave := range []string{"timestamp", "level", "service", "message"} {
		if _, ok := linea[clave]; !ok {
			t.Fatalf("falta %q en %s", clave, salida.String())
		}
	}
	if _, ok := linea["msg"]; ok {
		t.Fatalf("msg no se renombró: %s", salida.String())
	}
	// Dentro de un grupo las claves no son las del registro: no se tocan.
	if !strings.Contains(salida.String(), `"http":{"time":"dentro-de-grupo"}`) {
		t.Fatalf("se renombró una clave de un grupo: %s", salida.String())
	}
}

func TestContadoresDeNegocio(t *testing.T) {
	antesCreados, antesConsultas := valor(t, creados), valor(t, consultas)
	RegistrarCreado()
	RegistrarConsulta()
	RegistrarConsulta()
	if got := valor(t, creados) - antesCreados; got != 1 {
		t.Fatalf("departamentos_creados_total aumentó %v", got)
	}
	if got := valor(t, consultas) - antesConsultas; got != 2 {
		t.Fatalf("departamentos_consultas_total aumentó %v", got)
	}
}

func valor(t *testing.T, c prometheus.Counter) float64 {
	t.Helper()
	var m dto.Metric
	if err := c.Write(&m); err != nil {
		t.Fatal(err)
	}
	return m.GetCounter().GetValue()
}

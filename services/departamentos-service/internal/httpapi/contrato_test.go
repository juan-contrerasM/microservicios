package httpapi

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"go.opentelemetry.io/otel"
	"go.opentelemetry.io/otel/propagation"
	sdktrace "go.opentelemetry.io/otel/sdk/trace"

	"departamentos-service/internal/department"
	"departamentos-service/internal/observability"
)

// repoFalso implementa el repositorio que department.Service necesita sin
// MySQL. err, si no es nil, se devuelve en todas las operaciones.
type repoFalso struct {
	items map[string]department.Department
	err   error
}

func (f *repoFalso) Create(_ context.Context, d department.Department) (department.Department, error) {
	if f.err != nil {
		return department.Department{}, f.err
	}
	if _, existe := f.items[d.ID]; existe {
		return department.Department{}, department.ErrAlreadyExists
	}
	f.items[d.ID] = d
	return d, nil
}

func (f *repoFalso) GetByID(_ context.Context, id string) (department.Department, error) {
	if f.err != nil {
		return department.Department{}, f.err
	}
	d, existe := f.items[id]
	if !existe {
		return department.Department{}, department.ErrNotFound
	}
	return d, nil
}

func (f *repoFalso) List(context.Context) ([]department.Department, error) {
	if f.err != nil {
		return nil, f.err
	}
	return []department.Department{}, nil
}

func (f *repoFalso) Update(_ context.Context, d department.Department) (department.Department, error) {
	if f.err != nil {
		return department.Department{}, f.err
	}
	if _, existe := f.items[d.ID]; !existe {
		return department.Department{}, department.ErrNotFound
	}
	f.items[d.ID] = d
	return d, nil
}

func (f *repoFalso) Delete(_ context.Context, id string) error {
	if f.err != nil {
		return f.err
	}
	if _, existe := f.items[id]; !existe {
		return department.ErrNotFound
	}
	delete(f.items, id)
	return nil
}

// pingerFalso simula MySQL: devuelve err, o se cuelga sin respetar el contexto
// cuando colgado es true (el peor caso: un servidor que acepta TCP y no responde).
type pingerFalso struct {
	err     error
	colgado bool
}

func (p pingerFalso) PingContext(context.Context) error {
	if p.colgado {
		time.Sleep(30 * time.Second)
	}
	return p.err
}

func servidor(repo *repoFalso, db pinger) http.Handler {
	return rutas(NewDepartmentHandler(department.NewService(repo)), NewHealthHandler(db))
}

func repoConIT() *repoFalso {
	return &repoFalso{items: map[string]department.Department{"IT": {ID: "IT", Nombre: "Tecnología"}}}
}

func pedir(t *testing.T, h http.Handler, metodo, ruta, cuerpo string) *httptest.ResponseRecorder {
	t.Helper()
	req := httptest.NewRequest(metodo, ruta, strings.NewReader(cuerpo))
	if cuerpo != "" {
		req.Header.Set("Content-Type", "application/json")
	}
	rec := httptest.NewRecorder()
	h.ServeHTTP(rec, req)
	return rec
}

// assertC3 verifica el contrato C3: los cinco campos obligatorios, el código y
// que no se filtren campos ajenos al contrato (como el antiguo "mensaje").
func assertC3(t *testing.T, rec *httptest.ResponseRecorder, status int, path string) map[string]any {
	t.Helper()
	if rec.Code != status {
		t.Fatalf("código = %d, se esperaba %d; cuerpo: %s", rec.Code, status, rec.Body.String())
	}
	if ct := rec.Header().Get("Content-Type"); !strings.HasPrefix(ct, "application/json") {
		t.Fatalf("Content-Type = %q", ct)
	}
	var body map[string]any
	if err := json.Unmarshal(rec.Body.Bytes(), &body); err != nil {
		t.Fatalf("el cuerpo no es JSON: %v", err)
	}
	for campo := range body {
		switch campo {
		case "timestamp", "status", "error", "message", "path", "traceId":
		default:
			t.Errorf("campo fuera del contrato C3: %q", campo)
		}
	}
	if _, err := time.Parse(time.RFC3339Nano, fmt.Sprint(body["timestamp"])); err != nil {
		t.Errorf("timestamp no es RFC 3339: %v", body["timestamp"])
	}
	if body["status"] != float64(status) {
		t.Errorf("status = %v", body["status"])
	}
	if body["error"] != http.StatusText(status) {
		t.Errorf("error = %v, se esperaba %q", body["error"], http.StatusText(status))
	}
	if msg, _ := body["message"].(string); msg == "" {
		t.Error("message vacío")
	}
	if body["path"] != path {
		t.Errorf("path = %v, se esperaba %q", body["path"], path)
	}
	return body
}

func TestErroresSiguenElContratoC3(t *testing.T) {
	casos := []struct {
		nombre, metodo, ruta, cuerpo string
		status                       int
		mensaje                      string
	}{
		{"inexistente", http.MethodGet, "/departamentos/XX", "", http.StatusNotFound, "el departamento con id XX no existe"},
		{"json mal formado", http.MethodPost, "/departamentos", "{ no es json", http.StatusBadRequest, "cuerpo de la petición inválido"},
		{"faltan campos", http.MethodPost, "/departamentos", `{"id":"RRHH"}`, http.StatusBadRequest, "el departamento requiere al menos 'id' y 'nombre'"},
		{"id duplicado", http.MethodPost, "/departamentos", `{"id":"IT","nombre":"Otro"}`, http.StatusConflict, "ya existe un departamento registrado con ese id"},
		{"put sin nombre", http.MethodPut, "/departamentos/IT", `{"descripcion":"x"}`, http.StatusBadRequest, "el departamento requiere 'nombre'"},
		{"put inexistente", http.MethodPut, "/departamentos/XX", `{"nombre":"x"}`, http.StatusNotFound, "el departamento con id XX no existe"},
		{"delete inexistente", http.MethodDelete, "/departamentos/XX", "", http.StatusNotFound, "el departamento con id XX no existe"},
		{"ruta inexistente", http.MethodGet, "/no-existe", "", http.StatusNotFound, "recurso no encontrado"},
		{"método no soportado", http.MethodPatch, "/departamentos/IT", "", http.StatusMethodNotAllowed, "método no permitido para este recurso"},
	}
	for _, c := range casos {
		t.Run(c.nombre, func(t *testing.T) {
			rec := pedir(t, servidor(repoConIT(), pingerFalso{}), c.metodo, c.ruta, c.cuerpo)
			body := assertC3(t, rec, c.status, c.ruta)
			if body["message"] != c.mensaje {
				t.Errorf("message = %v, se esperaba %q", body["message"], c.mensaje)
			}
		})
	}
}

func TestMySQLCaidoResponde503SinDetallesInternos(t *testing.T) {
	caida := fmt.Errorf("consultando departamento IT: %w",
		&net.OpError{Op: "dial", Net: "tcp", Err: errors.New("connect: connection refused 10.0.0.7:3306")})
	repo := &repoFalso{items: map[string]department.Department{}, err: caida}

	rec := pedir(t, servidor(repo, pingerFalso{}), http.MethodGet, "/departamentos/IT", "")

	body := assertC3(t, rec, http.StatusServiceUnavailable, "/departamentos/IT")
	if body["message"] != mensajeBDNoDisponible {
		t.Errorf("message = %v", body["message"])
	}
	if strings.Contains(rec.Body.String(), "10.0.0.7") || strings.Contains(rec.Body.String(), "dial") {
		t.Errorf("la respuesta filtra detalles internos: %s", rec.Body.String())
	}
}

func TestErrorInesperadoResponde500Generico(t *testing.T) {
	repo := &repoFalso{items: map[string]department.Department{}, err: errors.New("Error 1146: Table 'departamentos' doesn't exist")}

	rec := pedir(t, servidor(repo, pingerFalso{}), http.MethodGet, "/departamentos", "")

	body := assertC3(t, rec, http.StatusInternalServerError, "/departamentos")
	if body["message"] != mensajeErrorInterno || strings.Contains(rec.Body.String(), "1146") {
		t.Errorf("la respuesta filtra detalles internos: %s", rec.Body.String())
	}
}

func TestPanicResponde500ConContratoC3(t *testing.T) {
	h := recoverer(http.HandlerFunc(func(http.ResponseWriter, *http.Request) { panic("nil map en handler.go:42") }))

	rec := pedir(t, h, http.MethodGet, "/departamentos", "")

	assertC3(t, rec, http.StatusInternalServerError, "/departamentos")
	if strings.Contains(rec.Body.String(), "handler.go") {
		t.Errorf("la respuesta filtra el panic: %s", rec.Body.String())
	}
}

func TestTraceIDSaleDelSpanDeOpenTelemetry(t *testing.T) {
	const traceID = "4bf92f3577b34da6a3ce929d0e0e4736"
	// Mismo envoltorio que en producción (otelhttp) con un proveedor que muestrea.
	proveedor := sdktrace.NewTracerProvider(sdktrace.WithSampler(sdktrace.AlwaysSample()))
	defer func() { _ = proveedor.Shutdown(context.Background()) }()
	proveedorAnterior, propagadorAnterior := otel.GetTracerProvider(), otel.GetTextMapPropagator()
	otel.SetTracerProvider(proveedor)
	otel.SetTextMapPropagator(propagation.TraceContext{})
	defer func() {
		otel.SetTracerProvider(proveedorAnterior)
		otel.SetTextMapPropagator(propagadorAnterior)
	}()

	h := observability.Handler(servidor(repoConIT(), pingerFalso{}))
	req := httptest.NewRequest(http.MethodGet, "/departamentos/XX", nil)
	req.Header.Set("traceparent", "00-"+traceID+"-00f067aa0ba902b7-01")
	rec := httptest.NewRecorder()
	h.ServeHTTP(rec, req)

	body := assertC3(t, rec, http.StatusNotFound, "/departamentos/XX")
	if body["traceId"] != traceID {
		t.Errorf("traceId = %v, se esperaba %s", body["traceId"], traceID)
	}
}

func TestTraceIDSeOmiteSinTraza(t *testing.T) {
	rec := pedir(t, servidor(repoConIT(), pingerFalso{}), http.MethodGet, "/departamentos/XX", "")

	body := assertC3(t, rec, http.StatusNotFound, "/departamentos/XX")
	if _, existe := body["traceId"]; existe {
		t.Errorf("traceId debería omitirse sin traza: %v", body["traceId"])
	}
}

func leerHealth(t *testing.T, rec *httptest.ResponseRecorder) healthResponse {
	t.Helper()
	var body healthResponse
	if err := json.Unmarshal(rec.Body.Bytes(), &body); err != nil {
		t.Fatalf("health no devolvió JSON: %v", err)
	}
	return body
}

func TestHealthUPSigueElContratoC2(t *testing.T) {
	rec := pedir(t, servidor(repoConIT(), pingerFalso{}), http.MethodGet, "/health", "")

	if rec.Code != http.StatusOK {
		t.Fatalf("código = %d", rec.Code)
	}
	body := leerHealth(t, rec)
	if body.Status != "UP" || body.Service != "departamentos-service" || body.Version != Version {
		t.Errorf("cuerpo = %+v", body)
	}
	if len(body.Checks) != 1 || body.Checks["database"] != "UP" {
		t.Errorf("checks = %v (solo debe reportar dependencias reales)", body.Checks)
	}
}

func TestHealthDownCuandoMySQLRechazaLaConexion(t *testing.T) {
	rec := pedir(t, servidor(repoConIT(), pingerFalso{err: errors.New("connection refused")}), http.MethodGet, "/health", "")

	if rec.Code != http.StatusServiceUnavailable {
		t.Fatalf("código = %d", rec.Code)
	}
	if body := leerHealth(t, rec); body.Status != "DOWN" || body.Checks["database"] != "DOWN" {
		t.Errorf("cuerpo = %+v", body)
	}
}

func TestHealthResponde503EnMenosDeTresSegundosConMySQLColgado(t *testing.T) {
	inicio := time.Now()
	rec := pedir(t, servidor(repoConIT(), pingerFalso{colgado: true}), http.MethodGet, "/health", "")
	transcurrido := time.Since(inicio)

	if transcurrido >= 3*time.Second {
		t.Fatalf("health tardó %v; el contrato C2 exige < 3 s", transcurrido)
	}
	if rec.Code != http.StatusServiceUnavailable {
		t.Fatalf("código = %d", rec.Code)
	}
	if body := leerHealth(t, rec); body.Checks["database"] != "DOWN" {
		t.Errorf("cuerpo = %+v", body)
	}
}

func TestHealthUsaServiceVersionSiExiste(t *testing.T) {
	t.Setenv("SERVICE_VERSION", "2026.11.0")

	if h := NewHealthHandler(pingerFalso{}); h.version != "2026.11.0" {
		t.Errorf("version = %q", h.version)
	}
}

package observability

import (
	"context"
	"log/slog"
	"net/http"
	"os"
	"strconv"
	"strings"
	"time"

	"github.com/prometheus/client_golang/prometheus"
	"github.com/prometheus/client_golang/prometheus/promhttp"
	"go.opentelemetry.io/contrib/instrumentation/net/http/otelhttp"
	"go.opentelemetry.io/otel"
	"go.opentelemetry.io/otel/exporters/zipkin"
	"go.opentelemetry.io/otel/propagation"
	"go.opentelemetry.io/otel/sdk/resource"
	sdktrace "go.opentelemetry.io/otel/sdk/trace"
	semconv "go.opentelemetry.io/otel/semconv/v1.37.0"
	"go.opentelemetry.io/otel/trace"
)

var (
	requests = prometheus.NewCounterVec(prometheus.CounterOpts{
		Name: "http_requests_total", Help: "Peticiones HTTP recibidas.",
	}, []string{"service", "method", "route", "status"})
	duration = prometheus.NewHistogramVec(prometheus.HistogramOpts{
		Name: "http_request_duration_seconds", Help: "Duración HTTP en segundos.",
		Buckets: []float64{.005, .01, .025, .05, .1, .25, .5, 1, 2, 5, 10},
	}, []string{"service", "method", "route", "status"})
)

func init() { prometheus.MustRegister(requests, duration) }

func Setup(ctx context.Context) (func(context.Context) error, error) {
	service := getenv("OTEL_SERVICE_NAME", "departamentos-service")
	slog.SetDefault(slog.New(slog.NewJSONHandler(os.Stdout, nil)).With("service", service))
	exporter, err := zipkin.New(getenv("OTEL_EXPORTER_ZIPKIN_ENDPOINT", "http://zipkin:9411/api/v2/spans"))
	if err != nil {
		return nil, err
	}
	res, err := resource.New(ctx, resource.WithAttributes(semconv.ServiceName(service)))
	if err != nil {
		return nil, err
	}
	provider := sdktrace.NewTracerProvider(
		sdktrace.WithBatcher(exporter), sdktrace.WithResource(res), sdktrace.WithSampler(sdktrace.AlwaysSample()),
	)
	otel.SetTracerProvider(provider)
	otel.SetTextMapPropagator(propagation.NewCompositeTextMapPropagator(propagation.TraceContext{}, propagation.Baggage{}))
	return provider.Shutdown, nil
}

func Handler(next http.Handler) http.Handler {
	return otelhttp.NewHandler(metrics(next), "departamentos.http")
}
func MetricsHandler() http.Handler { return promhttp.Handler() }

type recorder struct {
	http.ResponseWriter
	status int
}

func (r *recorder) WriteHeader(status int) { r.status = status; r.ResponseWriter.WriteHeader(status) }

func metrics(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path == "/metrics" {
			next.ServeHTTP(w, r)
			return
		}
		start := time.Now()
		rw := &recorder{ResponseWriter: w, status: http.StatusOK}
		next.ServeHTTP(rw, r)
		labels := []string{"departamentos-service", r.Method, normalizedRoute(r.URL.Path), strconv.Itoa(rw.status)}
		requests.WithLabelValues(labels...).Inc()
		duration.WithLabelValues(labels...).Observe(time.Since(start).Seconds())
		fields := []any{"method", r.Method, "route", labels[2], "status", rw.status, "durationMs", time.Since(start).Milliseconds()}
		spanContext := trace.SpanContextFromContext(r.Context())
		if spanContext.IsValid() {
			fields = append(fields, "traceId", spanContext.TraceID().String(), "spanId", spanContext.SpanID().String())
		}
		slog.Info("petición HTTP", fields...)
	})
}

func normalizedRoute(path string) string {
	if strings.HasPrefix(path, "/departamentos/") {
		return "/departamentos/{id}"
	}
	return path
}
func getenv(name, fallback string) string {
	if value := os.Getenv(name); value != "" {
		return value
	}
	return fallback
}

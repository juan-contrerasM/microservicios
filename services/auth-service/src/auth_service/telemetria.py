import json
import logging
import os
from datetime import datetime, timezone

from opentelemetry import trace
from opentelemetry.exporter.zipkin.json import ZipkinExporter
from opentelemetry.instrumentation.fastapi import FastAPIInstrumentor
from opentelemetry.instrumentation.logging import LoggingInstrumentor
from opentelemetry.instrumentation.pika import PikaInstrumentor
from opentelemetry.instrumentation.sqlalchemy import SQLAlchemyInstrumentor
from opentelemetry.sdk.resources import Resource
from opentelemetry.sdk.trace import TracerProvider
from opentelemetry.sdk.trace.export import BatchSpanProcessor
from prometheus_fastapi_instrumentator import Instrumentator


class JsonFormatter(logging.Formatter):
    def format(self, record):
        payload = {
            "timestamp": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
            "level": record.levelname,
            "service": os.getenv("OTEL_SERVICE_NAME", "auth-service"),
            "message": record.getMessage(),
        }
        trace_id = getattr(record, "otelTraceID", "0")
        span_id = getattr(record, "otelSpanID", "0")
        if trace_id and trace_id != "0":
            payload["traceId"] = trace_id
            payload["spanId"] = span_id
        if record.exc_info:
            payload["exception"] = self.formatException(record.exc_info)
        return json.dumps(payload, ensure_ascii=False)


def configurar():
    service = os.getenv("OTEL_SERVICE_NAME", "auth-service")
    provider = TracerProvider(resource=Resource.create({"service.name": service}))
    if os.getenv("OTEL_SERVICE_NAME"):
        exporter = ZipkinExporter(
            endpoint=os.getenv("OTEL_EXPORTER_ZIPKIN_ENDPOINT", "http://zipkin:9411/api/v2/spans")
        )
        provider.add_span_processor(BatchSpanProcessor(exporter))
    trace.set_tracer_provider(provider)
    LoggingInstrumentor().instrument(set_logging_format=False)
    PikaInstrumentor().instrument()
    SQLAlchemyInstrumentor().instrument()

    handler = logging.StreamHandler()
    handler.setFormatter(JsonFormatter())
    root = logging.getLogger()
    root.handlers.clear()
    root.addHandler(handler)
    root.setLevel(logging.INFO)
    logging.getLogger("pika").setLevel(logging.WARNING)


def instrumentar_app(app):
    FastAPIInstrumentor.instrument_app(app, excluded_urls="health,metrics")
    Instrumentator(excluded_handlers=["/metrics"]).instrument(app).expose(
        app, endpoint="/metrics", include_in_schema=False
    )

"""Formato de error HTTP uniforme (contrato C3 del proyecto final).

Todas las respuestas de error del servicio salen con el mismo cuerpo:
``{"timestamp","status","error","message","path","traceId"}``. Nunca se
devuelven trazas de pila ni detalles internos: el detalle técnico de un 500
queda solo en el log (con su traceId para correlacionar).
"""

import logging
import re
from datetime import datetime, timezone
from http import HTTPStatus

from fastapi import FastAPI, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from opentelemetry import trace
from pydantic import BaseModel, ConfigDict
from sqlalchemy import exc as sa_exc
from starlette.exceptions import HTTPException as StarletteHTTPException

logger = logging.getLogger("notificaciones")

_TRACEPARENT = re.compile(r"^[0-9a-f]{2}-([0-9a-f]{32})-[0-9a-f]{16}-[0-9a-f]{2}$")


class ErrorRespuesta(BaseModel):
    """Cuerpo de error común a los siete servicios."""

    model_config = ConfigDict(
        json_schema_extra={
            "examples": [
                {
                    "timestamp": "2026-11-09T12:00:00.000Z",
                    "status": 404,
                    "error": "Not Found",
                    "message": "Recurso no encontrado",
                    "path": "/notificaciones/E001/x",
                    "traceId": "4bf92f3577b34da6a3ce929d0e0e4736",
                }
            ]
        }
    )

    timestamp: str
    status: int
    error: str
    message: str
    path: str
    traceId: str | None = None


def _ejemplo(status: int, message: str, path: str) -> dict:
    return {
        "timestamp": "2026-11-09T12:00:00.000Z",
        "status": status,
        "error": HTTPStatus(status).phrase,
        "message": message,
        "path": path,
        "traceId": "4bf92f3577b34da6a3ce929d0e0e4736",
    }


def respuesta_error(status: int, descripcion: str, message: str, path: str) -> dict:
    """Entrada de ``responses=`` de FastAPI con el esquema C3 y un ejemplo."""
    return {
        "model": ErrorRespuesta,
        "description": descripcion,
        "content": {"application/json": {"example": _ejemplo(status, message, path)}},
    }


def trace_id_actual(request: Request | None = None) -> str | None:
    """traceId del span activo de OpenTelemetry; si no hay, el de ``traceparent``."""
    contexto = trace.get_current_span().get_span_context()
    if contexto.is_valid:
        return trace.format_trace_id(contexto.trace_id)
    if request is not None:
        coincidencia = _TRACEPARENT.match(request.headers.get("traceparent", "").strip().lower())
        if coincidencia:
            return coincidencia.group(1)
    return None


def cuerpo_error(status: int, message: str, request: Request) -> dict:
    try:
        error = HTTPStatus(status).phrase
    except ValueError:
        error = "Error"
    return {
        "timestamp": datetime.now(timezone.utc).isoformat(timespec="milliseconds").replace("+00:00", "Z"),
        "status": status,
        "error": error,
        "message": message,
        "path": request.url.path,
        "traceId": trace_id_actual(request),
    }


def error_json(status: int, message: str, request: Request, headers: dict | None = None) -> JSONResponse:
    return JSONResponse(status_code=status, content=cuerpo_error(status, message, request), headers=headers)


_MENSAJES_POR_DEFECTO = {
    401: "No autenticado",
    403: "No tiene permisos para realizar esta operación",
    404: "Recurso no encontrado",
    405: "Método no permitido",
}


def _describir_validacion(exc: RequestValidationError) -> str:
    partes = []
    for error in exc.errors():
        ubicacion = ".".join(str(p) for p in error.get("loc", ()) if p != "body")
        if error.get("type") == "json_invalid":
            return "El cuerpo de la petición no es JSON válido"
        if error.get("type") == "missing":
            partes.append(f"{ubicacion}: es obligatorio")
        else:
            partes.append(f"{ubicacion}: valor inválido" if ubicacion else "valor inválido")
    return "Petición inválida: " + "; ".join(partes) if partes else "Petición inválida"


def instalar_manejadores(app: FastAPI) -> None:
    """Registra los manejadores que traducen toda excepción al contrato C3."""

    @app.exception_handler(StarletteHTTPException)
    async def _http(request: Request, exc: StarletteHTTPException):
        detalle = exc.detail if isinstance(exc.detail, str) and exc.detail else None
        if detalle is None or detalle == HTTPStatus(exc.status_code).phrase:
            detalle = _MENSAJES_POR_DEFECTO.get(exc.status_code, HTTPStatus(exc.status_code).phrase)
        return error_json(exc.status_code, detalle, request, getattr(exc, "headers", None))

    @app.exception_handler(RequestValidationError)
    async def _validacion(request: Request, exc: RequestValidationError):
        # FastAPI respondería 422; el contrato C3 fija 400 para toda validación.
        return error_json(400, _describir_validacion(exc), request)

    @app.exception_handler(sa_exc.OperationalError)
    @app.exception_handler(sa_exc.InterfaceError)
    @app.exception_handler(sa_exc.TimeoutError)
    async def _base_caida(request: Request, exc: Exception):
        # Dependencia caída: 503 sin exponer el mensaje del driver (host, usuario, SQL).
        logger.error("Base de datos no disponible en %s %s: %s", request.method, request.url.path, type(exc).__name__)
        return error_json(503, "La base de datos no está disponible", request)

    @app.exception_handler(Exception)
    async def _inesperado(request: Request, exc: Exception):
        logger.error("Error no controlado en %s %s", request.method, request.url.path, exc_info=exc)
        return error_json(500, "Error interno del servidor", request)


def ajustar_openapi(app: FastAPI) -> None:
    """Quita del contrato el 422 que FastAPI documenta por defecto.

    El manejador de ``RequestValidationError`` responde 400 (C3), así que dejar
    el 422 en el OpenAPI documentaría un código que el servicio nunca devuelve.
    """
    generar = app.openapi

    def openapi():
        if app.openapi_schema:
            return app.openapi_schema
        esquema = generar()
        for operaciones in esquema.get("paths", {}).values():
            for operacion in operaciones.values():
                operacion.get("responses", {}).pop("422", None)
        esquemas = esquema.get("components", {}).get("schemas", {})
        esquemas.pop("HTTPValidationError", None)
        esquemas.pop("ValidationError", None)
        app.openapi_schema = esquema
        return esquema

    app.openapi = openapi

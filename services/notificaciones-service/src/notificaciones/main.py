import logging
from contextlib import asynccontextmanager
from datetime import datetime

from notificaciones.telemetria import configurar, instrumentar_app

configurar()

from fastapi import Depends, FastAPI, Security
from fastapi.responses import JSONResponse, RedirectResponse
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer
from pydantic import BaseModel, ConfigDict, Field
from sqlalchemy.orm import Session

from notificaciones import __version__
from notificaciones.config import load_settings
from notificaciones.consumidor import EstadoConsumidor, iniciar_consumidor
from notificaciones.db import SqlRepositorio, base_responde, crear_motor, crear_sesiones
from notificaciones.errores import ajustar_openapi, instalar_manejadores, respuesta_error
from notificaciones.salud import con_timeout

SERVICIO = "notificaciones-service"
logger = logging.getLogger("notificaciones")
settings = load_settings()
motor = crear_motor(settings.database_url)
sesiones = crear_sesiones(motor)
estado_consumidor = EstadoConsumidor()
bearer_auth = HTTPBearer(
    auto_error=False,
    scheme_name="BearerAuth",
    description="Access JWT validado por el API Gateway",
)


class NotificacionRespuesta(BaseModel):
    model_config = ConfigDict(
        from_attributes=True,
        ser_json_by_alias=True,
        json_schema_extra={
            "examples": [
                {
                    "id": "0b8f6c1e-3c1a-4f7e-9d2a-2f0c5d1e7a10",
                    "tipo": "VACACIONES",
                    "destinatario": "ana.perez@empresa.com",
                    "mensaje": "Sus vacaciones del 2027-03-15 al 2027-03-30 han sido programadas",
                    "fechaEnvio": "2026-11-09T12:00:01Z",
                    "empleadoId": "E001",
                }
            ]
        },
    )

    id: str
    tipo: str = Field(description="Tipo definido por la plantilla del evento: SEGURIDAD, CUENTA o VACACIONES")
    destinatario: str
    mensaje: str
    fecha_envio: datetime = Field(serialization_alias="fechaEnvio")
    empleado_id: str = Field(serialization_alias="empleadoId")


class ChecksSalud(BaseModel):
    database: str = Field(examples=["UP"])
    messageBroker: str = Field(examples=["UP"])


class HealthRespuesta(BaseModel):
    model_config = ConfigDict(
        json_schema_extra={
            "examples": [
                {
                    "status": "UP",
                    "service": SERVICIO,
                    "version": __version__,
                    "checks": {"database": "UP", "messageBroker": "UP"},
                }
            ]
        }
    )

    status: str
    service: str
    version: str
    checks: ChecksSalud


def _errores_consulta(path: str) -> dict:
    return {
        401: respuesta_error(
            401,
            "Sin token o token inválido. Lo responde el API Gateway antes de llegar al servicio",
            "No autenticado",
            path,
        ),
        500: respuesta_error(500, "Error inesperado", "Error interno del servidor", path),
        503: respuesta_error(503, "PostgreSQL no disponible", "La base de datos no está disponible", path),
    }


@asynccontextmanager
async def lifespan(_app: FastAPI):
    detener = None
    if not settings.consumer_disabled:
        detener = iniciar_consumidor(settings, sesiones, estado_consumidor)
    yield
    if detener is not None:
        detener.set()


app = FastAPI(
    title=SERVICIO,
    description=(
        "Historial de notificaciones del onboarding. Consume la cola q.notificaciones y expone consultas "
        "de solo lectura. Los errores siguen el contrato C3 (timestamp, status, error, message, path, traceId)."
    ),
    version=__version__,
    lifespan=lifespan,
)
instalar_manejadores(app)


def obtener_sesion():
    sesion = sesiones()
    try:
        yield sesion
    finally:
        sesion.close()


@app.get("/swagger", include_in_schema=False)
@app.get("/swagger/", include_in_schema=False)
def swagger():
    return RedirectResponse("/docs")


@app.get(
    "/health",
    tags=["Salud"],
    summary="Health check contra PostgreSQL y el consumidor de RabbitMQ",
    description=(
        "200 si PostgreSQL responde y el consumidor está conectado; 503 si alguno está DOWN. "
        "El check de base tiene un tiempo máximo, así que responde en menos de 3 s aunque la base cuelgue."
    ),
    response_model=HealthRespuesta,
    responses={
        503: {
            "model": HealthRespuesta,
            "description": "Alguna dependencia está DOWN",
            "content": {
                "application/json": {
                    "example": {
                        "status": "DOWN",
                        "service": SERVICIO,
                        "version": __version__,
                        "checks": {"database": "DOWN", "messageBroker": "UP"},
                    }
                }
            },
        }
    },
)
def health():
    database_ok = con_timeout(lambda: base_responde(motor), settings.health_timeout_seconds, False)
    broker_ok = settings.consumer_disabled or estado_consumidor.conectado
    checks = {"database": "UP" if database_ok else "DOWN", "messageBroker": "UP" if broker_ok else "DOWN"}
    sano = database_ok and broker_ok
    cuerpo = {"status": "UP" if sano else "DOWN", "service": SERVICIO, "version": __version__, "checks": checks}
    return cuerpo if sano else JSONResponse(status_code=503, content=cuerpo)


@app.get(
    "/notificaciones",
    tags=["Notificaciones"],
    response_model=list[NotificacionRespuesta],
    summary="Listar notificaciones. Arreglo vacío si no hay.",
    responses=_errores_consulta("/notificaciones"),
)
def listar(
    sesion: Session = Depends(obtener_sesion),
    _credenciales: HTTPAuthorizationCredentials | None = Security(bearer_auth),
):
    return SqlRepositorio(sesion).listar()


@app.get(
    "/notificaciones/{empleado_id}",
    tags=["Notificaciones"],
    response_model=list[NotificacionRespuesta],
    summary="Listar las notificaciones de un empleado. Vacío no es 404.",
    responses=_errores_consulta("/notificaciones/E001"),
)
def listar_de_empleado(
    empleado_id: str,
    sesion: Session = Depends(obtener_sesion),
    _credenciales: HTTPAuthorizationCredentials | None = Security(bearer_auth),
):
    return SqlRepositorio(sesion).listar(empleado_id)


instrumentar_app(app)
ajustar_openapi(app)

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

from notificaciones.config import load_settings
from notificaciones.consumidor import EstadoConsumidor, iniciar_consumidor
from notificaciones.db import SqlRepositorio, base_responde, crear_motor, crear_sesiones

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
    model_config = ConfigDict(from_attributes=True, ser_json_by_alias=True)

    id: str
    tipo: str
    destinatario: str
    mensaje: str
    fecha_envio: datetime = Field(serialization_alias="fechaEnvio")
    empleado_id: str = Field(serialization_alias="empleadoId")


@asynccontextmanager
async def lifespan(_app: FastAPI):
    detener = None
    if not settings.consumer_disabled:
        detener = iniciar_consumidor(settings, sesiones, estado_consumidor)
    yield
    if detener is not None:
        detener.set()


app = FastAPI(
    title="notificaciones-service",
    description="Historial de notificaciones del onboarding. Consume la cola q.notificaciones.",
    version="1.0.0",
    lifespan=lifespan,
)


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
    summary="Health check contra PostgreSQL",
    responses={
        200: {"description": "UP"},
        503: {"description": "DOWN"},
    },
)
def health():
    database_ok = base_responde(motor)
    broker_ok = settings.consumer_disabled or estado_consumidor.conectado
    checks = {"database": "UP" if database_ok else "DOWN", "messageBroker": "UP" if broker_ok else "DOWN"}
    if database_ok and broker_ok:
        return {"status": "UP", "service": "notificaciones-service", "checks": checks}
    return JSONResponse(
        status_code=503,
        content={"status": "DOWN", "service": "notificaciones-service", "checks": checks},
    )


@app.get(
    "/notificaciones",
    response_model=list[NotificacionRespuesta],
    summary="Listar notificaciones. Arreglo vacío si no hay.",
)
def listar(
    sesion: Session = Depends(obtener_sesion),
    _credenciales: HTTPAuthorizationCredentials | None = Security(bearer_auth),
):
    return SqlRepositorio(sesion).listar()


@app.get(
    "/notificaciones/{empleado_id}",
    response_model=list[NotificacionRespuesta],
    summary="Listar las notificaciones de un empleado. Vacío no es 404.",
)
def listar_de_empleado(
    empleado_id: str,
    sesion: Session = Depends(obtener_sesion),
    _credenciales: HTTPAuthorizationCredentials | None = Security(bearer_auth),
):
    return SqlRepositorio(sesion).listar(empleado_id)


instrumentar_app(app)

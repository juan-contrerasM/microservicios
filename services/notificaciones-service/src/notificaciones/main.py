import logging
from contextlib import asynccontextmanager
from datetime import datetime

logging.basicConfig(level=logging.INFO, format="%(levelname)s %(name)s %(message)s")
logging.getLogger("notificaciones").setLevel(logging.INFO)

from fastapi import Depends, FastAPI
from fastapi.responses import JSONResponse
from pydantic import BaseModel, ConfigDict, Field
from sqlalchemy.orm import Session

from notificaciones.config import load_settings
from notificaciones.consumidor import iniciar_consumidor
from notificaciones.db import SqlRepositorio, base_responde, crear_motor, crear_sesiones

logging.getLogger("pika").setLevel(logging.WARNING)

settings = load_settings()
motor = crear_motor(settings.database_url)
sesiones = crear_sesiones(motor)


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
        detener = iniciar_consumidor(settings, sesiones)
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


@app.get("/health")
def health():
    if base_responde(motor):
        return {"status": "UP", "service": "notificaciones-service"}
    return JSONResponse(
        status_code=503,
        content={"status": "DOWN", "service": "notificaciones-service"},
    )


@app.get("/notificaciones", response_model=list[NotificacionRespuesta])
def listar(sesion: Session = Depends(obtener_sesion)):
    return SqlRepositorio(sesion).listar()


@app.get("/notificaciones/{empleado_id}", response_model=list[NotificacionRespuesta])
def listar_de_empleado(empleado_id: str, sesion: Session = Depends(obtener_sesion)):
    return SqlRepositorio(sesion).listar(empleado_id)

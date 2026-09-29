import logging
import uuid
from datetime import datetime, timezone
from enum import Enum

from notificaciones.modelos import Destinatario, Notificacion

logger = logging.getLogger("notificaciones")

TIPO_BIENVENIDA = "BIENVENIDA"
TIPO_DESVINCULACION = "DESVINCULACION"
TIPO_VACACIONES = "VACACIONES"


class Resultado(Enum):
    PROCESADO = "procesado"
    DUPLICADO = "duplicado"
    SIN_DESTINATARIO = "sin_destinatario"
    IGNORADO = "ignorado"


def procesar(repositorio, envelope: dict, ahora: datetime) -> Resultado:
    event_id = str(envelope.get("id") or "")
    if not event_id:
        logger.error("Evento sin id; se confirma sin efecto")
        return Resultado.IGNORADO
    if repositorio.ya_procesado(event_id):
        return Resultado.DUPLICADO

    tipo = envelope.get("type")
    data = envelope.get("data") or {}
    try:
        if tipo == "empleado.creado":
            _bienvenida(repositorio, data, ahora)
        elif tipo == "empleado.retirado":
            if not _desvinculacion(repositorio, data, ahora):
                repositorio.marcar_procesado(event_id, ahora)
                return Resultado.SIN_DESTINATARIO
        elif tipo == "vacaciones.programadas":
            if not _vacaciones(repositorio, data, ahora):
                repositorio.marcar_procesado(event_id, ahora)
                return Resultado.SIN_DESTINATARIO
        else:
            logger.info("Evento %s ignorado por notificaciones", tipo)
            repositorio.marcar_procesado(event_id, ahora)
            return Resultado.IGNORADO
    except KeyError:
        logger.exception("Evento %s incompleto; se confirma sin fila", event_id)
        repositorio.marcar_procesado(event_id, ahora)
        return Resultado.IGNORADO

    repositorio.marcar_procesado(event_id, ahora)
    return Resultado.PROCESADO


def _bienvenida(repositorio, data: dict, ahora: datetime) -> None:
    empleado_id = data["empleadoId"]
    email = data["email"]
    nombre = data.get("nombre") or ""
    apellido = data.get("apellido") or ""
    repositorio.guardar_destinatario(Destinatario(empleado_id, email, nombre, apellido))
    mensaje = f"Bienvenido {nombre} {apellido}".strip()
    _registrar(repositorio, TIPO_BIENVENIDA, email, mensaje, ahora, empleado_id)


def _desvinculacion(repositorio, data: dict, ahora: datetime) -> bool:
    empleado_id = data.get("empleadoId")
    email = data.get("email") or _email_guardado(repositorio, empleado_id)
    if not email:
        logger.error(
            "empleado.retirado sin destinatario para %s; se confirma sin fila",
            empleado_id,
        )
        return False
    _registrar(
        repositorio,
        TIPO_DESVINCULACION,
        email,
        "Su cuenta ha sido desvinculada",
        ahora,
        empleado_id,
    )
    return True


def _vacaciones(repositorio, data: dict, ahora: datetime) -> bool:
    empleado_id = data.get("empleadoId")
    email = data.get("email") or _email_guardado(repositorio, empleado_id)
    if not email:
        logger.error(
            "vacaciones.programadas sin destinatario para %s; se confirma sin fila",
            empleado_id,
        )
        return False
    inicio = data.get("fechaInicio")
    fin = data.get("fechaFin")
    mensaje = f"Sus vacaciones del {inicio} al {fin} han sido programadas"
    _registrar(repositorio, TIPO_VACACIONES, email, mensaje, ahora, empleado_id)
    return True


def _email_guardado(repositorio, empleado_id: str | None) -> str | None:
    if not empleado_id:
        return None
    guardado = repositorio.buscar_destinatario(empleado_id)
    return guardado.email if guardado else None


def _registrar(repositorio, tipo: str, email: str, mensaje: str, ahora: datetime, empleado_id: str) -> None:
    momento = ahora if ahora.tzinfo else ahora.replace(tzinfo=timezone.utc)
    repositorio.guardar_notificacion(
        Notificacion(
            id=str(uuid.uuid4()),
            tipo=tipo,
            destinatario=email,
            mensaje=mensaje,
            fecha_envio=momento,
            empleado_id=empleado_id,
        )
    )
    logger.info('[NOTIFICACIÓN] Tipo: %s | Para: %s | Mensaje: "%s"', tipo, email, mensaje)

import logging
import uuid
from datetime import datetime, timezone
from enum import Enum

from notificaciones.modelos import Destinatario, Notificacion
from notificaciones.plantillas import renderizar

logger = logging.getLogger("notificaciones")

class Resultado(Enum):
    PROCESADO = "procesado"
    DUPLICADO = "duplicado"
    SIN_DESTINATARIO = "sin_destinatario"
    IGNORADO = "ignorado"


def procesar(repositorio, envelope: dict, ahora: datetime, enviar=None) -> Resultado:
    event_id = str(envelope.get("id") or "")
    if not event_id:
        logger.error("Evento sin id; se confirma sin efecto")
        return Resultado.IGNORADO
    if repositorio.ya_procesado(event_id):
        return Resultado.DUPLICADO

    tipo = envelope.get("type")
    data = envelope.get("data") or {}
    manejador = MANEJADORES.get(tipo)
    if manejador is None:
        logger.info("Evento %s ignorado por notificaciones", tipo)
        resultado = Resultado.IGNORADO
    else:
        try:
            resultado = manejador(repositorio, data, ahora, enviar)
        except KeyError:
            logger.exception("Evento %s incompleto; se confirma sin fila", event_id)
            resultado = Resultado.IGNORADO

    repositorio.marcar_procesado(event_id, ahora)
    return resultado


def _empleado_creado(repositorio, data: dict, _ahora: datetime, _enviar) -> Resultado:
    # El correo de bienvenida sale con usuario.creado, que trae el token. Aquí solo se guarda
    # el destinatario para los eventos que no traen email (usuario.recuperacion trae solo email).
    repositorio.guardar_destinatario(
        Destinatario(
            data["empleadoId"],
            data["email"],
            data.get("nombre") or "",
            data.get("apellido") or "",
        )
    )
    return Resultado.PROCESADO


def _empleado_retirado(_repositorio, data: dict, _ahora: datetime, _enviar) -> Resultado:
    # El adiós es cuenta.desactivada con motivo RETIRO, que publica auth-service.
    logger.info("empleado.retirado de %s no deja fila; el aviso es cuenta.desactivada", data.get("empleadoId"))
    return Resultado.IGNORADO


def _usuario_creado(repositorio, data: dict, ahora: datetime, enviar) -> Resultado:
    empleado_id = data["empleadoId"]
    email = data.get("email") or _email_guardado(repositorio, empleado_id)
    if not email:
        return _sin_destinatario("usuario.creado", empleado_id)
    tipo, asunto, mensaje = renderizar("usuario.creado", data)
    _registrar(repositorio, tipo, email, mensaje, ahora, empleado_id, enviar, asunto)
    return Resultado.PROCESADO


def _usuario_recuperacion(repositorio, data: dict, ahora: datetime, enviar) -> Resultado:
    email = data["email"]
    guardado = repositorio.buscar_destinatario_por_email(email)
    if guardado is None:
        return _sin_destinatario("usuario.recuperacion", email)
    tipo, asunto, mensaje = renderizar("usuario.recuperacion", data)
    _registrar(repositorio, tipo, email, mensaje, ahora, guardado.empleado_id, enviar, asunto)
    return Resultado.PROCESADO


def _cuenta_desactivada(repositorio, data: dict, ahora: datetime, enviar) -> Resultado:
    return _cuenta(repositorio, "cuenta.desactivada", data, ahora, enviar)


def _cuenta_activada(repositorio, data: dict, ahora: datetime, enviar) -> Resultado:
    return _cuenta(repositorio, "cuenta.activada", data, ahora, enviar)


def _cuenta(repositorio, evento: str, data: dict, ahora: datetime, enviar) -> Resultado:
    empleado_id = data["empleadoId"]
    email = data.get("email") or _email_guardado(repositorio, empleado_id)
    if not email:
        return _sin_destinatario(evento, empleado_id)
    tipo, asunto, mensaje = renderizar(evento, data)
    _registrar(repositorio, tipo, email, mensaje, ahora, empleado_id, enviar, asunto)
    return Resultado.PROCESADO


def _vacaciones_programadas(repositorio, data: dict, ahora: datetime, enviar) -> Resultado:
    return _vacaciones(repositorio, "vacaciones.programadas", data, ahora, enviar)


def _vacaciones_iniciadas(repositorio, data: dict, ahora: datetime, enviar) -> Resultado:
    return _vacaciones(repositorio, "vacaciones.iniciadas", data, ahora, enviar)


def _vacaciones_finalizadas(repositorio, data: dict, ahora: datetime, enviar) -> Resultado:
    return _vacaciones(repositorio, "vacaciones.finalizadas", data, ahora, enviar)


def _vacaciones(repositorio, evento: str, data: dict, ahora: datetime, enviar) -> Resultado:
    empleado_id = data.get("empleadoId")
    email = data.get("email") or _email_guardado(repositorio, empleado_id)
    if not email:
        return _sin_destinatario(evento, empleado_id)
    tipo, asunto, mensaje = renderizar(evento, data)
    _registrar(repositorio, tipo, email, mensaje, ahora, empleado_id, enviar, asunto)
    return Resultado.PROCESADO


MANEJADORES = {
    "empleado.creado": _empleado_creado,
    "empleado.retirado": _empleado_retirado,
    "usuario.creado": _usuario_creado,
    "usuario.recuperacion": _usuario_recuperacion,
    "cuenta.desactivada": _cuenta_desactivada,
    "cuenta.activada": _cuenta_activada,
    "vacaciones.programadas": _vacaciones_programadas,
    "vacaciones.iniciadas": _vacaciones_iniciadas,
    "vacaciones.finalizadas": _vacaciones_finalizadas,
}


def _sin_destinatario(evento: str, referencia: str | None) -> Resultado:
    logger.error("%s sin destinatario; se confirma sin fila", evento)
    return Resultado.SIN_DESTINATARIO


def _email_guardado(repositorio, empleado_id: str | None) -> str | None:
    if not empleado_id:
        return None
    guardado = repositorio.buscar_destinatario(empleado_id)
    return guardado.email if guardado else None


def _registrar(
    repositorio,
    tipo: str,
    email: str,
    mensaje: str,
    ahora: datetime,
    empleado_id: str,
    enviar,
    asunto: str,
) -> None:
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
    # El cuerpo puede contener tokens de restablecimiento y el destino es un dato
    # personal. Ambos se conservan en la notificación, pero nunca se escriben al log.
    logger.info("[NOTIFICACIÓN] Tipo: %s | Empleado: %s | Guardada", tipo, empleado_id)
    if enviar is None:
        return
    try:
        enviar(email, asunto, mensaje)
    except Exception:
        logger.exception("No se pudo enviar el correo; la notificación ya quedó guardada")

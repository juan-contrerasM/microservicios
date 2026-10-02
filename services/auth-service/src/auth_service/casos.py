import logging
from datetime import datetime

from auth_service.modelos import (
    ACTIVA,
    DESACTIVADA_PERMANENTE,
    PENDIENTE_ACTIVACION,
    ROL_ADMIN,
    ROL_USER,
    SUSPENDIDA_TEMPORAL,
    Cuenta,
    EventoSaliente,
    Respuesta,
)
from auth_service.seguridad import TokenInvalido, cumple_politica, hash_clave, iso_z, verifica_clave

logger = logging.getLogger("auth")

MENSAJE_CREDENCIALES = "Credenciales inválidas"
MENSAJE_INACTIVA = "La cuenta no está activa"
MENSAJE_RECUPERACION = "Si el correo existe, se envió un enlace de recuperación"
MENSAJE_ACTUALIZADA = "Contraseña actualizada"
MENSAJE_TOKEN = "El token de recuperación no es válido o expiró"
MENSAJE_POLITICA = "La contraseña no cumple la política de seguridad"
MENSAJE_ESTADO = "La cuenta no admite un cambio de contraseña en su estado actual"
MENSAJE_ACTUAL = "La contraseña actual no es correcta"
MENSAJE_NO_AUTENTICADO = "No autenticado"


def asegurar_admin(repo, settings, ahora: datetime) -> None:
    if repo.buscar_por_empleado(settings.auth_admin_usuario) is not None:
        return
    repo.insertar(
        Cuenta(
            empleado_id=settings.auth_admin_usuario,
            email=settings.auth_admin_email,
            rol=ROL_ADMIN,
            estado=ACTIVA,
            password_hash=hash_clave(settings.auth_admin_password),
            creada_en=ahora,
        )
    )


def iniciar_sesion(repo, tokens, usuario: str, contrasena: str, ahora: datetime) -> Respuesta:
    cuenta = repo.buscar_login(usuario)
    if cuenta is None or not verifica_clave(contrasena, cuenta.password_hash):
        return Respuesta(401, {"mensaje": MENSAJE_CREDENCIALES})
    if cuenta.estado != ACTIVA:
        return Respuesta(401, {"mensaje": MENSAJE_INACTIVA})
    token, expira_en = tokens.access(cuenta.empleado_id, cuenta.rol, ahora)
    return Respuesta(
        200,
        {
            "token": token,
            "tokenType": "Bearer",
            "expiresIn": expira_en,
            "role": cuenta.rol,
        },
    )


def recuperar(repo, tokens, email: str, ahora: datetime) -> tuple[Respuesta, list[EventoSaliente]]:
    respuesta = Respuesta(200, {"mensaje": MENSAJE_RECUPERACION})
    cuenta = repo.buscar_por_email(email)
    if cuenta is None or cuenta.estado not in (PENDIENTE_ACTIVACION, ACTIVA):
        return respuesta, []
    token, expira = tokens.reset(cuenta.empleado_id, ahora)
    evento = EventoSaliente(
        "usuario.recuperacion",
        {
            "email": cuenta.email,
            "tokenRecuperacion": token,
            "expiraEn": iso_z(expira),
        },
    )
    return respuesta, [evento]


def restablecer(repo, tokens, token: str, contrasena: str, ahora: datetime) -> tuple[Respuesta, list[EventoSaliente]]:
    try:
        payload = tokens.leer_reset(token)
    except TokenInvalido:
        return Respuesta(400, {"mensaje": MENSAJE_TOKEN}), []
    if not cumple_politica(contrasena):
        return Respuesta(400, {"mensaje": MENSAJE_POLITICA}), []
    cuenta = repo.buscar_por_empleado(payload["sub"])
    if cuenta is None:
        return Respuesta(400, {"mensaje": MENSAJE_TOKEN}), []
    if cuenta.estado in (SUSPENDIDA_TEMPORAL, DESACTIVADA_PERMANENTE):
        return Respuesta(400, {"mensaje": MENSAJE_ESTADO}), []
    if cuenta.estado not in (PENDIENTE_ACTIVACION, ACTIVA):
        return Respuesta(400, {"mensaje": MENSAJE_ESTADO}), []

    era_pendiente = cuenta.estado == PENDIENTE_ACTIVACION
    cuenta.password_hash = hash_clave(contrasena)
    cuenta.estado = ACTIVA
    repo.actualizar(cuenta)
    if not era_pendiente:
        return Respuesta(200, {"mensaje": MENSAJE_ACTUALIZADA}), []
    return Respuesta(200, {"mensaje": MENSAJE_ACTUALIZADA}), [
        EventoSaliente(
            "cuenta.activada",
            {
                "empleadoId": cuenta.empleado_id,
                "email": cuenta.email,
                "motivo": "ACTIVACION_INICIAL",
            },
        )
    ]


def cambiar_clave(repo, tokens, authorization: str | None, actual: str, nueva: str) -> Respuesta:
    token = _bearer(authorization)
    if token is None:
        return Respuesta(401, {"status": 401, "mensaje": MENSAJE_NO_AUTENTICADO})
    try:
        payload = tokens.leer_access(token)
    except TokenInvalido:
        return Respuesta(401, {"status": 401, "mensaje": MENSAJE_NO_AUTENTICADO})
    cuenta = repo.buscar_por_empleado(payload["sub"])
    if cuenta is None:
        return Respuesta(401, {"status": 401, "mensaje": MENSAJE_NO_AUTENTICADO})
    if not verifica_clave(actual, cuenta.password_hash):
        return Respuesta(400, {"mensaje": MENSAJE_ACTUAL})
    if not cumple_politica(nueva):
        return Respuesta(400, {"mensaje": MENSAJE_POLITICA})
    if cuenta.estado != ACTIVA:
        return Respuesta(400, {"mensaje": MENSAJE_ESTADO})
    cuenta.password_hash = hash_clave(nueva)
    repo.actualizar(cuenta)
    return Respuesta(200, {"mensaje": MENSAJE_ACTUALIZADA})


def procesar_evento(repo, tokens, envelope: dict, ahora: datetime) -> list[EventoSaliente]:
    event_id = str(envelope.get("id") or "")
    if not event_id:
        logger.error("Evento sin id; se confirma sin efecto")
        return []
    if repo.ya_procesado(event_id):
        return []

    tipo = envelope.get("type")
    data = envelope.get("data") or {}
    try:
        if tipo == "empleado.creado":
            salientes = _empleado_creado(repo, tokens, data, ahora)
        elif tipo == "empleado.retirado":
            salientes = _empleado_retirado(repo, data)
        elif tipo == "vacaciones.iniciadas":
            salientes = _vacaciones_iniciadas(repo, data)
        elif tipo == "vacaciones.finalizadas":
            salientes = _vacaciones_finalizadas(repo, data)
        else:
            logger.info("Evento %s ignorado por auth", tipo)
            salientes = []
    except KeyError:
        logger.exception("Evento %s incompleto; se confirma sin efecto", event_id)
        salientes = []

    repo.marcar_procesado(event_id, ahora)
    return salientes


def _empleado_creado(repo, tokens, data: dict, ahora: datetime) -> list[EventoSaliente]:
    empleado_id = data["empleadoId"]
    email = data["email"]
    if repo.buscar_por_empleado(empleado_id) is not None:
        logger.info("Ya existe cuenta para %s; no se publica otro usuario.creado", empleado_id)
        return []
    if repo.buscar_por_email(email) is not None:
        logger.error("El email %s ya tiene cuenta; se confirma sin alta", email)
        return []
    repo.insertar(
        Cuenta(
            empleado_id=empleado_id,
            email=email,
            rol=ROL_USER,
            estado=PENDIENTE_ACTIVACION,
            password_hash=None,
            creada_en=ahora,
        )
    )
    token, expira = tokens.reset(empleado_id, ahora)
    return [
        EventoSaliente(
            "usuario.creado",
            {
                "empleadoId": empleado_id,
                "email": email,
                "tokenActivacion": token,
                "expiraEn": iso_z(expira),
            },
        )
    ]


def _empleado_retirado(repo, data: dict) -> list[EventoSaliente]:
    empleado_id = data["empleadoId"]
    cuenta = repo.buscar_por_empleado(empleado_id)
    if cuenta is None:
        logger.error("empleado.retirado sin cuenta para %s", empleado_id)
        return []
    if cuenta.estado == DESACTIVADA_PERMANENTE:
        return []
    cuenta.estado = DESACTIVADA_PERMANENTE
    repo.actualizar(cuenta)
    return [_desactivada(cuenta, data.get("email"), "RETIRO", True)]


def _vacaciones_iniciadas(repo, data: dict) -> list[EventoSaliente]:
    empleado_id = data["empleadoId"]
    cuenta = repo.buscar_por_empleado(empleado_id)
    if cuenta is None:
        logger.error("vacaciones.iniciadas sin cuenta para %s", empleado_id)
        return []
    if cuenta.estado != ACTIVA:
        logger.info("La cuenta %s está %s; no se suspende", empleado_id, cuenta.estado)
        return []
    cuenta.estado = SUSPENDIDA_TEMPORAL
    repo.actualizar(cuenta)
    return [_desactivada(cuenta, data.get("email"), "VACACIONES", False)]


def _vacaciones_finalizadas(repo, data: dict) -> list[EventoSaliente]:
    empleado_id = data["empleadoId"]
    cuenta = repo.buscar_por_empleado(empleado_id)
    if cuenta is None:
        logger.error("vacaciones.finalizadas sin cuenta para %s", empleado_id)
        return []
    if cuenta.estado != SUSPENDIDA_TEMPORAL:
        logger.info("La cuenta %s está %s; no se reactiva", empleado_id, cuenta.estado)
        return []
    cuenta.estado = ACTIVA
    repo.actualizar(cuenta)
    return [
        EventoSaliente(
            "cuenta.activada",
            {
                "empleadoId": cuenta.empleado_id,
                "email": data.get("email") or cuenta.email,
                "motivo": "FIN_VACACIONES",
            },
        )
    ]


def _desactivada(cuenta: Cuenta, email: str | None, motivo: str, permanente: bool) -> EventoSaliente:
    return EventoSaliente(
        "cuenta.desactivada",
        {
            "empleadoId": cuenta.empleado_id,
            "email": email or cuenta.email,
            "motivo": motivo,
            "permanente": permanente,
        },
    )


def _bearer(authorization: str | None) -> str | None:
    if not authorization:
        return None
    partes = authorization.split(" ", 1)
    if len(partes) != 2 or partes[0].lower() != "bearer" or not partes[1].strip():
        return None
    return partes[1].strip()

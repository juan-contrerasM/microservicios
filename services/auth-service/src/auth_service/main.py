import logging
from contextlib import asynccontextmanager, contextmanager
from datetime import datetime, timezone

from auth_service.telemetria import configurar, instrumentar_app

configurar()

from fastapi import FastAPI, Header, HTTPException, Security
from fastapi.responses import JSONResponse, RedirectResponse
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer
from pydantic import BaseModel, ConfigDict, Field

from auth_service import __version__
from auth_service.casos import (
    asegurar_admin,
    cambiar_clave,
    iniciar_sesion,
    recuperar,
    restablecer,
)
from auth_service.config import Settings, load_settings
from auth_service.consumidor import EstadoConsumidor, iniciar_consumidor
from auth_service.errores import ajustar_openapi, instalar_manejadores, respuesta_error
from auth_service.publicar import PublicadorRabbit, publicar_todos
from auth_service.repositorio import SqlRepositorio, base_responde, crear_motor, crear_sesiones
from auth_service.salud import con_timeout
from auth_service.seguridad import Tokens

logger = logging.getLogger("auth")
SERVICIO = "auth-service"
bearer_auth = HTTPBearer(
    auto_error=False,
    scheme_name="BearerAuth",
    description="Access JWT emitido por POST /auth/login",
)


class LoginCuerpo(BaseModel):
    model_config = ConfigDict(json_schema_extra={"examples": [{"usuario": "admin", "contrasena": "Admin1234!"}]})

    usuario: str = Field(description="Email o id de empleado de la cuenta")
    contrasena: str


class RecuperarCuerpo(BaseModel):
    model_config = ConfigDict(json_schema_extra={"examples": [{"email": "ana.perez@empresa.com"}]})

    email: str


class ResetCuerpo(BaseModel):
    model_config = ConfigDict(
        json_schema_extra={"examples": [{"token": "eyJhbGciOiJIUzI1NiJ9.reset.firma", "contrasena": "Nueva1234!"}]}
    )

    token: str = Field(description="Token de activación (usuario.creado) o de recuperación (usuario.recuperacion)")
    contrasena: str = Field(description="Mínimo 8 caracteres con mayúscula, minúscula y dígito")


class CambioCuerpo(BaseModel):
    model_config = ConfigDict(
        json_schema_extra={"examples": [{"contrasenaActual": "Actual1234!", "contrasenaNueva": "Nueva1234!"}]}
    )

    contrasenaActual: str
    contrasenaNueva: str = Field(description="Mínimo 8 caracteres con mayúscula, minúscula y dígito")


class LoginRespuesta(BaseModel):
    model_config = ConfigDict(
        json_schema_extra={
            "examples": [
                {"token": "eyJhbGciOiJIUzI1NiJ9.acceso.firma", "tokenType": "Bearer", "expiresIn": 3600, "role": "ADMIN"}
            ]
        }
    )

    token: str
    tokenType: str = "Bearer"
    expiresIn: int = Field(description="Segundos de vida del access token")
    role: str = Field(description="ADMIN o USER")


class MensajeRespuesta(BaseModel):
    model_config = ConfigDict(json_schema_extra={"examples": [{"mensaje": "Contraseña actualizada"}]})

    mensaje: str


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


_ERROR_400_CUERPO = respuesta_error(400, "Cuerpo inválido o campos faltantes", "Petición inválida: usuario: es obligatorio", "/auth/login")
_ERROR_500 = respuesta_error(500, "Error inesperado", "Error interno del servidor", "/auth/login")
_ERROR_503 = respuesta_error(503, "La base de datos no está disponible", "La base de datos no está disponible", "/auth/login")


def crear_app(
    settings: Settings,
    abrir_repo,
    publicador,
    estado: EstadoConsumidor,
    comprobar_salud,
    tokens: Tokens,
    sesiones=None,
):
    @asynccontextmanager
    async def lifespan(_app: FastAPI):
        with abrir_repo() as (repo, sesion):
            try:
                asegurar_admin(repo, settings, datetime.now(timezone.utc))
                sesion.commit()
            except Exception:
                sesion.rollback()
                logger.exception("No se pudo sembrar el administrador")
        detener = None
        if not settings.consumer_disabled:
            detener = iniciar_consumidor(settings, sesiones, publicador, tokens, estado)
        yield
        if detener is not None:
            detener.set()

    app = FastAPI(
        title=SERVICIO,
        description=(
            "Identidad del onboarding. Emite JWT y administra el ciclo de vida de la cuenta. "
            "No existe /auth/register: las cuentas nacen del evento empleado.creado. "
            "Los errores siguen el contrato C3 (timestamp, status, error, message, path, traceId)."
        ),
        version=__version__,
        lifespan=lifespan,
    )
    instalar_manejadores(app)

    def _aplicar(respuesta, eventos):
        if eventos:
            publicar_todos(publicador, eventos)
        if respuesta.status == 200:
            return respuesta.cuerpo
        headers = {"WWW-Authenticate": "Bearer"} if respuesta.status == 401 else None
        raise HTTPException(status_code=respuesta.status, detail=respuesta.cuerpo.get("mensaje"), headers=headers)

    @app.get("/swagger", include_in_schema=False)
    @app.get("/swagger/", include_in_schema=False)
    def swagger():
        return RedirectResponse("/docs")

    @app.get(
        "/health",
        tags=["Salud"],
        summary="Health check contra PostgreSQL y el consumidor",
        description=(
            "200 si PostgreSQL responde y el consumidor está conectado a RabbitMQ; 503 si alguno está DOWN. "
            "Cada check tiene un tiempo máximo, así que responde en menos de 3 s aunque la base cuelgue."
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
        # Guarda externa: aunque comprobar_salud no acote sus checks, el endpoint no pasa de ~2.5 s.
        result = con_timeout(comprobar_salud, settings.health_timeout_seconds + 0.5, (False, False))
        database_ok, broker_ok = result if isinstance(result, tuple) else (bool(result), bool(result))
        checks = {"database": "UP" if database_ok else "DOWN", "messageBroker": "UP" if broker_ok else "DOWN"}
        sano = database_ok and broker_ok
        cuerpo = {"status": "UP" if sano else "DOWN", "service": SERVICIO, "version": __version__, "checks": checks}
        return cuerpo if sano else JSONResponse(status_code=503, content=cuerpo)

    @app.post(
        "/auth/login",
        tags=["Auth"],
        summary="Verifica credenciales y retorna un access JWT",
        response_model=LoginRespuesta,
        responses={
            400: _ERROR_400_CUERPO,
            401: respuesta_error(
                401, "Credenciales inválidas o cuenta no activa", "Credenciales inválidas", "/auth/login"
            ),
            500: _ERROR_500,
            503: _ERROR_503,
        },
    )
    def login(cuerpo: LoginCuerpo):
        with abrir_repo() as (repo, _sesion):
            respuesta = iniciar_sesion(repo, tokens, cuerpo.usuario, cuerpo.contrasena, datetime.now(timezone.utc))
        return _aplicar(respuesta, [])

    @app.post(
        "/auth/recover-password",
        tags=["Auth"],
        summary="Publica usuario.recuperacion si la cuenta puede recibir el enlace",
        description="Siempre responde 200 con el mismo mensaje, exista o no el correo (no revela cuentas).",
        response_model=MensajeRespuesta,
        responses={
            200: {
                "content": {
                    "application/json": {"example": {"mensaje": "Si el correo existe, se envió un enlace de recuperación"}}
                }
            },
            400: respuesta_error(400, "Cuerpo inválido", "Petición inválida: email: es obligatorio", "/auth/recover-password"),
            500: _ERROR_500,
            503: _ERROR_503,
        },
    )
    def recover(cuerpo: RecuperarCuerpo):
        with abrir_repo() as (repo, _sesion):
            respuesta, eventos = recuperar(repo, tokens, cuerpo.email, datetime.now(timezone.utc))
        return _aplicar(respuesta, eventos)

    @app.post(
        "/auth/reset-password",
        tags=["Auth"],
        summary="Establece la contraseña con el token de activación o recuperación",
        description="Con el token de activación pasa la cuenta de PENDIENTE_ACTIVACION a ACTIVA y publica cuenta.activada.",
        response_model=MensajeRespuesta,
        responses={
            400: respuesta_error(
                400,
                "Token inválido o expirado, contraseña fuera de política o cuenta en un estado que no lo admite",
                "El token de recuperación no es válido o expiró",
                "/auth/reset-password",
            ),
            500: _ERROR_500,
            503: _ERROR_503,
        },
    )
    def reset(cuerpo: ResetCuerpo):
        with abrir_repo() as (repo, sesion):
            respuesta, eventos = restablecer(repo, tokens, cuerpo.token, cuerpo.contrasena, datetime.now(timezone.utc))
            if respuesta.status == 200:
                sesion.commit()
            else:
                sesion.rollback()
        return _aplicar(respuesta, eventos if respuesta.status == 200 else [])

    @app.post(
        "/auth/change-password",
        tags=["Auth"],
        summary="Cambia la contraseña del sujeto del access token",
        response_model=MensajeRespuesta,
        responses={
            400: respuesta_error(
                400,
                "Contraseña actual incorrecta, nueva fuera de política o cuenta no activa",
                "La contraseña actual no es correcta",
                "/auth/change-password",
            ),
            401: respuesta_error(401, "Sin token o token inválido", "No autenticado", "/auth/change-password"),
            500: _ERROR_500,
            503: _ERROR_503,
        },
    )
    def change(
        cuerpo: CambioCuerpo,
        authorization: str | None = Header(default=None, include_in_schema=False),
        _credenciales: HTTPAuthorizationCredentials | None = Security(bearer_auth),
    ):
        with abrir_repo() as (repo, sesion):
            respuesta = cambiar_clave(repo, tokens, authorization, cuerpo.contrasenaActual, cuerpo.contrasenaNueva)
            if respuesta.status == 200:
                sesion.commit()
            else:
                sesion.rollback()
        return _aplicar(respuesta, [])

    instrumentar_app(app)
    ajustar_openapi(app)
    return app


def _abrir_sql(sesiones):
    @contextmanager
    def abrir():
        sesion = sesiones()
        try:
            yield SqlRepositorio(sesion), sesion
        finally:
            sesion.close()

    return abrir


def construir(settings: Settings | None = None):
    settings = settings or load_settings()
    motor = crear_motor(settings.database_url)
    sesiones = crear_sesiones(motor)
    estado = EstadoConsumidor()
    tokens = Tokens(settings.jwt_secret, settings.jwt_access_minutes, settings.reset_token_minutes)
    publicador = PublicadorRabbit(settings)

    def comprobar_salud() -> tuple[bool, bool]:
        cola_ok = settings.consumer_disabled or estado.conectado
        base_ok = con_timeout(lambda: base_responde(motor), settings.health_timeout_seconds, False)
        return base_ok, cola_ok

    app = crear_app(settings, _abrir_sql(sesiones), publicador, estado, comprobar_salud, tokens, sesiones)
    return app


settings = None
try:
    settings = load_settings()
except RuntimeError:
    settings = None

app = construir(settings) if settings is not None else FastAPI(title="auth-service")

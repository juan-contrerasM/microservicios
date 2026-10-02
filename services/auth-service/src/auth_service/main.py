import logging
from contextlib import asynccontextmanager, contextmanager
from datetime import datetime, timezone

logging.basicConfig(level=logging.INFO, format="%(levelname)s %(name)s %(message)s")
logging.getLogger("auth").setLevel(logging.INFO)
logging.getLogger("pika").setLevel(logging.WARNING)

from fastapi import FastAPI, Header
from fastapi.responses import JSONResponse, RedirectResponse
from pydantic import BaseModel

from auth_service.casos import (
    asegurar_admin,
    cambiar_clave,
    iniciar_sesion,
    recuperar,
    restablecer,
)
from auth_service.config import Settings, load_settings
from auth_service.consumidor import EstadoConsumidor, iniciar_consumidor
from auth_service.publicar import PublicadorRabbit, publicar_todos
from auth_service.repositorio import SqlRepositorio, base_responde, crear_motor, crear_sesiones
from auth_service.seguridad import Tokens

logger = logging.getLogger("auth")


class LoginCuerpo(BaseModel):
    usuario: str
    contrasena: str


class RecuperarCuerpo(BaseModel):
    email: str


class ResetCuerpo(BaseModel):
    token: str
    contrasena: str


class CambioCuerpo(BaseModel):
    contrasenaActual: str
    contrasenaNueva: str


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
        title="auth-service",
        description="Identidad del onboarding. Emite JWT y administra el ciclo de vida de la cuenta.",
        version="1.0.0",
        lifespan=lifespan,
    )

    def _aplicar(respuesta, eventos):
        if eventos:
            publicar_todos(publicador, eventos)
        if respuesta.status == 200:
            return respuesta.cuerpo
        return JSONResponse(status_code=respuesta.status, content=respuesta.cuerpo)

    @app.get("/swagger", include_in_schema=False)
    @app.get("/swagger/", include_in_schema=False)
    def swagger():
        return RedirectResponse("/docs")

    @app.get("/health", summary="Health check contra PostgreSQL y el consumidor")
    def health():
        if comprobar_salud():
            return {"status": "UP", "service": "auth-service"}
        return JSONResponse(status_code=503, content={"status": "DOWN", "service": "auth-service"})

    @app.post("/auth/login", summary="Verifica credenciales y retorna un access JWT")
    def login(cuerpo: LoginCuerpo):
        with abrir_repo() as (repo, _sesion):
            respuesta = iniciar_sesion(repo, tokens, cuerpo.usuario, cuerpo.contrasena, datetime.now(timezone.utc))
        return _aplicar(respuesta, [])

    @app.post("/auth/recover-password", summary="Publica usuario.recuperacion si la cuenta puede recibir el enlace")
    def recover(cuerpo: RecuperarCuerpo):
        with abrir_repo() as (repo, _sesion):
            respuesta, eventos = recuperar(repo, tokens, cuerpo.email, datetime.now(timezone.utc))
        return _aplicar(respuesta, eventos)

    @app.post("/auth/reset-password", summary="Establece la contraseña con el token de activación o recuperación")
    def reset(cuerpo: ResetCuerpo):
        with abrir_repo() as (repo, sesion):
            respuesta, eventos = restablecer(repo, tokens, cuerpo.token, cuerpo.contrasena, datetime.now(timezone.utc))
            if respuesta.status == 200:
                sesion.commit()
            else:
                sesion.rollback()
        return _aplicar(respuesta, eventos if respuesta.status == 200 else [])

    @app.post("/auth/change-password", summary="Cambia la contraseña del sujeto del access token")
    def change(cuerpo: CambioCuerpo, authorization: str | None = Header(default=None)):
        with abrir_repo() as (repo, sesion):
            respuesta = cambiar_clave(repo, tokens, authorization, cuerpo.contrasenaActual, cuerpo.contrasenaNueva)
            if respuesta.status == 200:
                sesion.commit()
            else:
                sesion.rollback()
        return _aplicar(respuesta, [])

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

    def comprobar_salud() -> bool:
        cola_ok = settings.consumer_disabled or estado.conectado
        return base_responde(motor) and cola_ok

    app = crear_app(settings, _abrir_sql(sesiones), publicador, estado, comprobar_salud, tokens, sesiones)
    return app


settings = None
try:
    settings = load_settings()
except RuntimeError:
    settings = None

app = construir(settings) if settings is not None else FastAPI(title="auth-service")

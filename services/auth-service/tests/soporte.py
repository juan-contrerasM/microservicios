from contextlib import contextmanager
from datetime import datetime, timezone

from auth_service.config import Settings
from auth_service.consumidor import EstadoConsumidor
from auth_service.main import crear_app
from auth_service.modelos import Cuenta
from auth_service.seguridad import Tokens


AHORA = datetime.now(timezone.utc)


class SesionNula:
    def commit(self):
        return None

    def rollback(self):
        return None

    def close(self):
        return None


class Memoria:
    def __init__(self):
        self.cuentas = {}
        self.por_email = {}
        self.procesados = set()

    def ya_procesado(self, event_id: str) -> bool:
        return event_id in self.procesados

    def marcar_procesado(self, event_id: str, _cuando) -> None:
        self.procesados.add(event_id)

    def buscar_por_empleado(self, empleado_id: str) -> Cuenta | None:
        return self.cuentas.get(empleado_id)

    def buscar_por_email(self, email: str) -> Cuenta | None:
        return self.por_email.get(email)

    def buscar_login(self, usuario: str) -> Cuenta | None:
        return self.buscar_por_email(usuario) or self.buscar_por_empleado(usuario)

    def insertar(self, cuenta: Cuenta) -> None:
        if cuenta.empleado_id in self.cuentas or cuenta.email in self.por_email:
            raise ValueError("cuenta duplicada")
        self.cuentas[cuenta.empleado_id] = cuenta
        self.por_email[cuenta.email] = cuenta

    def actualizar(self, cuenta: Cuenta) -> None:
        anterior = self.cuentas[cuenta.empleado_id]
        if anterior.email != cuenta.email:
            del self.por_email[anterior.email]
        self.cuentas[cuenta.empleado_id] = cuenta
        self.por_email[cuenta.email] = cuenta


class PublicadorMemoria:
    def __init__(self):
        self.eventos = []

    def publicar(self, evento) -> None:
        self.eventos.append(evento)


def settings_de_prueba() -> Settings:
    return Settings(
        jwt_secret="secreto-de-prueba",
        jwt_access_minutes=60,
        reset_token_minutes=60,
        auth_admin_usuario="admin",
        auth_admin_email="admin@empresa.com",
        auth_admin_password="Admin1234!",
        consumer_disabled=True,
    )


def tokens_de(settings: Settings) -> Tokens:
    return Tokens(settings.jwt_secret, settings.jwt_access_minutes, settings.reset_token_minutes)


@contextmanager
def abrir(repo: Memoria):
    yield repo, SesionNula()


def app_de_prueba(repo: Memoria, publicador: PublicadorMemoria):
    settings = settings_de_prueba()

    def abrir_repo():
        return abrir(repo)

    return crear_app(
        settings,
        abrir_repo,
        publicador,
        EstadoConsumidor(),
        lambda: True,
        tokens_de(settings),
    )

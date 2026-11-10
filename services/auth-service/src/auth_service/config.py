import os
from pathlib import Path
from urllib.parse import quote, urlsplit

from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", extra="ignore")

    port: int = 8086
    database_url: str = "postgresql+psycopg://auth:auth@localhost:5432/auth_db"
    broker_url: str = "amqp://localhost:5672"
    rabbitmq_user: str = "onboarding"
    rabbitmq_password: str = ""
    broker_exchange: str = "onboarding.eventos"
    broker_queue: str = "q.auth"
    consumer_disabled: bool = False
    jwt_secret: str = ""
    jwt_access_minutes: int = 60
    reset_token_minutes: int = 60
    auth_admin_usuario: str = "admin"
    auth_admin_email: str = "admin@empresa.com"
    auth_admin_password: str = ""

    def amqp_url(self) -> str:
        partes = urlsplit(self.broker_url)
        host = partes.hostname or "localhost"
        port = partes.port or 5672
        usuario = quote(self.rabbitmq_user, safe="")
        clave = quote(self.rabbitmq_password, safe="")
        return f"amqp://{usuario}:{clave}@{host}:{port}/"


def load_settings() -> Settings:
    values = _secret_values(
        "rabbitmq_password", "jwt_secret", "auth_admin_password", "db_password"
    )
    db_password = values.pop("db_password", "")
    if db_password and not os.environ.get("DATABASE_URL"):
        db_user = os.environ.get("DB_USER", "auth")
        db_host = os.environ.get("DB_HOST", "localhost")
        db_port = os.environ.get("DB_PORT", "5432")
        db_name = os.environ.get("DB_NAME", "auth_db")
        values["database_url"] = (
            f"postgresql+psycopg://{quote(db_user, safe='')}:{quote(db_password, safe='')}"
            f"@{db_host}:{db_port}/{db_name}"
        )
    settings = Settings(**values)
    if not settings.jwt_secret:
        raise RuntimeError("La variable de entorno JWT_SECRET es obligatoria")
    if not settings.rabbitmq_password or not settings.auth_admin_password:
        raise RuntimeError("Faltan secretos obligatorios de RabbitMQ o del administrador")
    return settings


def _secret_values(*names: str) -> dict[str, str]:
    values: dict[str, str] = {}
    for name in names:
        env_name = name.upper()
        direct = os.environ.get(env_name)
        path = os.environ.get(f"{env_name}_FILE")
        if direct:
            values[name] = direct
        elif path:
            value = Path(path).read_text(encoding="utf-8").strip()
            if value:
                values[name] = value
    return values

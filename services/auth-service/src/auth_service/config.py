from urllib.parse import quote, urlsplit

from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", extra="ignore")

    port: int = 8086
    database_url: str = "postgresql+psycopg://auth:auth@localhost:5432/auth_db"
    broker_url: str = "amqp://localhost:5672"
    rabbitmq_user: str = "onboarding"
    rabbitmq_password: str = "onboarding"
    broker_exchange: str = "onboarding.eventos"
    broker_queue: str = "q.auth"
    consumer_disabled: bool = False
    jwt_secret: str = ""
    jwt_access_minutes: int = 60
    reset_token_minutes: int = 60
    auth_admin_usuario: str = "admin"
    auth_admin_email: str = "admin@empresa.com"
    auth_admin_password: str = "Admin1234!"

    def amqp_url(self) -> str:
        partes = urlsplit(self.broker_url)
        host = partes.hostname or "localhost"
        port = partes.port or 5672
        usuario = quote(self.rabbitmq_user, safe="")
        clave = quote(self.rabbitmq_password, safe="")
        return f"amqp://{usuario}:{clave}@{host}:{port}/"


def load_settings() -> Settings:
    settings = Settings()
    if not settings.jwt_secret:
        raise RuntimeError("La variable de entorno JWT_SECRET es obligatoria")
    return settings

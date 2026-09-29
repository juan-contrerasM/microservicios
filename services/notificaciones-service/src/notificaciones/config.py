from urllib.parse import quote, urlsplit

from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", extra="ignore")

    port: int = 8084
    database_url: str = "postgresql+psycopg://notificaciones:notificaciones@localhost:5432/notificaciones_db"
    broker_url: str = "amqp://localhost:5672"
    rabbitmq_user: str = "onboarding"
    rabbitmq_password: str = "onboarding"
    broker_exchange: str = "onboarding.eventos"
    broker_queue: str = "q.notificaciones"
    consumer_disabled: bool = False
    smtp_host: str = ""
    smtp_port: int = 1025
    smtp_from: str = "onboarding@empresa.com"

    def amqp_url(self) -> str:
        partes = urlsplit(self.broker_url)
        host = partes.hostname or "localhost"
        port = partes.port or 5672
        usuario = quote(self.rabbitmq_user, safe="")
        clave = quote(self.rabbitmq_password, safe="")
        return f"amqp://{usuario}:{clave}@{host}:{port}/"


def load_settings() -> Settings:
    return Settings()

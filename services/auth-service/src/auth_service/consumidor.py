import json
import logging
import threading
import time
from datetime import datetime, timezone

import pika

from auth_service.casos import procesar_evento
from auth_service.publicar import publicar_todos
from auth_service.repositorio import SqlRepositorio
from auth_service.seguridad import Tokens

logger = logging.getLogger("auth")

CLAVES = (
    "empleado.creado",
    "empleado.retirado",
    "vacaciones.iniciadas",
    "vacaciones.finalizadas",
)
MAX_REINTENTOS = 3
RETRY_EXCHANGE_PREFIX = "onboarding.retry"
DLX_EXCHANGE = "onboarding.dlx"


class EstadoConsumidor:
    def __init__(self):
        self.conectado = False


def iniciar_consumidor(settings, sesiones, publicador, tokens: Tokens, estado: EstadoConsumidor) -> threading.Event:
    detener = threading.Event()

    def ciclo() -> None:
        while not detener.is_set():
            conexion = None
            try:
                parametros = pika.URLParameters(settings.amqp_url())
                parametros.heartbeat = 30
                parametros.blocked_connection_timeout = 30
                conexion = pika.BlockingConnection(parametros)
                canal = conexion.channel()
                _declarar_topologia(canal, settings.broker_exchange, settings.broker_queue)
                canal.basic_qos(prefetch_count=1)
                canal.basic_consume(
                    settings.broker_queue,
                    _manejador(sesiones, publicador, tokens, settings.broker_queue),
                    auto_ack=False,
                )
                estado.conectado = True
                logger.info("Consumidor escuchando %s", settings.broker_queue)
                while not detener.is_set():
                    conexion.process_data_events(time_limit=1)
            except Exception:
                estado.conectado = False
                logger.exception("Consumidor desconectado; reintento en 5s")
                detener.wait(5)
            finally:
                estado.conectado = False
                if conexion is not None and conexion.is_open:
                    conexion.close()

    hilo = threading.Thread(target=ciclo, name="auth-consumer", daemon=True)
    hilo.start()
    return detener


def _manejador(sesiones, publicador, tokens: Tokens, queue="q.auth"):
    def on_message(canal, method, properties, body: bytes) -> None:
        try:
            envelope = json.loads(body.decode("utf-8"))
        except (UnicodeDecodeError, json.JSONDecodeError):
            logger.exception("Mensaje no es JSON; se envía a DLQ")
            canal.basic_reject(method.delivery_tag, requeue=False)
            return
        if not envelope.get("id"):
            logger.error("Evento sin id; se envía a DLQ")
            canal.basic_reject(method.delivery_tag, requeue=False)
            return
        sesion = sesiones()
        try:
            eventos = procesar_evento(
                SqlRepositorio(sesion),
                tokens,
                envelope,
                datetime.now(timezone.utc),
            )
            sesion.commit()
        except Exception:
            sesion.rollback()
            _reintentar_o_dlq(canal, method, properties, body, envelope.get("id"), queue)
            return
        finally:
            sesion.close()
        publicar_todos(publicador, eventos)
        canal.basic_ack(method.delivery_tag)

    return on_message


def _declarar_topologia(canal, exchange: str, queue: str) -> None:
    retry_exchange = f"{RETRY_EXCHANGE_PREFIX}.{queue}"
    canal.exchange_declare(exchange=exchange, exchange_type="topic", durable=True)
    canal.exchange_declare(exchange=retry_exchange, exchange_type="topic", durable=True)
    canal.exchange_declare(exchange=DLX_EXCHANGE, exchange_type="direct", durable=True)
    canal.queue_declare(
        queue=queue,
        durable=True,
        arguments={"x-dead-letter-exchange": DLX_EXCHANGE, "x-dead-letter-routing-key": queue},
    )
    retry_queue = f"{queue}.retry"
    canal.queue_declare(
        queue=retry_queue,
        durable=True,
        arguments={"x-message-ttl": 5000, "x-dead-letter-exchange": exchange},
    )
    dlq = f"{queue}.dlq"
    canal.queue_declare(queue=dlq, durable=True)
    canal.queue_bind(queue=dlq, exchange=DLX_EXCHANGE, routing_key=queue)
    for clave in CLAVES:
        canal.queue_bind(queue=queue, exchange=exchange, routing_key=clave)
        canal.queue_bind(queue=retry_queue, exchange=retry_exchange, routing_key=clave)


def _reintentar_o_dlq(canal, method, properties, body: bytes, event_id, queue: str) -> None:
    headers = dict(getattr(properties, "headers", None) or {})
    intento = int(headers.get("x-retry-count", 0))
    if intento >= MAX_REINTENTOS:
        logger.exception("Fallo definitivo procesando %s; se envía a DLQ", event_id)
        canal.basic_reject(method.delivery_tag, requeue=False)
        return
    headers["x-retry-count"] = intento + 1
    nuevas = pika.BasicProperties(
        content_type=getattr(properties, "content_type", None) or "application/json",
        delivery_mode=2,
        headers=headers,
    )
    canal.basic_publish(
        exchange=f"{RETRY_EXCHANGE_PREFIX}.{queue}",
        routing_key=method.routing_key,
        body=body,
        properties=nuevas,
        mandatory=True,
    )
    canal.basic_ack(method.delivery_tag)
    logger.exception("Fallo procesando %s; reintento %s/%s programado", event_id, intento + 1, MAX_REINTENTOS)

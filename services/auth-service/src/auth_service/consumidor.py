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
                canal.exchange_declare(exchange=settings.broker_exchange, exchange_type="topic", durable=True)
                canal.queue_declare(queue=settings.broker_queue, durable=True)
                for clave in CLAVES:
                    canal.queue_bind(
                        queue=settings.broker_queue,
                        exchange=settings.broker_exchange,
                        routing_key=clave,
                    )
                canal.basic_qos(prefetch_count=1)
                canal.basic_consume(
                    settings.broker_queue,
                    _manejador(sesiones, publicador, tokens),
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


def _manejador(sesiones, publicador, tokens: Tokens):
    def on_message(canal, method, _properties, body: bytes) -> None:
        try:
            envelope = json.loads(body.decode("utf-8"))
        except (UnicodeDecodeError, json.JSONDecodeError):
            logger.exception("Mensaje no es JSON; se confirma para no bloquear la cola")
            canal.basic_ack(method.delivery_tag)
            return
        if not envelope.get("id"):
            logger.error("Evento sin id; se confirma sin efecto")
            canal.basic_ack(method.delivery_tag)
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
            logger.exception("Fallo procesando %s; se reencola", envelope.get("id"))
            canal.basic_nack(method.delivery_tag, requeue=True)
            return
        finally:
            sesion.close()
        publicar_todos(publicador, eventos)
        canal.basic_ack(method.delivery_tag)

    return on_message

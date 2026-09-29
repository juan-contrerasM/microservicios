import json
import logging
import threading
import time
from datetime import datetime, timezone

import pika

from notificaciones.correo import crear_enviador
from notificaciones.db import SqlRepositorio
from notificaciones.procesar import procesar

logger = logging.getLogger("notificaciones")

CLAVES = ("empleado.creado", "empleado.retirado", "vacaciones.programadas")


def iniciar_consumidor(settings, sesiones) -> threading.Event:
    detener = threading.Event()
    enviar = crear_enviador(settings)

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
                canal.basic_consume(settings.broker_queue, _manejador(sesiones, enviar), auto_ack=False)
                logger.info("Consumidor escuchando %s", settings.broker_queue)
                while not detener.is_set():
                    conexion.process_data_events(time_limit=1)
            except Exception:
                logger.exception("Consumidor desconectado; reintento en 5s")
                detener.wait(5)
            finally:
                if conexion is not None and conexion.is_open:
                    conexion.close()

    hilo = threading.Thread(target=ciclo, name="notificaciones-consumer", daemon=True)
    hilo.start()
    return detener


def _manejador(sesiones, enviar):
    def on_message(canal, method, _properties, body: bytes) -> None:
        try:
            envelope = json.loads(body.decode("utf-8"))
        except (UnicodeDecodeError, json.JSONDecodeError):
            logger.exception("Mensaje no es JSON; se confirma para no bloquear la cola")
            canal.basic_ack(method.delivery_tag)
            return
        sesion = sesiones()
        try:
            procesar(SqlRepositorio(sesion), envelope, datetime.now(timezone.utc), enviar)
            sesion.commit()
            canal.basic_ack(method.delivery_tag)
        except Exception:
            sesion.rollback()
            logger.exception("Fallo procesando %s; se reencola", envelope.get("id"))
            canal.basic_nack(method.delivery_tag, requeue=True)
        finally:
            sesion.close()

    return on_message

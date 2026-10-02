import json
import logging
import uuid
from datetime import datetime, timezone

import pika

from auth_service.modelos import EventoSaliente
from auth_service.seguridad import iso_z

logger = logging.getLogger("auth")


class PublicadorRabbit:
    def __init__(self, settings):
        self._settings = settings

    def publicar(self, evento: EventoSaliente) -> None:
        envelope = {
            "id": str(uuid.uuid4()),
            "type": evento.tipo,
            "version": 1,
            "occurredAt": iso_z(datetime.now(timezone.utc)),
            "producer": "auth-service",
            "data": evento.data,
        }
        conexion = pika.BlockingConnection(pika.URLParameters(self._settings.amqp_url()))
        try:
            canal = conexion.channel()
            canal.exchange_declare(
                exchange=self._settings.broker_exchange,
                exchange_type="topic",
                durable=True,
            )
            canal.basic_publish(
                exchange=self._settings.broker_exchange,
                routing_key=evento.tipo,
                body=json.dumps(envelope).encode("utf-8"),
                properties=pika.BasicProperties(content_type="application/json", delivery_mode=2),
            )
        finally:
            if conexion.is_open:
                conexion.close()
        logger.info("Publicado %s", evento.tipo)


def publicar_todos(publicador, eventos: list[EventoSaliente]) -> None:
    for evento in eventos:
        try:
            publicador.publicar(evento)
        except Exception:
            logger.exception("No se pudo publicar %s; el cambio de cuenta queda", evento.tipo)

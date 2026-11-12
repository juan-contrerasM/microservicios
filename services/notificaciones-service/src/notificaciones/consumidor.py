import json
import logging
import threading
from datetime import datetime, timezone

import pika
from pika.exceptions import ChannelClosedByBroker

from notificaciones.correo import crear_enviador
from notificaciones.db import SqlRepositorio
from notificaciones.metricas import inicializar_eventos, registrar_correo, registrar_evento
from notificaciones.procesar import Resultado, procesar

logger = logging.getLogger("notificaciones")

CLAVES = (
    "empleado.creado",
    "empleado.retirado",
    "vacaciones.programadas",
    "vacaciones.iniciadas",
    "vacaciones.finalizadas",
    "usuario.creado",
    "usuario.recuperacion",
    "cuenta.activada",
    "cuenta.desactivada",
)
inicializar_eventos(CLAVES)

# Política común de reintento/DLQ (ver docs/eventos.md). Los cuatro consumidores usan los
# mismos nombres para que la topología sea idéntica en RabbitMQ:
#   <q>        → dead-letter a onboarding.dlx con routing key <q>  (reject sin requeue = DLQ)
#   <q>.retry  → TTL 5 s y dead-letter al exchange por defecto ("") con routing key <q>,
#                así el reintento vuelve SOLO a la cola que falló y no a todos los consumidores.
#   <q>.dlq    → enlazada a onboarding.dlx con routing key <q>.
MAX_REINTENTOS = 3
RETRY_TTL_MS = 5000
DLX_EXCHANGE = "onboarding.dlx"
HEADER_REINTENTOS = "x-retry-count"
HEADER_ROUTING_ORIGINAL = "x-original-routing-key"
CAMPOS_ENVELOPE = ("id", "type", "version", "occurredAt", "producer", "data")
# Headers que gestiona el propio broker al hacer dead-letter; no se copian al republicar.
_HEADERS_DEL_BROKER = ("x-death", "x-first-death-", "x-last-death-")
PRECONDITION_FAILED = 406


class EstadoConsumidor:
    def __init__(self):
        self.conectado = False


class EnvelopeInvalido(ValueError):
    """El mensaje no cumple el contrato C1: nunca va a procesarse bien, va directo a DLQ."""


def iniciar_consumidor(settings, sesiones, estado: EstadoConsumidor | None = None) -> threading.Event:
    estado = estado or EstadoConsumidor()
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
                canal = declarar_topologia(conexion, settings.broker_exchange, settings.broker_queue)
                # Con confirmaciones, publicar en <q>.retry falla de forma visible y el
                # mensaje original no se confirma hasta que el reintento quedó en el broker.
                canal.confirm_delivery()
                canal.basic_qos(prefetch_count=1)
                canal.basic_consume(
                    settings.broker_queue,
                    _manejador(sesiones, enviar, settings.broker_queue),
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

    hilo = threading.Thread(target=ciclo, name="notificaciones-consumer", daemon=True)
    hilo.start()
    return detener


def validar_envelope(envelope, tipos=CLAVES) -> dict:
    """Exige los seis campos de C1 y un type que esta cola consume. Lanza EnvelopeInvalido."""
    if not isinstance(envelope, dict):
        raise EnvelopeInvalido("el cuerpo no es un objeto JSON")
    faltantes = [campo for campo in CAMPOS_ENVELOPE if envelope.get(campo) in (None, "")]
    if faltantes:
        raise EnvelopeInvalido(f"faltan campos obligatorios: {', '.join(faltantes)}")
    if not isinstance(envelope["id"], str):
        raise EnvelopeInvalido("id debe ser texto")
    if envelope["type"] not in tipos:
        raise EnvelopeInvalido(f"type desconocido para esta cola: {envelope['type']}")
    version = envelope["version"]
    if isinstance(version, bool) or not isinstance(version, int) or version < 1:
        raise EnvelopeInvalido("version debe ser un entero >= 1")
    if not isinstance(envelope["producer"], str):
        raise EnvelopeInvalido("producer debe ser texto")
    if not isinstance(envelope["data"], dict):
        raise EnvelopeInvalido("data debe ser un objeto")
    ocurrido = envelope["occurredAt"]
    try:
        datetime.fromisoformat(str(ocurrido).replace("Z", "+00:00"))
    except ValueError as error:
        raise EnvelopeInvalido("occurredAt no es ISO-8601") from error
    return envelope


def leer_envelope(body: bytes, tipos=CLAVES) -> dict:
    try:
        envelope = json.loads(body.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        raise EnvelopeInvalido("el mensaje no es JSON") from error
    return validar_envelope(envelope, tipos)


def _manejador(sesiones, enviar, queue="q.notificaciones"):
    def on_message(canal, method, properties, body: bytes) -> None:
        try:
            envelope = leer_envelope(body)
        except EnvelopeInvalido as error:
            # Poison: reintentarlo no lo arregla. Reject sin requeue → DLX → <q>.dlq.
            logger.error("Mensaje inválido; se envía a DLQ: %s", error)
            canal.basic_reject(method.delivery_tag, requeue=False)
            registrar_evento("dlq", getattr(method, "routing_key", None))
            return
        event_id = envelope["id"]
        # Los correos se acumulan y salen DESPUÉS del commit: si la transacción falla (o la
        # PK de eventos_procesados detecta que otra réplica ya lo procesó) no sale ningún
        # correo, y el reintento no puede duplicarlo porque el id ya quedó marcado.
        correos = []
        sesion = None
        try:
            sesion = sesiones()
            resultado = procesar(
                SqlRepositorio(sesion),
                envelope,
                datetime.now(timezone.utc),
                lambda destino, asunto, cuerpo: correos.append((destino, asunto, cuerpo)),
            )
            sesion.commit()
        except Exception as error:
            if sesion is not None:
                sesion.rollback()
            reintentar_o_dlq(canal, method, properties, body, event_id, queue, error)
            return
        finally:
            if sesion is not None:
                sesion.close()
        canal.basic_ack(method.delivery_tag)
        registrar_evento("duplicados" if resultado is Resultado.DUPLICADO else "procesados", envelope["type"])
        logger.info("Evento %s (%s) procesado", event_id, envelope["type"])
        enviar_correos(enviar, correos, envelope["type"])

    return on_message


def enviar_correos(enviar, correos, tipo: str | None = None) -> None:
    """Un fallo SMTP no deshace la notificación ya guardada ni reintenta el evento."""
    if enviar is None:
        return
    for destino, asunto, cuerpo in correos:
        try:
            enviar(destino, asunto, cuerpo)
        except Exception:
            registrar_correo(False, tipo)
            logger.exception("No se pudo enviar el correo; la notificación ya quedó guardada")
        else:
            registrar_correo(True, tipo)


def declarar_topologia(conexion, exchange: str, queue: str):
    """Declara exchange, cola principal, <q>.retry y <q>.dlq. Devuelve un canal abierto."""
    canal = conexion.channel()
    canal.exchange_declare(exchange=exchange, exchange_type="topic", durable=True)
    canal.exchange_declare(exchange=DLX_EXCHANGE, exchange_type="direct", durable=True)
    canal = _declarar_cola(conexion, canal, queue, argumentos_principal(queue))
    canal = _declarar_cola(conexion, canal, f"{queue}.retry", argumentos_retry(queue))
    canal = _declarar_cola(conexion, canal, f"{queue}.dlq", None)
    canal.queue_bind(queue=f"{queue}.dlq", exchange=DLX_EXCHANGE, routing_key=queue)
    for clave in CLAVES:
        canal.queue_bind(queue=queue, exchange=exchange, routing_key=clave)
    return canal


def argumentos_principal(queue: str) -> dict:
    return {"x-dead-letter-exchange": DLX_EXCHANGE, "x-dead-letter-routing-key": queue}


def argumentos_retry(queue: str) -> dict:
    return {
        "x-message-ttl": RETRY_TTL_MS,
        "x-dead-letter-exchange": "",
        "x-dead-letter-routing-key": queue,
    }


def _declarar_cola(conexion, canal, nombre: str, argumentos):
    """Si la cola ya existe con otros argumentos (PRECONDITION_FAILED), se recrea solo si
    está vacía; con mensajes dentro se falla con un mensaje claro (ver docs/eventos.md)."""
    try:
        canal.queue_declare(queue=nombre, durable=True, arguments=argumentos)
        return canal
    except ChannelClosedByBroker as error:
        if error.reply_code != PRECONDITION_FAILED:
            raise
    logger.warning("La cola %s existe con argumentos distintos; se recrea si está vacía", nombre)
    canal = conexion.channel()
    try:
        canal.queue_delete(queue=nombre, if_empty=True)
    except ChannelClosedByBroker as error:
        raise RuntimeError(
            f"La cola {nombre} tiene mensajes y argumentos antiguos; vacíala o bórrala "
            "desde la UI de RabbitMQ (ver docs/eventos.md, sección Migración)"
        ) from error
    canal.queue_declare(queue=nombre, durable=True, arguments=argumentos)
    return canal


def reintentar_o_dlq(canal, method, properties, body: bytes, event_id, queue: str, error=None) -> None:
    headers = {
        clave: valor
        for clave, valor in (getattr(properties, "headers", None) or {}).items()
        if not clave.startswith(_HEADERS_DEL_BROKER)
    }
    intento = _entero(headers.get(HEADER_REINTENTOS))
    if intento >= MAX_REINTENTOS:
        logger.error("Fallo definitivo procesando %s tras %s reintentos; se envía a DLQ: %s",
                     event_id, intento, error)
        canal.basic_reject(method.delivery_tag, requeue=False)
        registrar_evento("dlq", headers.get(HEADER_ROUTING_ORIGINAL) or method.routing_key)
        return
    # Se conservan todos los headers del productor (traceparent, tracestate, baggage...).
    headers[HEADER_REINTENTOS] = intento + 1
    headers.setdefault(HEADER_ROUTING_ORIGINAL, method.routing_key)
    nuevas = pika.BasicProperties(
        content_type=getattr(properties, "content_type", None) or "application/json",
        delivery_mode=2,
        headers=headers,
        message_id=getattr(properties, "message_id", None),
        correlation_id=getattr(properties, "correlation_id", None),
        type=getattr(properties, "type", None),
    )
    try:
        canal.basic_publish(exchange="", routing_key=f"{queue}.retry", body=body, properties=nuevas)
    except Exception:
        logger.exception("No se pudo programar el reintento de %s; se envía a DLQ", event_id)
        canal.basic_reject(method.delivery_tag, requeue=False)
        registrar_evento("dlq", headers.get(HEADER_ROUTING_ORIGINAL) or method.routing_key)
        return
    canal.basic_ack(method.delivery_tag)
    logger.error("Fallo procesando %s; reintento %s/%s programado en %s.retry: %s",
                 event_id, intento + 1, MAX_REINTENTOS, queue, error)


def _entero(valor) -> int:
    try:
        return int(valor or 0)
    except (TypeError, ValueError):
        return 0

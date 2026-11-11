"""Fase 4 (E12): consumidor con envelope validado, deduplicación sin correos repetidos,
reintento y DLQ. Todo con dobles: no hace falta RabbitMQ, PostgreSQL ni SMTP."""
import copy
import json
from types import SimpleNamespace

import pika
import pytest
from pika.exceptions import ChannelClosedByBroker

from notificaciones import consumidor
from test_procesar import Memoria, usuario_creado

TRACEPARENT = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01"


class Canal:
    def __init__(self, falla_publicar=False):
        self.acks = []
        self.rechazos = []
        self.publicados = []
        self._falla_publicar = falla_publicar

    def basic_ack(self, tag):
        self.acks.append(tag)

    def basic_reject(self, tag, requeue):
        self.rechazos.append((tag, requeue))

    def basic_publish(self, exchange, routing_key, body, properties):
        if self._falla_publicar:
            raise RuntimeError("canal caído")
        self.publicados.append((exchange, routing_key, body, properties))


class SesionTransaccional:
    """Trabaja sobre una copia del repositorio y solo la vuelca al confirmar, como una
    transacción real: un rollback no deja ni la notificación ni la marca de procesado."""

    def __init__(self, base, bitacora, falla_commit=False):
        self.base = base
        self.repo = copy.deepcopy(base)
        self.bitacora = bitacora
        self.falla_commit = falla_commit

    def commit(self):
        if self.falla_commit:
            raise RuntimeError("base caída")
        self.base.__dict__.update(self.repo.__dict__)
        self.bitacora.append("commit")

    def rollback(self):
        self.bitacora.append("rollback")

    def close(self):
        return None


@pytest.fixture
def entorno(monkeypatch):
    monkeypatch.setattr(consumidor, "SqlRepositorio", lambda sesion: sesion.repo)
    base = Memoria()
    bitacora = []
    correos = []

    def enviar(destino, asunto, cuerpo):
        bitacora.append("correo")
        correos.append((destino, asunto))

    estado = SimpleNamespace(falla_commit=False)
    manejador = consumidor._manejador(
        lambda: SesionTransaccional(base, bitacora, estado.falla_commit), enviar, "q.notificaciones"
    )
    return SimpleNamespace(base=base, bitacora=bitacora, correos=correos, manejador=manejador, estado=estado)


def _entregar(entorno, body, headers=None, tag=1, routing_key="usuario.creado"):
    canal = Canal()
    metodo = SimpleNamespace(delivery_tag=tag, routing_key=routing_key)
    entorno.manejador(canal, metodo, pika.BasicProperties(headers=headers or {}), body)
    return canal


def _body(envelope) -> bytes:
    return json.dumps(envelope).encode("utf-8")


def test_el_correo_sale_despues_del_commit(entorno):
    canal = _entregar(entorno, _body(usuario_creado()))

    assert canal.acks == [1]
    assert entorno.bitacora == ["commit", "correo"]
    assert len(entorno.base.notificaciones) == 1


def test_duplicado_no_envia_otro_correo_ni_deja_otra_fila(entorno):
    _entregar(entorno, _body(usuario_creado()))
    canal = _entregar(entorno, _body(usuario_creado()), tag=2)

    assert canal.acks == [2]
    assert len(entorno.correos) == 1
    assert len(entorno.base.notificaciones) == 1


def test_si_el_commit_falla_no_sale_correo_y_se_reintenta(entorno):
    entorno.estado.falla_commit = True

    canal = _entregar(entorno, _body(usuario_creado()), headers={"traceparent": TRACEPARENT})

    assert entorno.correos == []
    assert entorno.base.notificaciones == []
    assert entorno.base.procesados == set()
    exchange, routing_key, _body_, propiedades = canal.publicados[0]
    assert (exchange, routing_key) == ("", "q.notificaciones.retry")
    assert propiedades.headers["x-retry-count"] == 1
    assert propiedades.headers["traceparent"] == TRACEPARENT
    assert propiedades.headers["x-original-routing-key"] == "usuario.creado"
    assert canal.acks == [1]

    # El reintento, ya con la base de vuelta, envía el correo una sola vez.
    entorno.estado.falla_commit = False
    _entregar(entorno, canal.publicados[0][2], headers=propiedades.headers, tag=2, routing_key="q.notificaciones")
    assert len(entorno.correos) == 1


def test_contador_agotado_va_a_dlq(entorno):
    entorno.estado.falla_commit = True

    canal = _entregar(entorno, _body(usuario_creado()), headers={"x-retry-count": 3})

    assert canal.rechazos == [(1, False)]
    assert canal.publicados == []
    assert entorno.correos == []


@pytest.mark.parametrize(
    "body",
    [
        b"{no es json",
        _body({k: v for k, v in usuario_creado().items() if k != "version"}),
        _body({**usuario_creado(), "type": "empleado.actualizado"}),
        _body({**usuario_creado(), "data": "texto"}),
        _body({**usuario_creado(), "id": None}),
    ],
)
def test_envelope_invalido_se_rechaza_sin_requeue(entorno, body):
    canal = _entregar(entorno, body)

    assert canal.rechazos == [(1, False)]
    assert canal.acks == []
    assert entorno.bitacora == []


def test_un_fallo_smtp_no_reintenta_el_evento(monkeypatch):
    monkeypatch.setattr(consumidor, "SqlRepositorio", lambda sesion: sesion.repo)
    base = Memoria()

    def falla(*_):
        raise OSError("smtp caído")

    manejador = consumidor._manejador(lambda: SesionTransaccional(base, []), falla)
    canal = Canal()
    manejador(canal, SimpleNamespace(delivery_tag=5, routing_key="usuario.creado"),
              pika.BasicProperties(headers={}), _body(usuario_creado()))

    assert canal.acks == [5]
    assert canal.publicados == []
    assert len(base.notificaciones) == 1


def test_si_no_se_puede_programar_el_reintento_va_a_dlq():
    canal = Canal(falla_publicar=True)
    consumidor.reintentar_o_dlq(canal, SimpleNamespace(delivery_tag=9, routing_key="x"),
                                pika.BasicProperties(headers={}), b"{}", "evt", "q.notificaciones")
    assert canal.rechazos == [(9, False)]


class CanalTopologia:
    def __init__(self, conexion):
        self.conexion = conexion

    def exchange_declare(self, exchange, exchange_type, durable):
        self.conexion.exchanges[exchange] = exchange_type

    def queue_declare(self, queue, durable, arguments):
        previos = self.conexion.existentes.get(queue)
        if previos is not None and previos != (arguments or {}):
            raise ChannelClosedByBroker(406, "PRECONDITION_FAILED")
        self.conexion.existentes[queue] = arguments or {}

    def queue_delete(self, queue, if_empty):
        self.conexion.borradas.append(queue)
        del self.conexion.existentes[queue]

    def queue_bind(self, queue, exchange, routing_key):
        self.conexion.bindings.append((queue, exchange, routing_key))


class Conexion:
    def __init__(self, existentes=None):
        self.exchanges = {}
        self.existentes = existentes or {}
        self.borradas = []
        self.bindings = []

    def channel(self):
        return CanalTopologia(self)


def test_topologia_comun_y_migracion_de_la_cola_retry_antigua():
    conexion = Conexion({"q.notificaciones.retry": {"x-dead-letter-exchange": "onboarding.eventos"}})

    consumidor.declarar_topologia(conexion, "onboarding.eventos", "q.notificaciones")

    assert conexion.borradas == ["q.notificaciones.retry"]
    assert conexion.existentes["q.notificaciones.retry"] == {
        "x-message-ttl": 5000,
        "x-dead-letter-exchange": "",
        "x-dead-letter-routing-key": "q.notificaciones",
    }
    assert conexion.existentes["q.notificaciones"] == {
        "x-dead-letter-exchange": "onboarding.dlx",
        "x-dead-letter-routing-key": "q.notificaciones",
    }
    assert ("q.notificaciones.dlq", "onboarding.dlx", "q.notificaciones") in conexion.bindings
    assert {b[2] for b in conexion.bindings if b[0] == "q.notificaciones"} == set(consumidor.CLAVES)

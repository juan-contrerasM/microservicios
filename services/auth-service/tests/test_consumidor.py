"""Fase 4 (E12): consumidor con envelope validado, deduplicación, reintento y DLQ.

Todo con dobles: no hace falta RabbitMQ ni PostgreSQL.
"""
import json
from contextlib import contextmanager
from datetime import datetime, timezone
from types import SimpleNamespace

import pika
import pytest
from fastapi.testclient import TestClient
from pika.exceptions import ChannelClosedByBroker

from auth_service import consumidor
from auth_service.main import crear_app
from auth_service.modelos import PENDIENTE_ACTIVACION
from soporte import Memoria, PublicadorMemoria, settings_de_prueba, tokens_de

TRACEPARENT = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01"


def _envelope(event_id="evt-alta", tipo="empleado.creado", **extra):
    envelope = {
        "id": event_id,
        "type": tipo,
        "version": 1,
        "occurredAt": "2026-11-10T22:08:00Z",
        "producer": "empleados-service",
        "data": {"empleadoId": "E001", "email": "juan.perez@empresa.com"},
    }
    envelope.update(extra)
    return envelope


def _body(envelope) -> bytes:
    return json.dumps(envelope).encode("utf-8")


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


class Sesion:
    """Sesión de mentira que registra commit/rollback en una bitácora compartida."""

    def __init__(self, repo, bitacora, falla_commit=False):
        self.repo = repo
        self.bitacora = bitacora
        self.falla_commit = falla_commit

    def commit(self):
        if self.falla_commit:
            raise RuntimeError("base caída")
        self.bitacora.append("commit")

    def rollback(self):
        self.bitacora.append("rollback")

    def close(self):
        return None


class Publicador(PublicadorMemoria):
    def __init__(self, bitacora, falla=False):
        super().__init__()
        self.bitacora = bitacora
        self.falla = falla

    def publicar(self, evento):
        self.bitacora.append(f"publicar {evento.tipo}")
        if self.falla:
            raise RuntimeError("broker caído")
        super().publicar(evento)


@pytest.fixture
def entorno(monkeypatch):
    monkeypatch.setattr(consumidor, "SqlRepositorio", lambda sesion: sesion.repo)
    repo = Memoria()
    bitacora = []
    publicador = Publicador(bitacora)
    sesion = Sesion(repo, bitacora)
    manejador = consumidor._manejador(lambda: sesion, publicador, tokens_de(settings_de_prueba()), "q.auth")
    return SimpleNamespace(repo=repo, bitacora=bitacora, publicador=publicador, sesion=sesion, manejador=manejador)


def _entregar(entorno, body, headers=None, tag=1, routing_key="empleado.creado"):
    canal = Canal()
    metodo = SimpleNamespace(delivery_tag=tag, routing_key=routing_key)
    propiedades = pika.BasicProperties(content_type="application/json", headers=headers or {})
    entorno.manejador(canal, metodo, propiedades, body)
    return canal


def test_evento_valido_crea_la_cuenta_publica_despues_del_commit_y_confirma(entorno):
    canal = _entregar(entorno, _body(_envelope()))

    assert canal.acks == [1]
    assert entorno.repo.buscar_por_empleado("E001").estado == PENDIENTE_ACTIVACION
    assert entorno.bitacora == ["commit", "publicar usuario.creado"]


def test_duplicado_no_repite_el_efecto_ni_publica_y_se_confirma(entorno):
    _entregar(entorno, _body(_envelope()))
    entorno.bitacora.clear()

    canal = _entregar(entorno, _body(_envelope()), tag=2)

    assert canal.acks == [2]
    assert entorno.bitacora == ["commit"]
    assert len(entorno.publicador.eventos) == 1


def test_fallo_de_publicacion_no_revierte_la_cuenta_y_confirma(monkeypatch):
    monkeypatch.setattr(consumidor, "SqlRepositorio", lambda sesion: sesion.repo)
    bitacora = []
    repo = Memoria()
    manejador = consumidor._manejador(
        lambda: Sesion(repo, bitacora), Publicador(bitacora, falla=True), tokens_de(settings_de_prueba())
    )
    canal = Canal()
    manejador(canal, SimpleNamespace(delivery_tag=7, routing_key="empleado.creado"),
              pika.BasicProperties(headers={}), _body(_envelope()))

    assert canal.acks == [7]
    assert bitacora == ["commit", "publicar usuario.creado"]
    assert repo.buscar_por_empleado("E001") is not None


@pytest.mark.parametrize(
    "body",
    [
        b"esto no es json",
        b"\xff\xfe",
        _body(["lista"]),
        _body({k: v for k, v in _envelope().items() if k != "producer"}),
        _body({k: v for k, v in _envelope().items() if k != "occurredAt"}),
        _body(_envelope(id="")),
        _body(_envelope(type="evento.desconocido")),
        _body(_envelope(type="usuario.creado")),
        _body(_envelope(version="1")),
        _body(_envelope(version=0)),
        _body(_envelope(occurredAt="ayer")),
        _body(_envelope(data=None)),
        _body(_envelope(data=[1, 2])),
    ],
)
def test_envelope_invalido_se_rechaza_sin_requeue_y_no_toca_la_base(entorno, body):
    canal = _entregar(entorno, body)

    assert canal.rechazos == [(1, False)]
    assert canal.acks == []
    assert canal.publicados == []
    assert entorno.bitacora == []
    assert entorno.repo.cuentas == {}


def test_fallo_publica_en_la_cola_retry_con_contador_y_conserva_headers(monkeypatch, entorno):
    monkeypatch.setattr(consumidor, "procesar_evento", _explota)
    headers = {"traceparent": TRACEPARENT, "x-death": [{"count": 1}], "x-first-death-queue": "q.auth.retry"}

    canal = _entregar(entorno, _body(_envelope()), headers=headers)

    assert entorno.bitacora == ["rollback"]
    assert canal.acks == [1]
    assert len(canal.publicados) == 1
    exchange, routing_key, body, propiedades = canal.publicados[0]
    # Default exchange + cola retry propia: el reintento no se re-difunde a otros consumidores.
    assert exchange == ""
    assert routing_key == "q.auth.retry"
    assert json.loads(body)["id"] == "evt-alta"
    assert propiedades.delivery_mode == 2
    assert propiedades.headers["x-retry-count"] == 1
    assert propiedades.headers["traceparent"] == TRACEPARENT
    assert propiedades.headers["x-original-routing-key"] == "empleado.creado"
    assert "x-death" not in propiedades.headers
    assert "x-first-death-queue" not in propiedades.headers


def test_el_contador_avanza_y_conserva_la_routing_key_original(monkeypatch, entorno):
    monkeypatch.setattr(consumidor, "procesar_evento", _explota)
    headers = {"x-retry-count": 2, "x-original-routing-key": "empleado.creado", "traceparent": TRACEPARENT}

    canal = _entregar(entorno, _body(_envelope()), headers=headers, routing_key="q.auth")

    propiedades = canal.publicados[0][3]
    assert propiedades.headers["x-retry-count"] == 3
    assert propiedades.headers["x-original-routing-key"] == "empleado.creado"


def test_contador_agotado_va_a_dlq(monkeypatch, entorno):
    monkeypatch.setattr(consumidor, "procesar_evento", _explota)

    canal = _entregar(entorno, _body(_envelope()), headers={"x-retry-count": consumidor.MAX_REINTENTOS})

    assert canal.rechazos == [(1, False)]
    assert canal.publicados == []
    assert canal.acks == []


def test_fallo_del_commit_tambien_reintenta(monkeypatch):
    monkeypatch.setattr(consumidor, "SqlRepositorio", lambda sesion: sesion.repo)
    bitacora = []
    sesion = Sesion(Memoria(), bitacora, falla_commit=True)
    manejador = consumidor._manejador(lambda: sesion, Publicador(bitacora), tokens_de(settings_de_prueba()))
    canal = Canal()

    manejador(canal, SimpleNamespace(delivery_tag=3, routing_key="empleado.creado"),
              pika.BasicProperties(headers={}), _body(_envelope()))

    assert bitacora == ["rollback"]
    assert canal.publicados[0][1] == "q.auth.retry"
    assert canal.acks == [3]


def test_si_no_se_puede_programar_el_reintento_va_a_dlq():
    canal = Canal(falla_publicar=True)
    consumidor.reintentar_o_dlq(
        canal,
        SimpleNamespace(delivery_tag=9, routing_key="empleado.creado"),
        pika.BasicProperties(headers={}),
        b"{}",
        "evt",
        "q.auth",
    )
    assert canal.rechazos == [(9, False)]
    assert canal.acks == []


# --- Topología -----------------------------------------------------------------------------


class CanalTopologia:
    def __init__(self, registro, existentes=None):
        self.registro = registro
        self.existentes = existentes if existentes is not None else {}

    def exchange_declare(self, exchange, exchange_type, durable):
        self.registro.exchanges[exchange] = exchange_type

    def queue_declare(self, queue, durable, arguments):
        previos = self.existentes.get(queue)
        if previos is not None and previos["args"] != (arguments or {}):
            raise ChannelClosedByBroker(406, "PRECONDITION_FAILED - inequivalent arg")
        self.existentes[queue] = {"args": arguments or {}, "mensajes": 0}
        self.registro.colas[queue] = arguments or {}

    def queue_delete(self, queue, if_empty):
        if self.existentes[queue]["mensajes"] and if_empty:
            raise ChannelClosedByBroker(406, "PRECONDITION_FAILED - queue not empty")
        self.registro.borradas.append(queue)
        del self.existentes[queue]

    def queue_bind(self, queue, exchange, routing_key):
        self.registro.bindings.append((queue, exchange, routing_key))


class Conexion:
    def __init__(self, existentes=None):
        self.exchanges = {}
        self.colas = {}
        self.borradas = []
        self.bindings = []
        self.canales = 0
        self._existentes = existentes if existentes is not None else {}

    def channel(self):
        self.canales += 1
        return CanalTopologia(self, self._existentes)


def test_topologia_retry_vuelve_solo_a_la_cola_de_origen():
    conexion = Conexion()

    consumidor.declarar_topologia(conexion, "onboarding.eventos", "q.auth")

    assert conexion.exchanges == {"onboarding.eventos": "topic", "onboarding.dlx": "direct"}
    assert conexion.colas["q.auth"] == {
        "x-dead-letter-exchange": "onboarding.dlx",
        "x-dead-letter-routing-key": "q.auth",
    }
    assert conexion.colas["q.auth.retry"] == {
        "x-message-ttl": 5000,
        "x-dead-letter-exchange": "",
        "x-dead-letter-routing-key": "q.auth",
    }
    assert conexion.colas["q.auth.dlq"] == {}
    assert ("q.auth.dlq", "onboarding.dlx", "q.auth") in conexion.bindings
    # La cola retry no se enlaza a ningún exchange: solo se llega a ella por el default exchange.
    assert not [b for b in conexion.bindings if b[0] == "q.auth.retry"]
    assert {b[2] for b in conexion.bindings if b[0] == "q.auth"} == set(consumidor.CLAVES)


def test_cola_vacia_con_argumentos_antiguos_se_recrea_al_arrancar():
    viejos = {"x-message-ttl": 5000, "x-dead-letter-exchange": "onboarding.eventos"}
    conexion = Conexion({"q.auth.retry": {"args": viejos, "mensajes": 0}})

    consumidor.declarar_topologia(conexion, "onboarding.eventos", "q.auth")

    assert conexion.borradas == ["q.auth.retry"]
    assert conexion.colas["q.auth.retry"]["x-dead-letter-exchange"] == ""


def test_cola_con_mensajes_y_argumentos_antiguos_falla_con_mensaje_claro():
    conexion = Conexion({"q.auth": {"args": {}, "mensajes": 4}})

    with pytest.raises(RuntimeError, match="docs/eventos.md"):
        consumidor.declarar_topologia(conexion, "onboarding.eventos", "q.auth")
    assert conexion.borradas == []


# --- Productor HTTP: publicación después del commit ----------------------------------------


def test_reset_publica_cuenta_activada_despues_del_commit():
    repo = Memoria()
    settings = settings_de_prueba()
    tokens = tokens_de(settings)
    alta = consumidor.procesar_evento(repo, tokens, _envelope(), datetime.now(timezone.utc))
    bitacora = []

    class SesionOrden:
        def commit(self):
            bitacora.append("commit")

        def rollback(self):
            bitacora.append("rollback")

    class PublicadorCaido:
        def publicar(self, evento):
            bitacora.append(f"publicar {evento.tipo}")
            raise RuntimeError("broker caído")

    @contextmanager
    def abrir():
        yield repo, SesionOrden()

    app = crear_app(settings, abrir, PublicadorCaido(), consumidor.EstadoConsumidor(), lambda: True, tokens)
    with TestClient(app) as cliente:
        bitacora.clear()
        respuesta = cliente.post(
            "/auth/reset-password",
            json={"token": alta[0].data["tokenActivacion"], "contrasena": "Usuario123!"},
        )

    # El fallo del broker no revierte el negocio ni cambia la respuesta.
    assert respuesta.status_code == 200
    assert bitacora == ["commit", "publicar cuenta.activada"]


def _explota(*_args, **_kwargs):
    raise RuntimeError("fallo transitorio")

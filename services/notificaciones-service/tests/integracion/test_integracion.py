"""Fase 8 (E14): integración contra PostgreSQL, RabbitMQ y un SMTP de prueba (Mailhog) reales.

Alembic migra la base (upgrade/downgrade/upgrade), el consumidor del lifespan escucha el broker y
el correo se verifica en la API de Mailhog. Requiere DATABASE_URL, IT_AMQP_HOST e IT_MAILHOG_API
(los fija scripts/test-integration.sh); sin ellas se omite.
"""

import json
import os
import subprocess
import time
import urllib.request
import uuid
from datetime import datetime, timezone
from pathlib import Path

import pika
import pytest
from fastapi.testclient import TestClient
from sqlalchemy import create_engine, inspect

RAIZ = Path(__file__).resolve().parents[2]
pytestmark = pytest.mark.skipif(
    not all(os.environ.get(v) for v in ("DATABASE_URL", "IT_AMQP_HOST", "IT_MAILHOG_API")),
    reason="DATABASE_URL, IT_AMQP_HOST e IT_MAILHOG_API no definidas",
)

EXCHANGE = f"it.notif.{uuid.uuid4().hex[:8]}"
COLA = f"q.notificaciones.it.{uuid.uuid4().hex[:8]}"
TABLAS = {"destinatarios", "notificaciones", "eventos_procesados"}


def _alembic(*args: str) -> None:
    subprocess.run(["alembic", *args], cwd=RAIZ, check=True, capture_output=True)


def _tablas() -> set[str]:
    motor = create_engine(os.environ["DATABASE_URL"])
    try:
        return set(inspect(motor).get_table_names())
    finally:
        motor.dispose()


def _esperar(condicion, segundos=20):
    limite = time.monotonic() + segundos
    while time.monotonic() < limite:
        valor = condicion()
        if valor:
            return valor
        time.sleep(0.2)
    raise AssertionError("la condición no se cumplió a tiempo")


def _correos_para(destino: str) -> list[dict]:
    with urllib.request.urlopen(f"{os.environ['IT_MAILHOG_API']}/api/v2/search?kind=to&query={destino}") as r:
        return json.load(r)["items"]


def _publicar(canal, tipo: str, data: dict, event_id: str | None = None) -> str:
    event_id = event_id or str(uuid.uuid4())
    envelope = {
        "id": event_id,
        "type": tipo,
        "version": 1,
        "occurredAt": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
        "producer": "it",
        "data": data,
    }
    canal.basic_publish(EXCHANGE, tipo, json.dumps(envelope).encode(), pika.BasicProperties(delivery_mode=2))
    return event_id


@pytest.fixture(scope="module")
def entorno():
    os.environ["BROKER_EXCHANGE"] = EXCHANGE
    os.environ["BROKER_QUEUE"] = COLA
    _alembic("upgrade", "head")
    from notificaciones.main import app

    credenciales = pika.PlainCredentials(os.environ["RABBITMQ_USER"], os.environ["RABBITMQ_PASSWORD"])
    conexion = pika.BlockingConnection(pika.ConnectionParameters(os.environ["IT_AMQP_HOST"], credentials=credenciales))
    canal = conexion.channel()
    canal.exchange_declare(EXCHANGE, "topic", durable=True)
    with TestClient(app) as cliente:
        _esperar(lambda: cliente.get("/health").status_code == 200)
        yield cliente, canal
    for cola in (COLA, f"{COLA}.retry", f"{COLA}.dlq"):
        canal.queue_delete(cola)
    canal.exchange_delete(EXCHANGE)
    conexion.close()


def test_migraciones_bajan_y_suben_contra_postgres_real():
    _alembic("upgrade", "head")
    assert TABLAS <= _tablas()
    _alembic("downgrade", "base")
    assert not TABLAS & _tablas()
    _alembic("upgrade", "head")
    assert TABLAS <= _tablas()


def test_eventos_reales_generan_correo_y_registro_sin_duplicados(entorno):
    cliente, canal = entorno
    empleado = f"E-IT-{uuid.uuid4().hex[:6]}"
    email = f"{empleado.lower()}@empresa.com"

    _publicar(canal, "empleado.creado", {"empleadoId": empleado, "email": email, "nombre": "Ana"})
    creado = _publicar(canal, "usuario.creado", {"empleadoId": empleado, "email": email, "tokenActivacion": "tok-123"})
    _publicar(canal, "usuario.creado", {"empleadoId": empleado, "email": email, "tokenActivacion": "tok-123"}, event_id=creado)
    _publicar(
        canal,
        "vacaciones.programadas",
        {"empleadoId": empleado, "email": email, "fechaInicio": "2030-02-04", "fechaFin": "2030-02-08"},
    )

    historial = _esperar(lambda: (r := cliente.get(f"/notificaciones/{empleado}").json()) and len(r) >= 2 and r)
    time.sleep(1)
    historial = cliente.get(f"/notificaciones/{empleado}").json()
    assert sorted(n["tipo"] for n in historial) == ["SEGURIDAD", "VACACIONES"], "el duplicado no debe registrar otra"
    assert all(n["destinatario"] == email for n in historial)
    assert any(n["empleadoId"] == empleado for n in cliente.get("/notificaciones").json())

    correos = _esperar(lambda: len(c := _correos_para(email)) >= 2 and c)
    asuntos = sorted(c["Content"]["Headers"]["Subject"][0] for c in correos)
    assert asuntos == ["Seguridad", "Vacaciones programadas"]


def test_un_mensaje_poison_termina_en_la_dlq_real(entorno):
    _cliente, canal = entorno
    canal.basic_publish(EXCHANGE, "usuario.creado", b"{no es json", pika.BasicProperties(delivery_mode=2))
    _esperar(lambda: canal.queue_declare(f"{COLA}.dlq", passive=True).method.message_count >= 1)

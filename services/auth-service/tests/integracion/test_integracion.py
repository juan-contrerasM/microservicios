"""Fase 8 (E14): integración contra PostgreSQL y RabbitMQ reales.

Sin dobles: Alembic migra la base de verdad (upgrade/downgrade/upgrade), el consumidor del
lifespan escucha el broker real y los eventos que publica Auth se leen de una cola propia.
Requiere DATABASE_URL e IT_AMQP_HOST (los fija scripts/test-integration.sh); sin ellas se omite.
"""

import json
import os
import subprocess
import time
import uuid
from datetime import datetime, timezone
from pathlib import Path

import pika
import pytest
from fastapi.testclient import TestClient
from sqlalchemy import create_engine, inspect

RAIZ = Path(__file__).resolve().parents[2]
pytestmark = pytest.mark.skipif(
    not os.environ.get("DATABASE_URL") or not os.environ.get("IT_AMQP_HOST"),
    reason="DATABASE_URL e IT_AMQP_HOST no definidas",
)

EXCHANGE = f"it.auth.{uuid.uuid4().hex[:8]}"
COLA = f"q.auth.it.{uuid.uuid4().hex[:8]}"


def _alembic(*args: str) -> None:
    subprocess.run(["alembic", *args], cwd=RAIZ, check=True, capture_output=True)


def _tablas() -> set[str]:
    motor = create_engine(os.environ["DATABASE_URL"])
    try:
        return set(inspect(motor).get_table_names())
    finally:
        motor.dispose()


def _canal():
    credenciales = pika.PlainCredentials(os.environ["RABBITMQ_USER"], os.environ["RABBITMQ_PASSWORD"])
    conexion = pika.BlockingConnection(pika.ConnectionParameters(os.environ["IT_AMQP_HOST"], credentials=credenciales))
    return conexion, conexion.channel()


def _esperar(condicion, segundos=20):
    limite = time.monotonic() + segundos
    while time.monotonic() < limite:
        valor = condicion()
        if valor:
            return valor
        time.sleep(0.2)
    raise AssertionError("la condición no se cumplió a tiempo")


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
    from auth_service.config import load_settings
    from auth_service.main import construir

    app = construir(load_settings())
    conexion, canal = _canal()
    canal.exchange_declare(EXCHANGE, "topic", durable=True)
    escucha = canal.queue_declare("", exclusive=True).method.queue
    for clave in ("usuario.*", "cuenta.*"):
        canal.queue_bind(escucha, EXCHANGE, clave)
    with TestClient(app) as cliente:
        _esperar(lambda: cliente.get("/health").status_code == 200)
        yield cliente, canal, escucha
    for cola in (COLA, f"{COLA}.retry", f"{COLA}.dlq"):
        canal.queue_delete(cola)
    canal.exchange_delete(EXCHANGE)
    conexion.close()


def _recibir(canal, cola, tipo: str) -> dict:
    def leer():
        metodo, _props, cuerpo = canal.basic_get(cola, auto_ack=True)
        if metodo is None:
            return None
        envelope = json.loads(cuerpo)
        return envelope if envelope["type"] == tipo else None

    return _esperar(leer)


def test_migraciones_bajan_y_suben_contra_postgres_real():
    _alembic("upgrade", "head")
    assert {"cuentas", "eventos_procesados"} <= _tablas()
    _alembic("downgrade", "base")
    assert not {"cuentas", "eventos_procesados"} & _tablas()
    _alembic("upgrade", "head")
    assert {"cuentas", "eventos_procesados"} <= _tablas()


def test_ciclo_de_vida_de_la_cuenta_por_eventos_y_api(entorno):
    cliente, canal, escucha = entorno
    empleado = f"E-IT-{uuid.uuid4().hex[:6]}"
    email = f"{empleado.lower()}@empresa.com"

    creado = _publicar(canal, "empleado.creado", {"empleadoId": empleado, "email": email})
    _publicar(canal, "empleado.creado", {"empleadoId": empleado, "email": email}, event_id=creado)
    usuario = _recibir(canal, escucha, "usuario.creado")
    assert usuario["producer"] == "auth-service"
    assert usuario["data"]["empleadoId"] == empleado
    time.sleep(1)
    assert canal.basic_get(escucha, auto_ack=True)[0] is None, "el duplicado no debe publicar otro usuario.creado"

    assert cliente.post("/auth/login", json={"usuario": email, "contrasena": "Clave1234"}).status_code == 401
    activar = cliente.post("/auth/reset-password", json={"token": usuario["data"]["tokenActivacion"], "contrasena": "Clave1234"})
    assert activar.status_code == 200
    assert _recibir(canal, escucha, "cuenta.activada")["data"]["empleadoId"] == empleado

    login = cliente.post("/auth/login", json={"usuario": email, "contrasena": "Clave1234"})
    assert login.status_code == 200
    token = login.json()["token"]
    cambio = cliente.post(
        "/auth/change-password",
        json={"contrasenaActual": "Clave1234", "contrasenaNueva": "Nueva1234"},
        headers={"Authorization": f"Bearer {token}"},
    )
    assert cambio.status_code == 200

    assert cliente.post("/auth/recover-password", json={"email": email}).status_code == 200
    assert _recibir(canal, escucha, "usuario.recuperacion")["data"]["email"] == email

    _publicar(canal, "vacaciones.iniciadas", {"empleadoId": empleado, "email": email})
    assert _recibir(canal, escucha, "cuenta.desactivada")["data"]["empleadoId"] == empleado
    assert cliente.post("/auth/login", json={"usuario": email, "contrasena": "Nueva1234"}).status_code == 401

    _publicar(canal, "vacaciones.finalizadas", {"empleadoId": empleado, "email": email})
    _recibir(canal, escucha, "cuenta.activada")
    assert cliente.post("/auth/login", json={"usuario": email, "contrasena": "Nueva1234"}).status_code == 200

    _publicar(canal, "empleado.retirado", {"empleadoId": empleado})
    _recibir(canal, escucha, "cuenta.desactivada")
    assert cliente.post("/auth/login", json={"usuario": email, "contrasena": "Nueva1234"}).status_code == 401


def test_un_mensaje_poison_termina_en_la_dlq_real(entorno):
    _cliente, canal, _escucha = entorno
    canal.basic_publish(EXCHANGE, "empleado.creado", b"{no es json", pika.BasicProperties(delivery_mode=2))
    _esperar(lambda: canal.queue_declare(f"{COLA}.dlq", passive=True).method.message_count >= 1)

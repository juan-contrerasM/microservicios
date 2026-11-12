"""Fase 13 (E24): ningún log contiene contraseñas, tokens de activación/recuperación ni access JWT.

Recorre el ciclo completo de una cuenta por la API HTTP (alta por evento, activación, login,
cambio y recuperación de contraseña) capturando TODOS los logs, y busca los valores sensibles.
"""

import logging

from fastapi.testclient import TestClient

from auth_service.casos import procesar_evento
from auth_service.publicar import publicar_todos
from soporte import AHORA, Memoria, PublicadorMemoria, app_de_prueba, settings_de_prueba, tokens_de

CLAVE = "Activa1234Segura"
NUEVA = "Nueva5678Segura"


def test_el_ciclo_de_la_cuenta_no_escribe_secretos_en_los_logs(caplog):
    caplog.set_level(logging.DEBUG)
    repo, publicador = Memoria(), PublicadorMemoria()
    tokens = tokens_de(settings_de_prueba())
    alta = {"id": "evt-logs", "type": "empleado.creado",
            "data": {"empleadoId": "E900", "email": "logs@empresa.com"}}
    eventos = procesar_evento(repo, tokens, alta, AHORA)
    publicar_todos(publicador, eventos)
    activacion = eventos[0].data["tokenActivacion"]

    with TestClient(app_de_prueba(repo, publicador)) as cliente:
        assert cliente.post("/auth/reset-password", json={"token": activacion, "contrasena": CLAVE}).status_code == 200
        login = cliente.post("/auth/login", json={"usuario": "logs@empresa.com", "contrasena": CLAVE})
        acceso = login.json()["token"]
        cambio = cliente.post("/auth/change-password", headers={"Authorization": f"Bearer {acceso}"},
                              json={"contrasenaActual": CLAVE, "contrasenaNueva": NUEVA})
        assert cambio.status_code == 200
        assert cliente.post("/auth/login", json={"usuario": "logs@empresa.com", "contrasena": "mala"}).status_code == 401
        assert cliente.post("/auth/recover-password", json={"email": "logs@empresa.com"}).status_code == 200

    recuperacion = next(e for e in publicador.eventos if e.tipo == "usuario.recuperacion").data["tokenRecuperacion"]
    texto = "\n".join(r.getMessage() for r in caplog.records)
    assert texto, "la prueba debe capturar logs reales"
    for sensible in (CLAVE, NUEVA, activacion, acceso, recuperacion, "Admin1234!"):
        assert sensible not in texto, f"un log contiene un valor sensible ({sensible[:6]}...)"

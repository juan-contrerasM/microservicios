import base64
import json

from fastapi.testclient import TestClient

from auth_service.seguridad import verifica_clave
from soporte import PublicadorMemoria, Memoria, app_de_prueba


def test_la_semilla_guarda_hash_y_el_login_firma_admin():
    repo = Memoria()
    app = app_de_prueba(repo, PublicadorMemoria())

    with TestClient(app) as cliente:
        mal = cliente.post("/auth/login", json={"usuario": "admin", "contrasena": "no-es"})
        bien = cliente.post("/auth/login", json={"usuario": "admin", "contrasena": "Admin1234!"})
        openapi = cliente.get("/openapi.json").json()

    assert mal.status_code == 401
    assert mal.json()["mensaje"] == "Credenciales inválidas"

    cuerpo = bien.json()
    assert bien.status_code == 200
    assert cuerpo["role"] == "ADMIN"
    assert cuerpo["tokenType"] == "Bearer"
    assert cuerpo["expiresIn"] == 3600

    payload = cuerpo["token"].split(".")[1]
    relleno = "=" * ((4 - len(payload) % 4) % 4)
    datos = json.loads(base64.urlsafe_b64decode(payload + relleno))
    assert datos["sub"] == "admin"
    assert datos["role"] == "ADMIN"
    assert "Admin1234!" not in json.dumps(datos)
    assert "password" not in datos

    guardada = repo.buscar_por_empleado("admin")
    assert guardada.password_hash != "Admin1234!"
    assert guardada.password_hash.startswith("$2")
    assert verifica_clave("Admin1234!", guardada.password_hash)
    assert openapi["components"]["securitySchemes"]["BearerAuth"]["scheme"] == "bearer"
    assert openapi["paths"]["/auth/change-password"]["post"]["security"] == [{"BearerAuth": []}]
    assert "security" not in openapi["paths"]["/auth/login"]["post"]

from auth_service.casos import (
    cambiar_clave,
    iniciar_sesion,
    procesar_evento,
    recuperar,
    restablecer,
)
from auth_service.modelos import ACTIVA, DESACTIVADA_PERMANENTE, PENDIENTE_ACTIVACION, SUSPENDIDA_TEMPORAL
from soporte import AHORA, Memoria, settings_de_prueba, tokens_de


def _creado(event_id="evt-alta"):
    return {
        "id": event_id,
        "type": "empleado.creado",
        "data": {
            "empleadoId": "E001",
            "email": "juan.perez@empresa.com",
            "nombre": "Juan",
            "apellido": "Pérez",
        },
    }


def _evento(event_id, tipo, data):
    return {"id": event_id, "type": tipo, "data": data}


def test_alta_reset_y_el_mismo_id_no_publica_dos_veces():
    repo = Memoria()
    tokens = tokens_de(settings_de_prueba())

    primero = procesar_evento(repo, tokens, _creado(), AHORA)
    segundo = procesar_evento(repo, tokens, _creado(), AHORA)

    assert len(primero) == 1
    assert primero[0].tipo == "usuario.creado"
    assert primero[0].data["tokenActivacion"]
    assert "password" not in primero[0].data
    assert segundo == []
    cuenta = repo.buscar_por_empleado("E001")
    assert cuenta.estado == PENDIENTE_ACTIVACION
    assert cuenta.password_hash is None
    assert cuenta.rol == "USER"

    respuesta, eventos = restablecer(repo, tokens, primero[0].data["tokenActivacion"], "Usuario123!", AHORA)
    assert respuesta.status == 200
    assert eventos[0].tipo == "cuenta.activada"
    assert eventos[0].data["motivo"] == "ACTIVACION_INICIAL"
    assert repo.buscar_por_empleado("E001").estado == ACTIVA

    login = iniciar_sesion(repo, tokens, "juan.perez@empresa.com", "Usuario123!", AHORA)
    assert login.status == 200
    assert login.cuerpo["role"] == "USER"


def test_recuperar_una_cuenta_activa_no_vuelve_a_publicar_activacion():
    repo = Memoria()
    tokens = tokens_de(settings_de_prueba())
    alta = procesar_evento(repo, tokens, _creado(), AHORA)
    restablecer(repo, tokens, alta[0].data["tokenActivacion"], "Usuario123!", AHORA)

    respuesta, eventos = recuperar(repo, tokens, "juan.perez@empresa.com", AHORA)
    assert respuesta.cuerpo["mensaje"] == "Si el correo existe, se envió un enlace de recuperación"
    assert eventos[0].tipo == "usuario.recuperacion"
    assert "empleadoId" not in eventos[0].data

    otra, nada = recuperar(repo, tokens, "nadie@empresa.com", AHORA)
    assert otra.status == 200
    assert nada == []

    reset, publicados = restablecer(repo, tokens, eventos[0].data["tokenRecuperacion"], "Usuario123!", AHORA)
    assert reset.status == 200
    assert publicados == []


def test_change_password_exige_access_token_y_la_clave_actual():
    repo = Memoria()
    tokens = tokens_de(settings_de_prueba())
    alta = procesar_evento(repo, tokens, _creado(), AHORA)
    restablecer(repo, tokens, alta[0].data["tokenActivacion"], "Usuario123!", AHORA)
    login = iniciar_sesion(repo, tokens, "juan.perez@empresa.com", "Usuario123!", AHORA)
    header = f"Bearer {login.cuerpo['token']}"

    sin_token = cambiar_clave(repo, tokens, None, "Usuario123!", "Usuario456!")
    assert sin_token.status == 401

    corta = cambiar_clave(repo, tokens, header, "Usuario123!", "corta")
    assert corta.status == 400
    assert corta.cuerpo["mensaje"] == "La contraseña no cumple la política de seguridad"

    mal = cambiar_clave(repo, tokens, header, "OtraClave1", "Usuario456!")
    assert mal.cuerpo["mensaje"] == "La contraseña actual no es correcta"

    bien = cambiar_clave(repo, tokens, header, "Usuario123!", "Usuario456!")
    assert bien.status == 200
    assert iniciar_sesion(repo, tokens, "juan.perez@empresa.com", "Usuario123!", AHORA).status == 401
    assert iniciar_sesion(repo, tokens, "juan.perez@empresa.com", "Usuario456!", AHORA).status == 200


def test_vacaciones_suspenden_y_el_fin_reactiva():
    repo = Memoria()
    tokens = tokens_de(settings_de_prueba())
    _activar(repo, tokens)

    inicio = procesar_evento(
        repo,
        tokens,
        _evento("evt-inicio", "vacaciones.iniciadas", {"empleadoId": "E001", "email": "juan.perez@empresa.com"}),
        AHORA,
    )
    assert repo.buscar_por_empleado("E001").estado == SUSPENDIDA_TEMPORAL
    assert inicio[0].data["motivo"] == "VACACIONES"
    assert inicio[0].data["permanente"] is False
    login = iniciar_sesion(repo, tokens, "juan.perez@empresa.com", "Usuario123!", AHORA)
    assert login.status == 401
    assert login.cuerpo["mensaje"] == "La cuenta no está activa"

    fin = procesar_evento(
        repo,
        tokens,
        _evento("evt-fin", "vacaciones.finalizadas", {"empleadoId": "E001", "email": "juan.perez@empresa.com"}),
        AHORA,
    )
    assert repo.buscar_por_empleado("E001").estado == ACTIVA
    assert fin[0].data["motivo"] == "FIN_VACACIONES"
    assert iniciar_sesion(repo, tokens, "juan.perez@empresa.com", "Usuario123!", AHORA).status == 200


def test_retiro_durante_vacaciones_no_reactiva_y_el_id_repetido_no_publica():
    repo = Memoria()
    tokens = tokens_de(settings_de_prueba())
    _activar(repo, tokens)

    procesar_evento(
        repo,
        tokens,
        _evento("evt-inicio", "vacaciones.iniciadas", {"empleadoId": "E001", "email": "juan.perez@empresa.com"}),
        AHORA,
    )
    repetido = procesar_evento(
        repo,
        tokens,
        _evento("evt-inicio", "vacaciones.iniciadas", {"empleadoId": "E001", "email": "juan.perez@empresa.com"}),
        AHORA,
    )
    assert repetido == []

    retiro = procesar_evento(
        repo,
        tokens,
        _evento("evt-retiro", "empleado.retirado", {"empleadoId": "E001", "email": "juan.perez@empresa.com"}),
        AHORA,
    )
    assert repo.buscar_por_empleado("E001").estado == DESACTIVADA_PERMANENTE
    assert retiro[0].data["motivo"] == "RETIRO"
    assert retiro[0].data["permanente"] is True

    fin = procesar_evento(
        repo,
        tokens,
        _evento("evt-fin", "vacaciones.finalizadas", {"empleadoId": "E001", "email": "juan.perez@empresa.com"}),
        AHORA,
    )
    assert fin == []
    assert repo.buscar_por_empleado("E001").estado == DESACTIVADA_PERMANENTE
    login = iniciar_sesion(repo, tokens, "juan.perez@empresa.com", "Usuario123!", AHORA)
    assert login.status == 401
    assert login.cuerpo["mensaje"] == "La cuenta no está activa"


def _activar(repo, tokens):
    alta = procesar_evento(repo, tokens, _creado(), AHORA)
    restablecer(repo, tokens, alta[0].data["tokenActivacion"], "Usuario123!", AHORA)

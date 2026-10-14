from datetime import timedelta

import pytest

from auth_service.casos import iniciar_sesion, procesar_evento, recuperar, restablecer
from auth_service.modelos import ACTIVA, DESACTIVADA_PERMANENTE, PENDIENTE_ACTIVACION, SUSPENDIDA_TEMPORAL
from auth_service.seguridad import TokenInvalido, Tokens, cumple_politica, hash_clave, verifica_clave
from soporte import AHORA, Memoria, settings_de_prueba, tokens_de

EMAIL = "juan.perez@empresa.com"
TOKEN_INVALIDO = "El token de recuperación no es válido o expiró"
ESTADO_INVALIDO = "La cuenta no admite un cambio de contraseña en su estado actual"


def _evento(event_id, tipo, data):
    return {"id": event_id, "type": tipo, "data": data}


def _alta(repo, tokens, event_id="evt-alta"):
    eventos = procesar_evento(
        repo,
        tokens,
        _evento(event_id, "empleado.creado", {"empleadoId": "E001", "email": EMAIL}),
        AHORA,
    )
    return eventos[0].data["tokenActivacion"]


def _activa(repo, tokens):
    restablecer(repo, tokens, _alta(repo, tokens), "Usuario123!", AHORA)


@pytest.mark.parametrize(
    "clave, cumple",
    [
        ("Usuario123", True),
        ("Abcdefg1", True),
        ("Abcdef1", False),
        ("usuario123", False),
        ("USUARIO123", False),
        ("UsuarioAbc", False),
        ("", False),
    ],
)
def test_politica_de_contrasena(clave, cumple):
    assert cumple_politica(clave) is cumple


def test_la_clave_se_guarda_como_bcrypt_y_no_en_claro():
    guardado = hash_clave("Usuario123!")

    assert guardado != "Usuario123!"
    assert guardado.startswith("$2")
    assert verifica_clave("Usuario123!", guardado)
    assert not verifica_clave("Usuario124!", guardado)
    assert not verifica_clave("Usuario123!", None)
    assert hash_clave("Usuario123!") != guardado


def test_token_de_reset_expirado_es_invalido():
    tokens = tokens_de(settings_de_prueba())
    token, _ = tokens.reset("E001", AHORA - timedelta(minutes=61))

    with pytest.raises(TokenInvalido):
        tokens.leer_reset(token)


def test_access_y_reset_no_se_intercambian():
    tokens = tokens_de(settings_de_prueba())
    access, _ = tokens.access("E001", "USER", AHORA)
    reset, _ = tokens.reset("E001", AHORA)

    with pytest.raises(TokenInvalido):
        tokens.leer_reset(access)
    with pytest.raises(TokenInvalido):
        tokens.leer_access(reset)


def test_token_firmado_con_otro_secreto_es_invalido():
    ajeno = Tokens("otro-secreto", 60, 60)
    token, _ = ajeno.reset("E001", AHORA)

    with pytest.raises(TokenInvalido):
        tokens_de(settings_de_prueba()).leer_reset(token)


def test_reset_con_token_expirado_responde_400_y_no_activa():
    repo = Memoria()
    tokens = tokens_de(settings_de_prueba())
    _alta(repo, tokens)
    vencido, _ = tokens.reset("E001", AHORA - timedelta(minutes=61))

    respuesta, eventos = restablecer(repo, tokens, vencido, "Usuario123!", AHORA)

    assert respuesta.status == 400
    assert respuesta.cuerpo["mensaje"] == TOKEN_INVALIDO
    assert eventos == []
    assert repo.buscar_por_empleado("E001").estado == PENDIENTE_ACTIVACION


def test_reset_con_access_token_responde_400():
    repo = Memoria()
    tokens = tokens_de(settings_de_prueba())
    _activa(repo, tokens)
    access = iniciar_sesion(repo, tokens, EMAIL, "Usuario123!", AHORA).cuerpo["token"]

    respuesta, eventos = restablecer(repo, tokens, access, "Usuario456!", AHORA)

    assert respuesta.status == 400
    assert respuesta.cuerpo["mensaje"] == TOKEN_INVALIDO
    assert eventos == []


def test_reset_que_no_cumple_la_politica_no_activa():
    repo = Memoria()
    tokens = tokens_de(settings_de_prueba())
    token = _alta(repo, tokens)

    respuesta, eventos = restablecer(repo, tokens, token, "corta", AHORA)

    assert respuesta.status == 400
    assert eventos == []
    assert repo.buscar_por_empleado("E001").password_hash is None


@pytest.mark.parametrize(
    "evento, estado",
    [
        ("empleado.retirado", DESACTIVADA_PERMANENTE),
        ("vacaciones.iniciadas", SUSPENDIDA_TEMPORAL),
    ],
)
def test_reset_de_una_cuenta_no_activa_responde_400(evento, estado):
    repo = Memoria()
    tokens = tokens_de(settings_de_prueba())
    _activa(repo, tokens)
    procesar_evento(repo, tokens, _evento("evt-cambio", evento, {"empleadoId": "E001"}), AHORA)
    assert repo.buscar_por_empleado("E001").estado == estado
    token, _ = tokens.reset("E001", AHORA)

    respuesta, eventos = restablecer(repo, tokens, token, "Usuario456!", AHORA)

    assert respuesta.status == 400
    assert respuesta.cuerpo["mensaje"] == ESTADO_INVALIDO
    assert eventos == []
    assert repo.buscar_por_empleado("E001").estado == estado
    assert iniciar_sesion(repo, tokens, EMAIL, "Usuario456!", AHORA).status == 401


def test_recuperar_un_retirado_responde_igual_pero_no_publica():
    repo = Memoria()
    tokens = tokens_de(settings_de_prueba())
    _activa(repo, tokens)
    procesar_evento(repo, tokens, _evento("evt-retiro", "empleado.retirado", {"empleadoId": "E001"}), AHORA)

    respuesta, eventos = recuperar(repo, tokens, EMAIL, AHORA)

    assert respuesta.status == 200
    assert respuesta.cuerpo["mensaje"] == "Si el correo existe, se envió un enlace de recuperación"
    assert eventos == []


def test_fin_de_vacaciones_sobre_una_cuenta_activa_no_publica():
    repo = Memoria()
    tokens = tokens_de(settings_de_prueba())
    _activa(repo, tokens)

    fin = procesar_evento(repo, tokens, _evento("evt-fin", "vacaciones.finalizadas", {"empleadoId": "E001"}), AHORA)

    assert fin == []
    assert repo.buscar_por_empleado("E001").estado == ACTIVA


def test_inicio_de_vacaciones_sobre_una_cuenta_pendiente_no_suspende():
    repo = Memoria()
    tokens = tokens_de(settings_de_prueba())
    _alta(repo, tokens)

    inicio = procesar_evento(repo, tokens, _evento("evt-inicio", "vacaciones.iniciadas", {"empleadoId": "E001"}), AHORA)

    assert inicio == []
    assert repo.buscar_por_empleado("E001").estado == PENDIENTE_ACTIVACION


def test_retiro_repetido_con_otro_id_no_publica_dos_veces():
    repo = Memoria()
    tokens = tokens_de(settings_de_prueba())
    _activa(repo, tokens)

    primero = procesar_evento(repo, tokens, _evento("evt-r1", "empleado.retirado", {"empleadoId": "E001"}), AHORA)
    segundo = procesar_evento(repo, tokens, _evento("evt-r2", "empleado.retirado", {"empleadoId": "E001"}), AHORA)

    assert len(primero) == 1
    assert segundo == []


def test_alta_con_email_de_otra_cuenta_no_crea_nada():
    repo = Memoria()
    tokens = tokens_de(settings_de_prueba())
    _alta(repo, tokens)

    otra = procesar_evento(
        repo,
        tokens,
        _evento("evt-alta-2", "empleado.creado", {"empleadoId": "E002", "email": EMAIL}),
        AHORA,
    )

    assert otra == []
    assert repo.buscar_por_empleado("E002") is None


@pytest.mark.parametrize(
    "envelope",
    [
        {"type": "empleado.creado", "data": {"empleadoId": "E001", "email": EMAIL}},
        {"id": "evt-x", "type": "empleado.creado", "data": {"email": EMAIL}},
        {"id": "evt-y", "type": "perfil.actualizado", "data": {"empleadoId": "E001"}},
        {"id": "evt-z", "type": "empleado.retirado", "data": {"empleadoId": "E404"}},
    ],
    ids=["sin-id", "incompleto", "tipo-ajeno", "sin-cuenta"],
)
def test_eventos_que_se_confirman_sin_efecto(envelope):
    repo = Memoria()
    tokens = tokens_de(settings_de_prueba())

    assert procesar_evento(repo, tokens, envelope, AHORA) == []
    assert repo.cuentas == {}

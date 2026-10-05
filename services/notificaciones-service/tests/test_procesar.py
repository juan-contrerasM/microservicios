import logging
from datetime import datetime, timezone

from notificaciones.modelos import Destinatario, Notificacion
from notificaciones.procesar import Resultado, procesar

AHORA = datetime(2026, 9, 26, 14, 0, 1, tzinfo=timezone.utc)
TOKEN = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJFMDAxIiwidHlwZSI6IlJFU0VUX1BBU1NXT1JEIn0.firma"


class Memoria:
    def __init__(self):
        self.procesados = set()
        self.destinatarios = {}
        self.notificaciones = []

    def ya_procesado(self, event_id: str) -> bool:
        return event_id in self.procesados

    def marcar_procesado(self, event_id: str, _cuando) -> None:
        self.procesados.add(event_id)

    def guardar_destinatario(self, destinatario: Destinatario) -> None:
        self.destinatarios[destinatario.empleado_id] = destinatario

    def buscar_destinatario(self, empleado_id: str):
        return self.destinatarios.get(empleado_id)

    def buscar_destinatario_por_email(self, email: str):
        return next((d for d in self.destinatarios.values() if d.email == email), None)

    def guardar_notificacion(self, notificacion: Notificacion) -> None:
        self.notificaciones.append(notificacion)


def evento(event_id, tipo, data, producer="auth-service"):
    return {
        "id": event_id,
        "type": tipo,
        "version": 1,
        "occurredAt": "2026-09-26T14:00:00Z",
        "producer": producer,
        "data": data,
    }


def creado(event_id="evt-1"):
    return evento(
        event_id,
        "empleado.creado",
        {
            "empleadoId": "E001",
            "nombre": "Juan",
            "apellido": "Pérez",
            "email": "juan.perez@empresa.com",
            "estado": "ACTIVO",
        },
        producer="empleados-service",
    )


def usuario_creado(event_id="evt-usuario"):
    return evento(
        event_id,
        "usuario.creado",
        {
            "empleadoId": "E001",
            "email": "juan.perez@empresa.com",
            "tokenActivacion": TOKEN,
            "expiraEn": "2026-09-26T15:00:00Z",
        },
    )


def test_empleado_creado_solo_guarda_el_destinatario():
    repo = Memoria()

    assert procesar(repo, creado(), AHORA) is Resultado.PROCESADO

    assert repo.notificaciones == []
    assert repo.destinatarios["E001"].email == "juan.perez@empresa.com"


def test_usuario_creado_deja_una_seguridad_con_el_token(caplog):
    repo = Memoria()
    caplog.set_level(logging.INFO, logger="notificaciones")

    assert procesar(repo, usuario_creado(), AHORA) is Resultado.PROCESADO
    assert procesar(repo, usuario_creado(), AHORA) is Resultado.DUPLICADO

    assert len(repo.notificaciones) == 1
    aviso = repo.notificaciones[0]
    assert aviso.tipo == "SEGURIDAD"
    assert aviso.empleado_id == "E001"
    assert aviso.destinatario == "juan.perez@empresa.com"
    assert aviso.mensaje == (
        "Para establecer o restablecer su contraseña ingrese a "
        f"https://app.empresa.com/reset?token={TOKEN}"
    )
    assert caplog.text.count("[NOTIFICACIÓN] Tipo: SEGURIDAD | Para: juan.perez@empresa.com") == 1


def test_recuperacion_busca_el_empleado_por_email():
    repo = Memoria()
    procesar(repo, creado(), AHORA)
    envelope = evento(
        "evt-rec",
        "usuario.recuperacion",
        {"email": "juan.perez@empresa.com", "tokenRecuperacion": "otro-token", "expiraEn": "2026-09-26T15:00:00Z"},
    )

    assert procesar(repo, envelope, AHORA) is Resultado.PROCESADO

    aviso = repo.notificaciones[0]
    assert aviso.tipo == "SEGURIDAD"
    assert aviso.empleado_id == "E001"
    assert aviso.mensaje.endswith("reset?token=otro-token")


def test_recuperacion_sin_destinatario_previo_se_confirma_sin_fila():
    repo = Memoria()
    envelope = evento(
        "evt-rec",
        "usuario.recuperacion",
        {"email": "nadie@empresa.com", "tokenRecuperacion": "t", "expiraEn": "2026-09-26T15:00:00Z"},
    )

    assert procesar(repo, envelope, AHORA) is Resultado.SIN_DESTINATARIO
    assert procesar(repo, envelope, AHORA) is Resultado.DUPLICADO
    assert repo.notificaciones == []


def test_cuenta_desactivada_y_activada_dejan_cuenta(caplog):
    repo = Memoria()
    caplog.set_level(logging.INFO, logger="notificaciones")
    desactivada = evento(
        "evt-des",
        "cuenta.desactivada",
        {"empleadoId": "E001", "email": "juan.perez@empresa.com", "motivo": "VACACIONES", "permanente": False},
    )
    activada = evento(
        "evt-act",
        "cuenta.activada",
        {"empleadoId": "E001", "email": "juan.perez@empresa.com", "motivo": "FIN_VACACIONES"},
    )

    assert procesar(repo, desactivada, AHORA) is Resultado.PROCESADO
    assert procesar(repo, activada, AHORA) is Resultado.PROCESADO

    assert [(n.tipo, n.mensaje) for n in repo.notificaciones] == [
        ("CUENTA", "Su cuenta fue desactivada"),
        ("CUENTA", "Bienvenido de regreso. Su cuenta ha sido reactivada"),
    ]
    assert '[NOTIFICACIÓN] Tipo: CUENTA | Para: juan.perez@empresa.com | Mensaje: "Su cuenta fue desactivada"' in caplog.text


def test_retiro_ya_no_deja_desvinculacion():
    repo = Memoria()
    envelope = evento(
        "evt-retiro",
        "empleado.retirado",
        {
            "empleadoId": "E001",
            "email": "juan.perez@empresa.com",
            "fechaRetiro": "2026-09-26T14:05:00Z",
            "motivo": "RENUNCIA",
        },
        producer="empleados-service",
    )

    assert procesar(repo, envelope, AHORA) is Resultado.IGNORADO
    assert procesar(repo, envelope, AHORA) is Resultado.DUPLICADO
    assert repo.notificaciones == []


def test_inicio_y_fin_de_vacaciones():
    repo = Memoria()
    iniciadas = evento(
        "evt-ini",
        "vacaciones.iniciadas",
        {
            "vacacionesId": "V-2026-0001",
            "empleadoId": "E001",
            "email": "juan.perez@empresa.com",
            "fechaInicio": "2026-10-05",
            "fechaFin": "2026-10-09",
        },
        producer="vacaciones-service",
    )
    finalizadas = evento(
        "evt-fin",
        "vacaciones.finalizadas",
        {
            "vacacionesId": "V-2026-0001",
            "empleadoId": "E001",
            "email": "juan.perez@empresa.com",
            "fechaFin": "2026-10-09",
        },
        producer="vacaciones-service",
    )

    assert procesar(repo, iniciadas, AHORA) is Resultado.PROCESADO
    assert procesar(repo, finalizadas, AHORA) is Resultado.PROCESADO

    assert [(n.tipo, n.mensaje) for n in repo.notificaciones] == [
        ("VACACIONES", "Sus vacaciones del 2026-10-05 al 2026-10-09 han iniciado"),
        ("VACACIONES", "Sus vacaciones finalizaron el 2026-10-09"),
    ]


def test_vacaciones_sin_email_usa_el_destinatario_del_alta():
    repo = Memoria()
    procesar(repo, creado("evt-alta"), AHORA)
    envelope = evento(
        "evt-vac",
        "vacaciones.programadas",
        {
            "vacacionesId": "V-2027-0001",
            "empleadoId": "E001",
            "fechaInicio": "2027-03-15",
            "fechaFin": "2027-03-30",
            "diasHabiles": 12,
        },
        producer="vacaciones-service",
    )

    assert procesar(repo, envelope, AHORA) is Resultado.PROCESADO

    aviso = repo.notificaciones[0]
    assert aviso.tipo == "VACACIONES"
    assert aviso.destinatario == "juan.perez@empresa.com"
    assert aviso.mensaje == "Sus vacaciones del 2027-03-15 al 2027-03-30 han sido programadas"


def test_vacaciones_de_un_desconocido_no_inventa_destinatario_y_no_se_repite():
    repo = Memoria()
    envelope = evento(
        "evt-desconocido",
        "vacaciones.programadas",
        {"vacacionesId": "V-1", "empleadoId": "E999", "fechaInicio": "2027-03-15", "fechaFin": "2027-03-30"},
        producer="vacaciones-service",
    )

    assert procesar(repo, envelope, AHORA) is Resultado.SIN_DESTINATARIO
    assert procesar(repo, envelope, AHORA) is Resultado.DUPLICADO
    assert repo.notificaciones == []


def test_envia_el_correo_una_vez_y_un_fallo_smtp_no_borra_la_fila():
    repo = Memoria()
    enviados = []

    def enviar(destino, asunto, cuerpo):
        enviados.append((destino, asunto, cuerpo))

    assert procesar(repo, usuario_creado(), AHORA, enviar) is Resultado.PROCESADO
    assert procesar(repo, usuario_creado(), AHORA, enviar) is Resultado.DUPLICADO
    assert len(enviados) == 1
    assert enviados[0][0] == "juan.perez@empresa.com"
    assert enviados[0][1] == "Seguridad"
    assert TOKEN in enviados[0][2]

    def falla(_destino, _asunto, _cuerpo):
        raise OSError("smtp caido")

    repo_fallo = Memoria()
    assert procesar(repo_fallo, usuario_creado("evt-smtp"), AHORA, falla) is Resultado.PROCESADO
    assert len(repo_fallo.notificaciones) == 1

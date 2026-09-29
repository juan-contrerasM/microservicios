import logging
from datetime import datetime, timezone

from notificaciones.modelos import Destinatario, Notificacion
from notificaciones.procesar import Resultado, procesar

AHORA = datetime(2026, 9, 26, 14, 0, 1, tzinfo=timezone.utc)


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

    def guardar_notificacion(self, notificacion: Notificacion) -> None:
        self.notificaciones.append(notificacion)


def creado(event_id="evt-1"):
    return {
        "id": event_id,
        "type": "empleado.creado",
        "version": 1,
        "occurredAt": "2026-09-26T14:00:00Z",
        "producer": "empleados-service",
        "data": {
            "empleadoId": "E001",
            "nombre": "Juan",
            "apellido": "Pérez",
            "email": "juan.perez@empresa.com",
            "estado": "ACTIVO",
        },
    }


def test_el_mismo_evento_deja_una_sola_bienvenida(caplog):
    repo = Memoria()
    caplog.set_level(logging.INFO, logger="notificaciones")

    assert procesar(repo, creado(), AHORA) is Resultado.PROCESADO
    assert procesar(repo, creado(), AHORA) is Resultado.DUPLICADO

    assert len(repo.notificaciones) == 1
    assert repo.notificaciones[0].tipo == "BIENVENIDA"
    assert repo.notificaciones[0].mensaje == "Bienvenido Juan Pérez"
    assert repo.notificaciones[0].destinatario == "juan.perez@empresa.com"
    assert caplog.text.count("[NOTIFICACIÓN] Tipo: BIENVENIDA") == 1


def test_retiro_usa_el_email_del_catalogo(caplog):
    repo = Memoria()
    caplog.set_level(logging.INFO, logger="notificaciones")
    envelope = {
        "id": "evt-retiro",
        "type": "empleado.retirado",
        "data": {
            "empleadoId": "E001",
            "email": "juan.perez@empresa.com",
            "fechaRetiro": "2026-09-26T14:05:00Z",
            "motivo": "RENUNCIA",
        },
    }

    assert procesar(repo, envelope, AHORA) is Resultado.PROCESADO

    assert repo.notificaciones[0].tipo == "DESVINCULACION"
    assert repo.notificaciones[0].mensaje == "Su cuenta ha sido desvinculada"
    assert '[NOTIFICACIÓN] Tipo: DESVINCULACION | Para: juan.perez@empresa.com' in caplog.text


def test_vacaciones_sin_email_usa_el_destinatario_del_alta():
    repo = Memoria()
    procesar(repo, creado("evt-alta"), AHORA)
    envelope = {
        "id": "evt-vac",
        "type": "vacaciones.programadas",
        "data": {
            "vacacionesId": "V-2027-0001",
            "empleadoId": "E001",
            "fechaInicio": "2027-03-15",
            "fechaFin": "2027-03-30",
            "diasHabiles": 12,
        },
    }

    assert procesar(repo, envelope, AHORA) is Resultado.PROCESADO

    aviso = repo.notificaciones[1]
    assert aviso.tipo == "VACACIONES"
    assert aviso.destinatario == "juan.perez@empresa.com"
    assert aviso.mensaje == "Sus vacaciones del 2027-03-15 al 2027-03-30 han sido programadas"


def test_vacaciones_de_un_desconocido_no_inventa_destinatario_y_no_se_repite():
    repo = Memoria()
    envelope = {
        "id": "evt-desconocido",
        "type": "vacaciones.programadas",
        "data": {"vacacionesId": "V-1", "empleadoId": "E999", "fechaInicio": "2027-03-15", "fechaFin": "2027-03-30"},
    }

    assert procesar(repo, envelope, AHORA) is Resultado.SIN_DESTINATARIO
    assert procesar(repo, envelope, AHORA) is Resultado.DUPLICADO
    assert repo.notificaciones == []


def test_envia_el_correo_una_vez_y_un_fallo_smtp_no_borra_la_fila():
    repo = Memoria()
    enviados = []

    def enviar(destino, asunto, cuerpo):
        enviados.append((destino, asunto, cuerpo))

    assert procesar(repo, creado(), AHORA, enviar) is Resultado.PROCESADO
    assert procesar(repo, creado(), AHORA, enviar) is Resultado.DUPLICADO
    assert enviados == [("juan.perez@empresa.com", "Bienvenida", "Bienvenido Juan Pérez")]

    def falla(_destino, _asunto, _cuerpo):
        raise OSError("smtp caido")

    repo_fallo = Memoria()
    assert procesar(repo_fallo, creado("evt-smtp"), AHORA, falla) is Resultado.PROCESADO
    assert len(repo_fallo.notificaciones) == 1

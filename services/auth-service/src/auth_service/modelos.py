from dataclasses import dataclass
from datetime import datetime


PENDIENTE_ACTIVACION = "PENDIENTE_ACTIVACION"
ACTIVA = "ACTIVA"
SUSPENDIDA_TEMPORAL = "SUSPENDIDA_TEMPORAL"
DESACTIVADA_PERMANENTE = "DESACTIVADA_PERMANENTE"

ROL_ADMIN = "ADMIN"
ROL_USER = "USER"


@dataclass
class Cuenta:
    empleado_id: str
    email: str
    rol: str
    estado: str
    password_hash: str | None
    creada_en: datetime


@dataclass
class EventoSaliente:
    tipo: str
    data: dict


@dataclass
class Respuesta:
    status: int
    cuerpo: dict

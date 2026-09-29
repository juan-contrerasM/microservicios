from dataclasses import dataclass
from datetime import datetime


@dataclass
class Destinatario:
    empleado_id: str
    email: str
    nombre: str
    apellido: str


@dataclass
class Notificacion:
    id: str
    tipo: str
    destinatario: str
    mensaje: str
    fecha_envio: datetime
    empleado_id: str

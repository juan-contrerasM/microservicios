from sqlalchemy import DateTime, String, Text, create_engine, select, text
from sqlalchemy.orm import DeclarativeBase, Mapped, Session, mapped_column, sessionmaker

from notificaciones.modelos import Destinatario, Notificacion


class Base(DeclarativeBase):
    pass


class DestinatarioRow(Base):
    __tablename__ = "destinatarios"

    empleado_id: Mapped[str] = mapped_column(String(64), primary_key=True)
    email: Mapped[str] = mapped_column(String(255))
    nombre: Mapped[str] = mapped_column(String(255))
    apellido: Mapped[str] = mapped_column(String(255))


class NotificacionRow(Base):
    __tablename__ = "notificaciones"

    id: Mapped[str] = mapped_column(String(36), primary_key=True)
    tipo: Mapped[str] = mapped_column(String(32))
    destinatario: Mapped[str] = mapped_column(String(255))
    mensaje: Mapped[str] = mapped_column(Text)
    fecha_envio: Mapped[object] = mapped_column(DateTime(timezone=True))
    empleado_id: Mapped[str] = mapped_column(String(64))


class EventoProcesadoRow(Base):
    __tablename__ = "eventos_procesados"

    id: Mapped[str] = mapped_column(String(64), primary_key=True)
    procesado_en: Mapped[object] = mapped_column(DateTime(timezone=True))


class SqlRepositorio:
    def __init__(self, session: Session):
        self._session = session

    def ya_procesado(self, event_id: str) -> bool:
        return self._session.get(EventoProcesadoRow, event_id) is not None

    def marcar_procesado(self, event_id: str, cuando) -> None:
        self._session.add(EventoProcesadoRow(id=event_id, procesado_en=cuando))

    def guardar_destinatario(self, destinatario: Destinatario) -> None:
        fila = self._session.get(DestinatarioRow, destinatario.empleado_id)
        if fila is None:
            self._session.add(
                DestinatarioRow(
                    empleado_id=destinatario.empleado_id,
                    email=destinatario.email,
                    nombre=destinatario.nombre,
                    apellido=destinatario.apellido,
                )
            )
            return
        fila.email = destinatario.email
        fila.nombre = destinatario.nombre
        fila.apellido = destinatario.apellido

    def buscar_destinatario(self, empleado_id: str) -> Destinatario | None:
        fila = self._session.get(DestinatarioRow, empleado_id)
        if fila is None:
            return None
        return Destinatario(fila.empleado_id, fila.email, fila.nombre, fila.apellido)

    def buscar_destinatario_por_email(self, email: str) -> Destinatario | None:
        consulta = select(DestinatarioRow).where(DestinatarioRow.email == email).limit(1)
        fila = self._session.scalars(consulta).first()
        if fila is None:
            return None
        return Destinatario(fila.empleado_id, fila.email, fila.nombre, fila.apellido)

    def guardar_notificacion(self, notificacion: Notificacion) -> None:
        self._session.add(
            NotificacionRow(
                id=notificacion.id,
                tipo=notificacion.tipo,
                destinatario=notificacion.destinatario,
                mensaje=notificacion.mensaje,
                fecha_envio=notificacion.fecha_envio,
                empleado_id=notificacion.empleado_id,
            )
        )

    def listar(self, empleado_id: str | None = None) -> list[NotificacionRow]:
        consulta = select(NotificacionRow).order_by(NotificacionRow.fecha_envio.asc())
        if empleado_id is not None:
            consulta = consulta.where(NotificacionRow.empleado_id == empleado_id)
        return list(self._session.scalars(consulta))


def crear_motor(database_url: str):
    return create_engine(database_url, pool_pre_ping=True)


def crear_sesiones(motor):
    return sessionmaker(bind=motor, expire_on_commit=False)


def base_responde(motor) -> bool:
    try:
        with motor.connect() as conexion:
            conexion.execute(text("SELECT 1"))
        return True
    except Exception:
        return False

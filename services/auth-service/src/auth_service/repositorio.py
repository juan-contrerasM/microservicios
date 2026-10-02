from sqlalchemy import DateTime, String, create_engine, select, text
from sqlalchemy.orm import DeclarativeBase, Mapped, Session, mapped_column, sessionmaker

from auth_service.modelos import Cuenta


class Base(DeclarativeBase):
    pass


class CuentaRow(Base):
    __tablename__ = "cuentas"

    empleado_id: Mapped[str] = mapped_column(String(64), primary_key=True)
    email: Mapped[str] = mapped_column(String(255))
    rol: Mapped[str] = mapped_column(String(16))
    estado: Mapped[str] = mapped_column(String(32))
    password_hash: Mapped[str] = mapped_column(String(255), nullable=True)
    creada_en: Mapped[object] = mapped_column(DateTime(timezone=True))


class EventoProcesadoRow(Base):
    __tablename__ = "eventos_procesados"

    id: Mapped[str] = mapped_column(String(64), primary_key=True)
    procesado_en: Mapped[object] = mapped_column(DateTime(timezone=True))


def _a_cuenta(fila: CuentaRow) -> Cuenta:
    return Cuenta(
        empleado_id=fila.empleado_id,
        email=fila.email,
        rol=fila.rol,
        estado=fila.estado,
        password_hash=fila.password_hash,
        creada_en=fila.creada_en,
    )


class SqlRepositorio:
    def __init__(self, session: Session):
        self._session = session

    def ya_procesado(self, event_id: str) -> bool:
        return self._session.get(EventoProcesadoRow, event_id) is not None

    def marcar_procesado(self, event_id: str, cuando) -> None:
        self._session.add(EventoProcesadoRow(id=event_id, procesado_en=cuando))

    def buscar_por_empleado(self, empleado_id: str) -> Cuenta | None:
        fila = self._session.get(CuentaRow, empleado_id)
        return _a_cuenta(fila) if fila is not None else None

    def buscar_por_email(self, email: str) -> Cuenta | None:
        fila = self._session.scalars(select(CuentaRow).where(CuentaRow.email == email)).one_or_none()
        return _a_cuenta(fila) if fila is not None else None

    def buscar_login(self, usuario: str) -> Cuenta | None:
        return self.buscar_por_email(usuario) or self.buscar_por_empleado(usuario)

    def insertar(self, cuenta: Cuenta) -> None:
        self._session.add(
            CuentaRow(
                empleado_id=cuenta.empleado_id,
                email=cuenta.email,
                rol=cuenta.rol,
                estado=cuenta.estado,
                password_hash=cuenta.password_hash,
                creada_en=cuenta.creada_en,
            )
        )

    def actualizar(self, cuenta: Cuenta) -> None:
        fila = self._session.get(CuentaRow, cuenta.empleado_id)
        if fila is None:
            raise LookupError(cuenta.empleado_id)
        fila.email = cuenta.email
        fila.rol = cuenta.rol
        fila.estado = cuenta.estado
        fila.password_hash = cuenta.password_hash


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

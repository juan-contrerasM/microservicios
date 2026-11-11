"""Checks de health con tiempo máximo (contrato C2).

Cada check corre en un hilo aparte y se espera como mucho ``timeout``
segundos: si la base de datos cuelga (TCP sin respuesta, lock), el check se
reporta DOWN y ``GET /health`` contesta igual en menos de 3 s, en vez de
quedarse esperando a que el driver se rinda.
"""

from concurrent.futures import ThreadPoolExecutor
from concurrent.futures import TimeoutError as FuturoVencido

# Hilos propios: un check colgado no ocupa el threadpool de FastAPI.
_EJECUTOR = ThreadPoolExecutor(max_workers=4, thread_name_prefix="health")


def con_timeout(funcion, timeout: float, por_defecto):
    """Ejecuta ``funcion`` y devuelve ``por_defecto`` si falla o tarda más de ``timeout``."""
    try:
        futuro = _EJECUTOR.submit(funcion)
    except RuntimeError:
        return por_defecto
    try:
        return futuro.result(timeout=timeout)
    except FuturoVencido:
        futuro.cancel()
        return por_defecto
    except Exception:
        return por_defecto

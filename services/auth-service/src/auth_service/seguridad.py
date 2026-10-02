import re
from datetime import datetime, timedelta, timezone

import bcrypt
import jwt

ALGORITMO = "HS256"
TIPO_RESET = "RESET_PASSWORD"


class TokenInvalido(Exception):
    pass


def hash_clave(clave: str) -> str:
    return bcrypt.hashpw(clave.encode("utf-8"), bcrypt.gensalt()).decode("utf-8")


def verifica_clave(clave: str, password_hash: str | None) -> bool:
    if not password_hash:
        return False
    return bcrypt.checkpw(clave.encode("utf-8"), password_hash.encode("utf-8"))


def cumple_politica(clave: str) -> bool:
    if len(clave) < 8:
        return False
    if not re.search(r"[A-Z]", clave):
        return False
    if not re.search(r"[a-z]", clave):
        return False
    if not re.search(r"\d", clave):
        return False
    return True


def iso_z(momento: datetime) -> str:
    return momento.astimezone(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


class Tokens:
    def __init__(self, secret: str, access_minutes: int, reset_minutes: int):
        self._secret = secret
        self._access_minutes = access_minutes
        self._reset_minutes = reset_minutes

    def access(self, sub: str, role: str, ahora: datetime) -> tuple[str, int]:
        expira_en = self._access_minutes * 60
        instante = int(ahora.timestamp())
        payload = {
            "sub": sub,
            "role": role,
            "iat": instante,
            "exp": instante + expira_en,
        }
        return jwt.encode(payload, self._secret, algorithm=ALGORITMO), expira_en

    def reset(self, sub: str, ahora: datetime) -> tuple[str, datetime]:
        expira = ahora + timedelta(minutes=self._reset_minutes)
        payload = {
            "sub": sub,
            "type": TIPO_RESET,
            "iat": int(ahora.timestamp()),
            "exp": int(expira.timestamp()),
        }
        return jwt.encode(payload, self._secret, algorithm=ALGORITMO), expira

    def leer_access(self, token: str) -> dict:
        payload = self._leer(token)
        if payload.get("type") == TIPO_RESET:
            raise TokenInvalido()
        if payload.get("role") not in ("ADMIN", "USER") or not payload.get("sub"):
            raise TokenInvalido()
        return payload

    def leer_reset(self, token: str) -> dict:
        payload = self._leer(token)
        if payload.get("type") != TIPO_RESET or not payload.get("sub"):
            raise TokenInvalido()
        return payload

    def _leer(self, token: str) -> dict:
        try:
            return jwt.decode(token, self._secret, algorithms=[ALGORITMO])
        except jwt.InvalidTokenError as exc:
            raise TokenInvalido() from exc

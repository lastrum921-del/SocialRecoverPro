"""Modelo de datos. PostgreSQL en producción; SQLite en pruebas y desarrollo.

El servidor guarda CUENTAS, SESIONES, CUOTAS y METADATOS DE OBJETOS (clave, tamaño, huella del objeto cifrado).
Nunca recibe contraseñas de cifrado, claves, nombres de archivos ni contenido en claro.
"""
from sqlalchemy import (BigInteger, Boolean, Column, ForeignKey, Integer, MetaData, String, Table, Text,
                        create_engine)

metadata = MetaData()

plans = Table(
    "plans", metadata,
    Column("id", String(32), primary_key=True),
    Column("name", String(64), nullable=False),
    Column("price_cents_year", Integer, nullable=False),
    Column("quota_bytes", BigInteger, nullable=False),
    Column("max_devices", Integer, nullable=False),
    Column("active", Boolean, nullable=False, default=True),
)

users = Table(
    "users", metadata,
    Column("id", Integer, primary_key=True, autoincrement=True),
    Column("email", String(254), unique=True, nullable=False),
    Column("pw_hash", String(256), nullable=False),
    Column("email_verified", Boolean, nullable=False, default=False),
    Column("plan_id", String(32), ForeignKey("plans.id"), nullable=False),
    Column("quota_bytes", BigInteger, nullable=False),
    Column("used_bytes", BigInteger, nullable=False, default=0),
    Column("totp_secret", String(64), nullable=True),
    Column("created_at", BigInteger, nullable=False),
)

sessions = Table(
    "sessions", metadata,
    Column("id", Integer, primary_key=True, autoincrement=True),
    Column("user_id", Integer, ForeignKey("users.id", ondelete="CASCADE"), nullable=False, index=True),
    Column("token_hash", String(64), unique=True, nullable=False),
    Column("device", String(120), nullable=False),
    Column("created_at", BigInteger, nullable=False),
    Column("expires_at", BigInteger, nullable=False),
    Column("revoked", Boolean, nullable=False, default=False),
)

objects = Table(
    "objects", metadata,
    Column("user_id", Integer, ForeignKey("users.id", ondelete="CASCADE"), primary_key=True),
    Column("key", String(300), primary_key=True),
    Column("size", BigInteger, nullable=False),
    Column("sha256", String(64), nullable=False),
    Column("updated_at", BigInteger, nullable=False),
)

audit = Table(
    "audit", metadata,
    Column("id", Integer, primary_key=True, autoincrement=True),
    Column("user_id", Integer, nullable=True, index=True),
    Column("event", String(64), nullable=False),
    Column("detail", Text, nullable=True),
    Column("at", BigInteger, nullable=False),
)

DEFAULT_PLANS = [
    # Precio previsto: 29,99 €/año con 5 GB de nube. Ampliaciones opcionales (precios a validar).
    {"id": "annual", "name": "SocialRecover Pro anual", "price_cents_year": 2999, "quota_bytes": 5 * 1024**3, "max_devices": 5, "active": True},
    {"id": "annual_plus100", "name": "Pro anual + 100 GB", "price_cents_year": 2999 + 1999, "quota_bytes": 105 * 1024**3, "max_devices": 5, "active": True},
    {"id": "free", "name": "Gratis (solo local)", "price_cents_year": 0, "quota_bytes": 0, "max_devices": 1, "active": True},
]


def make_engine(url: str):
    kw = {"connect_args": {"check_same_thread": False}} if url.startswith("sqlite") else {"pool_pre_ping": True}
    eng = create_engine(url, future=True, **kw)
    metadata.create_all(eng)
    with eng.begin() as c:
        for p in DEFAULT_PLANS:
            if c.execute(plans.select().where(plans.c.id == p["id"])).first() is None:
                c.execute(plans.insert().values(**p))
    return eng

"""SocialRecover Pro — API de la nube cifrada (conocimiento cero).

El cliente cifra todo ANTES de subirlo (AES-256-GCM). Este servidor solo almacena objetos opacos,
controla cuotas y gestiona cuentas. No existe ningún endpoint que permita leer el contenido de otro
usuario, y el panel de administración solo ve métricas agregadas.
"""
import base64
import hashlib
import hmac
import os
import re
import secrets
import struct
import time
from typing import Optional

from fastapi import Depends, FastAPI, Header, HTTPException, Request, Response
from pydantic import BaseModel
from sqlalchemy import and_, func, select

from . import db
from .config import Settings, settings as default_settings
from .storage import LocalStorage, S3Storage

KEY_RE = re.compile(r"^(config|snapshots/[A-Za-z0-9._-]{1,120}|blobs/[0-9a-f]{2}/[0-9a-f]{64}|locks/[A-Za-z0-9._-]{1,120})$")
EMAIL_RE = re.compile(r"^[^@\s]{1,64}@[^@\s]{1,190}\.[^@\s]{2,}$")


def now_ms() -> int:
    return int(time.time() * 1000)


# ------------------------------------------------------------------ contraseñas de cuenta y TOTP

def hash_password(pw: str) -> str:
    salt = secrets.token_bytes(16)
    h = hashlib.scrypt(pw.encode(), salt=salt, n=2**15, r=8, p=1, maxmem=64 * 1024 * 1024)
    return "scrypt$" + base64.b64encode(salt).decode() + "$" + base64.b64encode(h).decode()


def verify_password(pw: str, stored: str) -> bool:
    try:
        _, s, h = stored.split("$")
        calc = hashlib.scrypt(pw.encode(), salt=base64.b64decode(s), n=2**15, r=8, p=1, maxmem=64 * 1024 * 1024)
        return hmac.compare_digest(calc, base64.b64decode(h))
    except Exception:
        return False


def totp(secret_b32: str, at: Optional[float] = None, step: int = 30) -> str:
    """RFC 6238 (compatible con Google Authenticator, Authy, 1Password...)."""
    key = base64.b32decode(secret_b32 + "=" * (-len(secret_b32) % 8))
    counter = int((at if at is not None else time.time()) // step)
    mac = hmac.new(key, struct.pack(">Q", counter), hashlib.sha1).digest()
    o = mac[-1] & 0x0F
    code = (struct.unpack(">I", mac[o:o + 4])[0] & 0x7FFFFFFF) % 1_000_000
    return f"{code:06d}"


def totp_ok(secret: str, code: str) -> bool:
    t = time.time()
    return any(hmac.compare_digest(totp(secret, t + d * 30), code or "") for d in (-1, 0, 1))


# ------------------------------------------------------------------ aplicación

class Credentials(BaseModel):
    email: str
    password: str
    device: str = "Dispositivo"
    totp: Optional[str] = None


class TotpConfirm(BaseModel):
    secret: str
    code: str


class PlanUpdate(BaseModel):
    name: Optional[str] = None
    price_cents_year: Optional[int] = None
    quota_bytes: Optional[int] = None
    max_devices: Optional[int] = None
    active: Optional[bool] = None


def create_app(cfg: Settings = default_settings, storage=None) -> FastAPI:
    if cfg.database_url.startswith("sqlite:///./"):
        os.makedirs(os.path.dirname(cfg.database_url.replace("sqlite:///", "")) or ".", exist_ok=True)
    engine = db.make_engine(cfg.database_url)
    store = storage or (S3Storage(cfg.s3_bucket, cfg.s3_endpoint, cfg.s3_region) if cfg.storage_backend == "s3" else LocalStorage(cfg.storage_dir))
    failures: dict = {}
    app = FastAPI(title="SocialRecover Pro API", version="1.0.0")

    def log(conn, user_id, event, detail=None):
        conn.execute(db.audit.insert().values(user_id=user_id, event=event, detail=detail, at=now_ms()))

    def new_session(conn, user_id: int, device: str) -> str:
        token = secrets.token_urlsafe(32)
        conn.execute(db.sessions.insert().values(
            user_id=user_id, token_hash=hashlib.sha256(token.encode()).hexdigest(), device=device[:120],
            created_at=now_ms(), expires_at=now_ms() + cfg.token_days * 86_400_000, revoked=False))
        return token

    def current_user(authorization: str = Header(default="")) -> dict:
        if not authorization.startswith("Bearer "):
            raise HTTPException(401, "Falta el token")
        th = hashlib.sha256(authorization[7:].encode()).hexdigest()
        with engine.begin() as c:
            row = c.execute(select(db.sessions, db.users).join(db.users, db.users.c.id == db.sessions.c.user_id)
                            .where(db.sessions.c.token_hash == th)).mappings().first()
        if row is None or row["revoked"] or row["expires_at"] < now_ms():
            raise HTTPException(401, "Sesión no válida o caducada")
        return dict(row)

    def check_key(key: str):
        if not KEY_RE.match(key):
            raise HTTPException(400, "Clave de objeto no válida")

    # ---------------- cuentas

    @app.post("/v1/auth/register", status_code=201)
    def register(cr: Credentials):
        email = cr.email.strip().lower()
        if not EMAIL_RE.match(email):
            raise HTTPException(400, "Correo no válido")
        if len(cr.password) < 10:
            raise HTTPException(400, "La contraseña de la cuenta debe tener al menos 10 caracteres")
        with engine.begin() as c:
            if c.execute(select(db.users.c.id).where(db.users.c.email == email)).first():
                raise HTTPException(409, "Ya existe una cuenta con ese correo")
            plan = c.execute(select(db.plans).where(db.plans.c.id == "annual")).mappings().first()
            uid = c.execute(db.users.insert().values(
                email=email, pw_hash=hash_password(cr.password), email_verified=False, plan_id=plan["id"],
                quota_bytes=cfg.default_quota, used_bytes=0, created_at=now_ms())).inserted_primary_key[0]
            log(c, uid, "register")
            # Envío del correo de verificación: pendiente de integrar un proveedor SMTP (ver docs).
            return {"token": new_session(c, uid, cr.device), "email_verified": False}

    @app.post("/v1/auth/login")
    def login(cr: Credentials):
        email = cr.email.strip().lower()
        f = failures.get(email, (0, 0))
        if f[0] >= cfg.max_login_failures and now_ms() - f[1] < 15 * 60_000:
            raise HTTPException(429, "Demasiados intentos. Espera 15 minutos.")
        with engine.begin() as c:
            u = c.execute(select(db.users).where(db.users.c.email == email)).mappings().first()
            if u is None or not verify_password(cr.password, u["pw_hash"]):
                failures[email] = (f[0] + 1, now_ms())
                log(c, u["id"] if u else None, "login_failed")
                raise HTTPException(401, "Correo o contraseña incorrectos")
            if u["totp_secret"]:
                if not cr.totp:
                    raise HTTPException(401, "Se necesita el código de verificación (MFA)")
                if not totp_ok(u["totp_secret"], cr.totp):
                    failures[email] = (f[0] + 1, now_ms())
                    raise HTTPException(401, "Código de verificación incorrecto")
            failures.pop(email, None)
            log(c, u["id"], "login", cr.device[:120])
            return {"token": new_session(c, u["id"], cr.device)}

    @app.post("/v1/auth/logout", status_code=204)
    def logout(authorization: str = Header(default=""), user=Depends(current_user)):
        th = hashlib.sha256(authorization[7:].encode()).hexdigest()
        with engine.begin() as c:
            c.execute(db.sessions.update().where(db.sessions.c.token_hash == th).values(revoked=True))
        return Response(status_code=204)

    @app.get("/v1/sessions")
    def list_sessions(user=Depends(current_user)):
        with engine.begin() as c:
            rows = c.execute(select(db.sessions.c.id, db.sessions.c.device, db.sessions.c.created_at)
                             .where(and_(db.sessions.c.user_id == user["user_id"], db.sessions.c.revoked.is_(False)))).mappings().all()
        return [dict(r) for r in rows]

    @app.delete("/v1/sessions/{sid}", status_code=204)
    def revoke_session(sid: int, user=Depends(current_user)):
        with engine.begin() as c:
            c.execute(db.sessions.update().where(and_(db.sessions.c.id == sid, db.sessions.c.user_id == user["user_id"])).values(revoked=True))
            log(c, user["user_id"], "session_revoked", str(sid))
        return Response(status_code=204)

    @app.post("/v1/auth/mfa/setup")
    def mfa_setup(user=Depends(current_user)):
        secret = base64.b32encode(secrets.token_bytes(20)).decode().rstrip("=")
        return {"secret": secret, "otpauth": f"otpauth://totp/SocialRecover%20Pro:{user['email']}?secret={secret}&issuer=SocialRecover%20Pro"}

    @app.post("/v1/auth/mfa/enable", status_code=204)
    def mfa_enable(body: TotpConfirm, user=Depends(current_user)):
        if not totp_ok(body.secret, body.code):
            raise HTTPException(400, "Código incorrecto")
        with engine.begin() as c:
            c.execute(db.users.update().where(db.users.c.id == user["user_id"]).values(totp_secret=body.secret))
            log(c, user["user_id"], "mfa_enabled")
        return Response(status_code=204)

    @app.delete("/v1/account", status_code=204)
    def delete_account(user=Depends(current_user)):
        """Derecho de supresión (art. 17 RGPD): borra objetos, sesiones y cuenta."""
        uid = user["user_id"]
        store.delete_all(uid)
        with engine.begin() as c:
            c.execute(db.objects.delete().where(db.objects.c.user_id == uid))
            c.execute(db.sessions.delete().where(db.sessions.c.user_id == uid))
            c.execute(db.users.delete().where(db.users.c.id == uid))
            log(c, None, "account_deleted", str(uid))
        return Response(status_code=204)

    @app.get("/v1/usage")
    def usage(user=Depends(current_user)):
        return {"used_bytes": user["used_bytes"], "quota_bytes": user["quota_bytes"], "plan": user["plan_id"]}

    # ---------------- objetos cifrados

    @app.put("/v1/repo/{key:path}", status_code=201)
    async def put_object(key: str, request: Request, x_content_sha256: str = Header(default=""), user=Depends(current_user)):
        check_key(key)
        data = await request.body()
        if len(data) > cfg.max_object:
            raise HTTPException(413, "Objeto demasiado grande")
        sha = hashlib.sha256(data).hexdigest()
        if not hmac.compare_digest(sha, x_content_sha256.lower()):
            raise HTTPException(400, "La huella no coincide: el objeto llegó dañado")
        uid = user["user_id"]
        with engine.begin() as c:
            old = c.execute(select(db.objects.c.size).where(and_(db.objects.c.user_id == uid, db.objects.c.key == key))).scalar()
            delta = len(data) - (old or 0)
            # Reserva atómica de cuota: falla si se superaría.
            res = c.execute(db.users.update().where(and_(db.users.c.id == uid, db.users.c.used_bytes + delta <= db.users.c.quota_bytes))
                            .values(used_bytes=db.users.c.used_bytes + delta))
            if res.rowcount == 0:
                raise HTTPException(507, "Cuota de almacenamiento superada")
        try:
            store.put(uid, key, data)
        except Exception:
            with engine.begin() as c:
                c.execute(db.users.update().where(db.users.c.id == uid).values(used_bytes=db.users.c.used_bytes - delta))
            raise HTTPException(503, "Almacenamiento no disponible")
        with engine.begin() as c:
            if old is None:
                c.execute(db.objects.insert().values(user_id=uid, key=key, size=len(data), sha256=sha, updated_at=now_ms()))
            else:
                c.execute(db.objects.update().where(and_(db.objects.c.user_id == uid, db.objects.c.key == key))
                          .values(size=len(data), sha256=sha, updated_at=now_ms()))
        return {"key": key, "size": len(data)}

    @app.get("/v1/repo/{key:path}")
    def get_object(key: str, user=Depends(current_user)):
        check_key(key)
        data = store.get(user["user_id"], key)
        if data is None:
            raise HTTPException(404, "No existe")
        return Response(data, media_type="application/octet-stream", headers={"X-Content-SHA256": hashlib.sha256(data).hexdigest()})

    @app.head("/v1/repo/{key:path}")
    def head_object(key: str, user=Depends(current_user)):
        check_key(key)
        with engine.begin() as c:
            size = c.execute(select(db.objects.c.size).where(and_(db.objects.c.user_id == user["user_id"], db.objects.c.key == key))).scalar()
        if size is None:
            raise HTTPException(404)
        return Response(headers={"Content-Length": str(size)})

    @app.delete("/v1/repo/{key:path}", status_code=204)
    def delete_object(key: str, user=Depends(current_user)):
        check_key(key)
        uid = user["user_id"]
        with engine.begin() as c:
            size = c.execute(select(db.objects.c.size).where(and_(db.objects.c.user_id == uid, db.objects.c.key == key))).scalar()
            if size is not None:
                c.execute(db.objects.delete().where(and_(db.objects.c.user_id == uid, db.objects.c.key == key)))
                c.execute(db.users.update().where(db.users.c.id == uid).values(used_bytes=db.users.c.used_bytes - size))
        store.delete(uid, key)
        return Response(status_code=204)

    @app.get("/v1/repo")
    def list_objects(prefix: str = "", after: str = "", limit: int = 5000, user=Depends(current_user)):
        limit = max(1, min(limit, 10000))
        with engine.begin() as c:
            rows = c.execute(select(db.objects.c.key, db.objects.c.size)
                             .where(and_(db.objects.c.user_id == user["user_id"], db.objects.c.key.like(prefix.replace("%", "") + "%"), db.objects.c.key > after))
                             .order_by(db.objects.c.key).limit(limit)).all()
        items = [{"key": k, "size": s} for k, s in rows]
        return {"items": items, "next": items[-1]["key"] if len(items) == limit else None}

    # ---------------- administración (métricas agregadas; nunca contenido)

    def admin(x_admin_token: str = Header(default="")):
        if not cfg.admin_token or not hmac.compare_digest(x_admin_token, cfg.admin_token):
            raise HTTPException(403, "Solo administración")

    @app.get("/v1/admin/stats", dependencies=[Depends(admin)])
    def admin_stats():
        with engine.begin() as c:
            n = c.execute(select(func.count()).select_from(db.users)).scalar()
            used = c.execute(select(func.coalesce(func.sum(db.users.c.used_bytes), 0))).scalar()
            quota = c.execute(select(func.coalesce(func.sum(db.users.c.quota_bytes), 0))).scalar()
            by_plan = c.execute(select(db.users.c.plan_id, func.count()).group_by(db.users.c.plan_id)).all()
            plist = c.execute(select(db.plans)).mappings().all()
        revenue = sum(p["price_cents_year"] * dict(by_plan).get(p["id"], 0) for p in plist)
        return {"customers": n, "used_bytes": used, "quota_bytes_sold": quota,
                "customers_by_plan": dict(by_plan), "annual_revenue_cents": revenue, "plans": [dict(p) for p in plist]}

    @app.put("/v1/admin/plans/{pid}", dependencies=[Depends(admin)])
    def admin_plan(pid: str, body: PlanUpdate):
        vals = {k: v for k, v in body.model_dump().items() if v is not None}
        with engine.begin() as c:
            if c.execute(select(db.plans.c.id).where(db.plans.c.id == pid)).first() is None:
                c.execute(db.plans.insert().values(id=pid, name=vals.get("name", pid), price_cents_year=vals.get("price_cents_year", 0),
                                                   quota_bytes=vals.get("quota_bytes", 0), max_devices=vals.get("max_devices", 1), active=vals.get("active", True)))
            elif vals:
                c.execute(db.plans.update().where(db.plans.c.id == pid).values(**vals))
            log(c, None, "plan_updated", pid)
        return {"ok": True}

    @app.get("/healthz")
    def health():
        return {"ok": True}

    return app


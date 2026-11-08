"""Pruebas del backend con datos sintéticos (sin datos personales reales)."""
import hashlib
import os
import time

import boto3
import pytest
from fastapi.testclient import TestClient
from moto import mock_aws

from app.config import Settings
from app.main import create_app, totp
from app.storage import S3Storage


def make(tmp_path, quota=10_000, **env):
    cfg = Settings()
    cfg.database_url = f"sqlite:///{tmp_path}/t.db"
    cfg.storage_dir = str(tmp_path / "obj")
    cfg.default_quota = quota
    cfg.admin_token = "admin-secreto"
    for k, v in env.items():
        setattr(cfg, k, v)
    return TestClient(create_app(cfg)), cfg


def reg(c, email="ana@example.com", pw="contraseña-larga-1"):
    r = c.post("/v1/auth/register", json={"email": email, "password": pw, "device": "Pixel"})
    assert r.status_code == 201, r.text
    return {"Authorization": "Bearer " + r.json()["token"]}


def put(c, h, key, data):
    return c.put(f"/v1/repo/{key}", content=data, headers={**h, "X-Content-SHA256": hashlib.sha256(data).hexdigest()})


BLOB = "blobs/ab/" + "ab" * 32


def test_register_login_and_wrong_password(tmp_path):
    c, _ = make(tmp_path)
    reg(c)
    assert c.post("/v1/auth/register", json={"email": "ana@example.com", "password": "otra-contraseña"}).status_code == 409
    assert c.post("/v1/auth/login", json={"email": "ana@example.com", "password": "mala-contraseña"}).status_code == 401
    assert c.post("/v1/auth/login", json={"email": "ANA@example.com", "password": "contraseña-larga-1"}).status_code == 200
    assert c.post("/v1/auth/register", json={"email": "x@y.es", "password": "corta"}).status_code == 400


def test_bruteforce_is_throttled(tmp_path):
    c, _ = make(tmp_path)
    reg(c)
    for _ in range(5):
        c.post("/v1/auth/login", json={"email": "ana@example.com", "password": "mal"})
    assert c.post("/v1/auth/login", json={"email": "ana@example.com", "password": "contraseña-larga-1"}).status_code == 429


def test_object_roundtrip_integrity_and_listing(tmp_path):
    c, _ = make(tmp_path)
    h = reg(c)
    assert put(c, h, "config", b"cfg").status_code == 201
    assert put(c, h, BLOB, b"x" * 100).status_code == 201
    assert c.get(f"/v1/repo/{BLOB}", headers=h).content == b"x" * 100
    assert c.head(f"/v1/repo/{BLOB}", headers=h).status_code == 200
    bad = c.put("/v1/repo/snapshots/s1", content=b"abc", headers={**h, "X-Content-SHA256": "00" * 32})
    assert bad.status_code == 400, "un objeto dañado en tránsito se rechaza"
    assert c.put("/v1/repo/../etc/passwd", content=b"a", headers={**h, "X-Content-SHA256": hashlib.sha256(b"a").hexdigest()}).status_code in (400, 404)
    items = c.get("/v1/repo", params={"prefix": "blobs/"}, headers=h).json()["items"]
    assert items == [{"key": BLOB, "size": 100}]
    assert c.get("/v1/usage", headers=h).json()["used_bytes"] == 103
    assert c.delete(f"/v1/repo/{BLOB}", headers=h).status_code == 204
    assert c.get("/v1/usage", headers=h).json()["used_bytes"] == 3
    assert c.get(f"/v1/repo/{BLOB}", headers=h).status_code == 404


def test_quota_is_enforced(tmp_path):
    c, _ = make(tmp_path, quota=1000)
    h = reg(c)
    assert put(c, h, BLOB, b"a" * 900).status_code == 201
    r = put(c, h, "blobs/cd/" + "cd" * 32, b"b" * 200)
    assert r.status_code == 507
    assert put(c, h, BLOB, b"a" * 950).status_code == 201, "sobrescribir solo cuenta la diferencia"
    assert c.get("/v1/usage", headers=h).json() == {"used_bytes": 950, "quota_bytes": 1000, "plan": "annual"}


def test_users_are_isolated_and_admin_cannot_read_content(tmp_path):
    c, _ = make(tmp_path)
    ha = reg(c, "ana@example.com")
    hb = reg(c, "beto@example.com")
    put(c, ha, "config", b"de-ana")
    assert c.get("/v1/repo/config", headers=hb).status_code == 404
    assert c.get("/v1/repo/config", headers={"Authorization": "Bearer inventado"}).status_code == 401
    stats = c.get("/v1/admin/stats", headers={"X-Admin-Token": "admin-secreto"}).json()
    assert stats["customers"] == 2 and stats["annual_revenue_cents"] == 2 * 2999
    assert c.get("/v1/admin/stats", headers={"X-Admin-Token": "no"}).status_code == 403
    assert "/v1/admin/repo" not in [r.path for r in c.app.routes], "no hay endpoint de administración para leer objetos"
    assert c.put("/v1/admin/plans/annual", json={"quota_bytes": 10 * 1024**3}, headers={"X-Admin-Token": "admin-secreto"}).status_code == 200


def test_mfa_totp(tmp_path):
    c, _ = make(tmp_path)
    h = reg(c)
    sec = c.post("/v1/auth/mfa/setup", headers=h).json()["secret"]
    assert c.post("/v1/auth/mfa/enable", json={"secret": sec, "code": "000000" if totp(sec) != "000000" else "111111"}, headers=h).status_code == 400
    assert c.post("/v1/auth/mfa/enable", json={"secret": sec, "code": totp(sec)}, headers=h).status_code == 204
    cred = {"email": "ana@example.com", "password": "contraseña-larga-1"}
    assert c.post("/v1/auth/login", json=cred).status_code == 401
    assert c.post("/v1/auth/login", json={**cred, "totp": totp(sec)}).status_code == 200


def test_sessions_revocation_and_account_deletion(tmp_path):
    c, cfg = make(tmp_path)
    h = reg(c)
    put(c, h, BLOB, b"datos")
    sid = c.get("/v1/sessions", headers=h).json()[0]["id"]
    h2 = {"Authorization": "Bearer " + c.post("/v1/auth/login", json={"email": "ana@example.com", "password": "contraseña-larga-1"}).json()["token"]}
    assert c.delete(f"/v1/sessions/{sid}", headers=h2).status_code == 204
    assert c.get("/v1/usage", headers=h).status_code == 401, "la sesión revocada deja de funcionar"
    assert c.delete("/v1/account", headers=h2).status_code == 204
    assert not os.path.exists(os.path.join(cfg.storage_dir, "1")), "los objetos se borran del almacenamiento"
    assert c.post("/v1/auth/login", json={"email": "ana@example.com", "password": "contraseña-larga-1"}).status_code == 401


@mock_aws
def test_s3_storage_backend(tmp_path):
    s3 = boto3.client("s3", region_name="eu-west-1")
    s3.create_bucket(Bucket="srp", CreateBucketConfiguration={"LocationConstraint": "eu-west-1"})
    store = S3Storage("srp", client=s3)
    cfg = Settings()
    cfg.database_url = f"sqlite:///{tmp_path}/t.db"
    cfg.default_quota = 10_000
    c = TestClient(create_app(cfg, storage=store))
    h = reg(c)
    assert put(c, h, BLOB, b"cifrado").status_code == 201
    assert c.get(f"/v1/repo/{BLOB}", headers=h).content == b"cifrado"
    assert s3.list_objects_v2(Bucket="srp")["KeyCount"] == 1
    c.delete("/v1/account", headers=h)
    assert s3.list_objects_v2(Bucket="srp")["KeyCount"] == 0

"""Almacenamiento de objetos cifrados: carpeta local o cualquier servicio compatible con S3."""
import os
import tempfile
from typing import Optional


class LocalStorage:
    def __init__(self, root: str):
        self.root = os.path.abspath(root)
        os.makedirs(self.root, exist_ok=True)

    def _path(self, user_id: int, key: str) -> str:
        p = os.path.abspath(os.path.join(self.root, str(user_id), key))
        if not p.startswith(os.path.join(self.root, str(user_id)) + os.sep):
            raise ValueError("clave no válida")
        return p

    def put(self, user_id: int, key: str, data: bytes) -> None:
        p = self._path(user_id, key)
        os.makedirs(os.path.dirname(p), exist_ok=True)
        fd, tmp = tempfile.mkstemp(dir=os.path.dirname(p), suffix=".tmp")
        with os.fdopen(fd, "wb") as f:
            f.write(data)
            f.flush()
            os.fsync(f.fileno())
        os.replace(tmp, p)  # atómico

    def get(self, user_id: int, key: str) -> Optional[bytes]:
        try:
            with open(self._path(user_id, key), "rb") as f:
                return f.read()
        except FileNotFoundError:
            return None

    def delete(self, user_id: int, key: str) -> None:
        try:
            os.remove(self._path(user_id, key))
        except FileNotFoundError:
            pass

    def delete_all(self, user_id: int) -> None:
        import shutil
        shutil.rmtree(os.path.join(self.root, str(user_id)), ignore_errors=True)


class S3Storage:
    """Compatible con AWS S3, MinIO, Wasabi, Backblaze B2 (API S3), Scaleway, OVH..."""

    def __init__(self, bucket: str, endpoint: Optional[str] = None, region: str = "eu-west-1", client=None):
        import boto3
        self.bucket = bucket
        self.s3 = client or boto3.client("s3", endpoint_url=endpoint, region_name=region)

    def _k(self, user_id: int, key: str) -> str:
        if ".." in key or key.startswith("/"):
            raise ValueError("clave no válida")
        return f"u{user_id}/{key}"

    def put(self, user_id: int, key: str, data: bytes) -> None:
        self.s3.put_object(Bucket=self.bucket, Key=self._k(user_id, key), Body=data)  # PUT de S3 es atómico

    def get(self, user_id: int, key: str) -> Optional[bytes]:
        try:
            return self.s3.get_object(Bucket=self.bucket, Key=self._k(user_id, key))["Body"].read()
        except self.s3.exceptions.NoSuchKey:
            return None

    def delete(self, user_id: int, key: str) -> None:
        self.s3.delete_object(Bucket=self.bucket, Key=self._k(user_id, key))

    def delete_all(self, user_id: int) -> None:
        pag = self.s3.get_paginator("list_objects_v2")
        for page in pag.paginate(Bucket=self.bucket, Prefix=f"u{user_id}/"):
            keys = [{"Key": o["Key"]} for o in page.get("Contents", [])]
            for i in range(0, len(keys), 1000):
                self.s3.delete_objects(Bucket=self.bucket, Delete={"Objects": keys[i:i + 1000]})

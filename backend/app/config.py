"""Configuración por variables de entorno (12-factor)."""
import os


class Settings:
    def __init__(self) -> None:
        self.database_url = os.getenv("SRP_DATABASE_URL", "sqlite:///./data/srp.db")
        self.storage_backend = os.getenv("SRP_STORAGE", "local")  # local | s3
        self.storage_dir = os.getenv("SRP_STORAGE_DIR", "./data/objects")
        self.s3_endpoint = os.getenv("SRP_S3_ENDPOINT")  # None = AWS; o URL de MinIO / Wasabi / etc.
        self.s3_bucket = os.getenv("SRP_S3_BUCKET", "socialrecover")
        self.s3_region = os.getenv("SRP_S3_REGION", "eu-west-1")
        # Cuota inicial del plan anual (29,99 €): 5 GB, configurable y sujeta a validación de costes.
        self.default_quota = int(os.getenv("SRP_DEFAULT_QUOTA_BYTES", str(5 * 1024**3)))
        self.max_object = int(os.getenv("SRP_MAX_OBJECT_BYTES", str(32 * 1024**2)))
        self.token_days = int(os.getenv("SRP_TOKEN_DAYS", "90"))
        self.admin_token = os.getenv("SRP_ADMIN_TOKEN")  # sin valor = panel de administración desactivado
        self.max_login_failures = int(os.getenv("SRP_MAX_LOGIN_FAILURES", "5"))


settings = Settings()

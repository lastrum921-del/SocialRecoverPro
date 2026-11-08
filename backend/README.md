# Backend de SocialRecover Pro (nube cifrada)

API REST (FastAPI) para cuentas, cuotas y almacenamiento de objetos **ya cifrados en el dispositivo**.
El servidor no conoce contraseñas de cifrado, claves, nombres de archivos ni contenido.

## Desarrollo

```bash
python3 -m venv .venv && . .venv/bin/activate
pip install -r requirements-dev.txt
pytest -q                                  # 8 pruebas
uvicorn --factory app.main:create_app --port 8080
```

## Producción (Docker)

```bash
export SRP_ADMIN_TOKEN=$(openssl rand -hex 32) POSTGRES_PASSWORD=... MINIO_PASSWORD=...
docker compose up -d --build
```
Crear el bucket `socialrecover` en MinIO (consola en :9001) o apuntar `SRP_S3_ENDPOINT` a otro proveedor S3.
Poner TLS delante (Caddy/Traefik). **Docker Compose no se ha probado en el entorno de desarrollo** (sin Docker);
la API sí se ha probado con SQLite, almacenamiento local y S3 simulado (moto).

## Endpoints

| Método | Ruta | Uso |
|---|---|---|
| POST | /v1/auth/register, /v1/auth/login, /v1/auth/logout | Cuenta y sesiones (scrypt, bloqueo tras 5 fallos) |
| POST | /v1/auth/mfa/setup, /v1/auth/mfa/enable | Doble factor TOTP |
| GET/DELETE | /v1/sessions[/{id}] | Ver y revocar dispositivos |
| DELETE | /v1/account | Borrado total (RGPD art. 17) |
| GET | /v1/usage | Espacio usado y cuota (5 GB por defecto) |
| PUT/GET/HEAD/DELETE | /v1/repo/{clave} | Objetos cifrados (con SHA-256 de integridad) |
| GET | /v1/repo?prefix= | Listado paginado |
| GET/PUT | /v1/admin/stats, /v1/admin/plans/{id} | Panel: métricas agregadas y planes (cabecera X-Admin-Token) |

Pendiente: envío real del correo de verificación (SMTP), cobro (Play Billing / App Store / pasarela web),
interfaz web del panel, copias de seguridad de la base de datos y monitorización.

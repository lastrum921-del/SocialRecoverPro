# Estado de la versión 1.1.0-beta1 (Módulo Premium)

Leyenda: ✅ implementado y probado · 🟢 implementado y compilado, pendiente de probar en dispositivo · 🟡 implementado, sin compilar (interfaz Compose) · ⏳ pendiente · ⛔ no es técnicamente posible

**Resumen de lo probado (08/11/2026):** 51 pruebas del motor en Kotlin (3 de ellas contra el servidor real con cortes de red simulados) + 8 pruebas del backend en Python, todas superadas; recorrido completo del componente de escritorio. Salidas reales: `resultados-pruebas.txt`, `resultados-escritorio.txt`.
**No se ha generado APK**: el entorno no tenía acceso a los repositorios de Google/Maven. La interfaz Android está escrita pero no compilada.

## Por capas

| Capa | Estado |
|---|---|
| Motor `:core` (copias v1, bóveda, Máquina del tiempo, redes, recuperación guiada, detector, nube cliente) | ✅ |
| Backend de la nube (FastAPI, SQLite, almacenamiento local y S3 simulado) | ✅ |
| Backend con PostgreSQL + MinIO en Docker | ⏳ escrito (`docker-compose.yml`), no probado (sin Docker en el entorno) |
| Componente de escritorio / Windows (CLI + tarea programada) | ✅ en JVM/Linux · ⏳ probar en Windows real |
| Capa de datos Android (SAF, bóveda en carpeta, llavero, notificaciones, servicio de bóvedas) | 🟢 compilada contra `android.jar` (API 34) |
| ProtectionWorker (WorkManager), estado y pantallas Compose | 🟡 |
| Interfaz gráfica de escritorio | ⏳ |
| App iPhone | ⏳ (plan en `IPHONE.md`) |

## Las cinco funciones

| Función | Motor | Android | Escritorio |
|---|---|---|---|
| 1. Máquina del tiempo (calendario, día/mes/año, versiones, filtros, recuperar) | ✅ | 🟡 `TimeMachineScreen`, `VersionsScreen` | ✅ `calendar`, `files`, `restore` |
| 2. Rescate de recuerdos (9 redes + genérico, buscador, exportar CSV/HTML) | ✅ | 🟡 `SocialHubScreen` | ✅ `import-social` (búsqueda: vía `find`) |
| 3. Detector de desprotegidos (avisos, periodicidad, categorías, notificaciones) | ✅ | 🟡 `StatusScreen` · 🟢 `Notifier` · 🟡 worker | ✅ `status` |
| 4. Nube inteligente (híbrido, incremental, duplicados, compresión, reanudación, retención, cuotas) | ✅ | 🟡 `SmartCloudScreen` · 🟢 `VaultService` | ✅ (bóveda local o URL de la nube) |
| 5. Recuperación guiada en 3 pasos (lenguaje natural, disponibilidad, alternativas) | ✅ | 🟡 `GuidedRecoveryScreen` | ✅ `find` |

## Pendiente

| Elemento | Notas |
|---|---|
| Compilar y probar la app Android en dispositivos | Lista en `PRUEBAS.md` |
| Cobro de la suscripción (29,99 €/año) | Google Play Billing / StoreKit / pasarela web; el servidor ya tiene planes y cuotas |
| Correo de verificación y recuperación de cuenta | Requiere proveedor SMTP |
| Interfaz web del panel de administración | La API (`/v1/admin/*`) existe y está probada |
| Vista previa de fotos y vídeos dentro de la copia | Hoy: comparación de documentos de texto |
| Fragmentación por contenido (CDC) | Hoy bloques fijos de 1 MiB: insertar datos al principio de un archivo grande obliga a regrabarlo |
| Ejecución de copias largas como servicio en primer plano (Android 14+) | Necesario para copias de varios GB en segundo plano |
| DPAPI / Administrador de credenciales en Windows | Hoy la copia programada usa una variable de entorno |
| Recuperación de archivos borrados de discos NTFS | Fase Windows |
| Auditoría de seguridad externa | Antes del lanzamiento |

## No es técnicamente posible (y la app lo explica)

| Petición | Motivo |
|---|---|
| Recuperar contenido borrado definitivamente de los servidores de una red social | Solo existe en la plataforma |
| Obtener los vídeos de TikTok o los recuerdos de Snapchat desde su exportación | Esas exportaciones solo contienen enlaces → «solo registro de actividad» |
| Fechas de los «Me gusta» de X | La exportación no las incluye |
| Supervisar todo el teléfono o leer datos de otras apps | Android/iOS no lo permiten y no se debe hacer; solo carpetas autorizadas |
| Copias automáticas a una hora exacta en Android/iOS | El sistema decide cuándo ejecutar tareas en segundo plano |
| Recuperar sin contraseña | Cifrado de extremo a extremo: nadie puede, tampoco SocialRecover Pro |

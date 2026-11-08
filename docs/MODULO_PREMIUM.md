# Módulo Premium de Recuperación Inteligente (v1.1)

Precio previsto: **29,99 €/año**, con **5 GB** de nube cifrada (cuota configurable en el servidor, sujeta a validación de costes) y copias locales sin límite artificial. Ampliaciones de espacio como planes opcionales (`backend/app/db.py`, tabla `plans`).

## Cómo encaja en la arquitectura

```
core/ (Kotlin puro, compartido por Android y escritorio — sin duplicar componentes)
├── vault/      Bóveda incremental: bloques deduplicados, comprimidos y cifrados; instantáneas;
│               restauración; verificación; retención; versiones; Máquina del tiempo; híbrido; migración v1
├── social/     Importadores modulares (Meta, TikTok, YouTube, X, Reddit, Snapchat, Pinterest, genérico),
│               importación a la bóveda, buscador y exportación CSV/HTML
├── recovery/   Intérprete de frases en español + búsqueda unificada (archivos + redes) con disponibilidad
├── status/     Detector de archivos desprotegidos + escáner de cambios
├── cloud/      Cliente de la nube (HTTP, sin dependencias) y cuenta
└── (v1) crypto/, engine/  — siguen leyendo las copias 1.0
backend/        API FastAPI + PostgreSQL/SQLite + almacenamiento local o S3
desktop/        Componente Windows/escritorio (línea de comandos + tarea programada)
app/            Android: SAF, llavero del sistema, WorkManager, 5 pantallas nuevas
```

## Función 1 · Máquina del tiempo digital

| Requisito | Implementación | Prueba |
|---|---|---|
| Elegir día, mes o año | `TimeMachine.daysWithBackups / monthsWithBackups / years / filesBetween` | `timeMachineCalendarAndFilters` |
| Calendario con días marcados | `TimeMachineScreen` + `CalendarGrid` (Android); `srp calendar` (escritorio) | transcript `resultados-escritorio.txt` |
| Archivos de una fecha + «Recuperar» | `filesOn` + `VaultRestore` | `incrementalBackupStoresOnlyChanges` |
| Comparar versiones | `Versions.history / compare / lineDiff` (texto línea a línea) | `versionHistoryAndComparison` |
| Filtrar por dispositivo, plataforma, categoría | `TimeFilter` | `timeMachineCalendarAndFilters` |
| Nunca mostrar como recuperable lo que no está | `LoadedSnapshot.isAvailable` (todos los bloques presentes) | `damagedAndMissingBlobsAreDetectedAndNeverOfferedAsRecoverable` |

## Función 2 · Rescate de recuerdos de redes sociales

Importadores (`core/social/Importers.kt`), registrables en `Importers.all`:

| Red | Formato | Qué se indexa | Archivos multimedia |
|---|---|---|---|
| Instagram / Threads | Meta JSON | publicaciones, reels, stories, comentarios, «Me gusta», guardados, hilos | Sí (vienen en el ZIP) |
| Facebook | Meta JSON | publicaciones, comentarios, reacciones, fotos | Sí |
| TikTok | user_data JSON | vídeos publicados (enlace), comentarios, «Me gusta», favoritos, historial | **No**: TikTok solo da enlaces → «solo registro» |
| YouTube | Google Takeout | historial, comentarios (CSV), «Me gusta», vídeos subidos | Sí si se incluyeron |
| X | archivo `tweets.js`, `like.js` | publicaciones, respuestas, «Me gusta» (sin fecha), fotos | Sí |
| Reddit | CSV (RGPD) | publicaciones, comentarios, guardados, votos | No |
| Snapchat | JSON | recuerdos (enlaces que caducan) | **No** → «solo registro» |
| Pinterest / otras | HTML | enlaces y textos (básico) + fotos y vídeos del ZIP | Sí |

Detalles: corrección automática del texto mal codificado de Meta, exportaciones en varias partes, tolerancia a formatos que cambian (si no se puede analizar, todo se guarda igualmente y se avisa), y **los mensajes privados se guardan cifrados pero no se indexan** (contienen texto de otras personas). Buscador por palabra, fecha, red y tipo (`SocialSearch`), exportación CSV/HTML (`SocialExport`). Pruebas: `SocialImportTest` (5).

## Función 3 · Detector inteligente de archivos desprotegidos

`ProtectionAnalyzer` (función pura) + `ChangeScanner` + `VaultService.protection()` (Android) + `ProtectionWorker` (WorkManager) + `Notifier`.
Detecta: carpetas sin copia desde N días, última copia fallida, copias interrumpidas (marcas de bloqueo antiguas), errores de sincronización, archivos nuevos/modificados pendientes, poco espacio, nube casi llena, problemas de integridad y copias sin verificar. Mensajes como «Tus fotografías seleccionadas llevan 7 días sin una copia de seguridad correcta». Configurable: notificaciones, días, periodicidad, categorías. Solo revisa carpetas que el usuario añade explícitamente (permiso SAF revocable); no lee otras apps. Pruebas: `protectionAlertsAreClearAndCorrect`, `failedAndInterruptedBackupsAreDetected`, `changeScannerFindsPendingFiles`.

## Función 4 · Nube inteligente y ahorro de almacenamiento

| Requisito | Implementación | Prueba |
|---|---|---|
| Local / nube / ambas | `HybridBackup` + `HybridPolicy` (por categoría y límite de tamaño) | `quotaAndHybridModeKeepLocalCopyWhenCloudFails` |
| Incremental | archivo sin cambios no se relee; bloque existente no se guarda | `incrementalBackupStoresOnlyChanges` |
| Duplicados | identificador de bloque = HMAC del contenido; informe de duplicados | `duplicatesAndCompression` |
| Compresión si conviene | Deflate por bloque solo si ahorra ≥ 5 %; nunca en JPG/MP4/ZIP | `duplicatesAndCompression` |
| Integridad | GCM + identificador recalculado + SHA-256 por archivo + `VaultCheck` | varias |
| Reanudación | bloques idempotentes; la instantánea se escribe al final | `interruptedBackupResumesReusingBlocks`, `connectionLossDuringUploadThenResume` (servidor real + corte TCP) |
| Retención | `RetentionPolicy` (por defecto conservar todo) + `Retention.prune` con confirmación y bloqueo | `retentionNeverDeletesWithoutPolicyAndPrunesSafely` |
| Espacio y alertas | `/v1/usage`, cuota atómica en servidor (507), alertas del detector | pytest `test_quota_is_enforced` |
| Cifrado antes de enviar | AES-256-GCM en el dispositivo; el servidor no tiene claves | `cloudBackupRestoreFromAnotherDeviceAndZeroKnowledge` |

## Función 5 · Recuperación guiada en tres pasos

1. **¿Qué has perdido?** 8 opciones + frase libre. `QueryParser` entiende tipo, red, dispositivo y fechas en español («septiembre», «la Navidad pasada», «hace 2 semanas», «2023», «14/09/2023»), **sin conexión**.
2. **Buscar**: `GuidedRecovery.search` en copias locales y nube, todas las versiones y redes importadas; ordena por relevancia, fecha o tipo. Cada resultado indica **Disponible**, **Texto disponible**, **Solo registro de actividad** o **Datos no disponibles**.
3. **Recuperar**: selección, detalle, carpeta de destino, restauración verificada y confirmación. Si no hay nada, explica por qué con alternativas reales (importar la exportación, papelera «Eliminado recientemente», fecha de la primera copia, instalar en el ordenador…).

Pruebas: `naturalLanguageQueriesInSpanish`, `guidedRecoveryFindsAndClassifiesAvailability`.

## Compatibilidad y migración

Las copias 1.0 se siguen pudiendo abrir y restaurar. «Pasar a la Máquina del tiempo» (`LegacyMigrator`) las copia a la bóveda conservando la fecha original y verificando cada huella; la original no se borra. Prueba: `migrationFromVersion1KeepsDateAndIntegrity`.

## Seguridad y privacidad

- Una contraseña de bóveda (nunca guardada). Opcional: clave en el **Android Keystore** para copias automáticas (`DeviceKeys`).
- Contraseña de cuenta en la nube independiente (scrypt en servidor), MFA TOTP, sesiones revocables, bloqueo tras 5 intentos.
- Borrado total de cuenta y datos (`DELETE /v1/account`). El panel de administración solo ve métricas agregadas.
- Sin keyloggers, capturas, ni acceso a cuentas de redes sociales: solo exportaciones oficiales que el usuario descarga.

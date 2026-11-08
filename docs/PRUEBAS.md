# Pruebas y calidad

## Módulo Premium (v1.1): pruebas obligatorias

| Requisito | Prueba(s) | Resultado |
|---|---|---|
| Recuperación por fecha | `VaultTest.timeMachineCalendarAndFilters`, `migrationFromVersion1KeepsDateAndIntegrity` | ✅ |
| Comparación de versiones | `VaultTest.versionHistoryAndComparison` | ✅ |
| Importación de exportaciones sociales | `SocialImportTest` (Instagram, Threads, Facebook, TikTok, YouTube, X, Reddit, Snapchat, varias partes, formatos rotos o desconocidos) | ✅ |
| Búsqueda de comentarios y registros | `SocialImportTest.searchByWordDateNetworkAndTypeAndExport`, `RecoveryAndStatusTest.guidedRecoveryFindsAndClassifiesAvailability` | ✅ |
| Detección de copias fallidas | `RecoveryAndStatusTest.protectionAlertsAreClearAndCorrect`, `failedAndInterruptedBackupsAreDetected` | ✅ |
| Integridad de archivos | `VaultTest.damagedAndMissingBlobsAre…`, `VaultCheck` en varias pruebas | ✅ |
| Copias incrementales | `VaultTest.incrementalBackupStoresOnlyChanges` | ✅ |
| Detección de duplicados | `VaultTest.duplicatesAndCompression` | ✅ |
| Interrupciones de conexión | `CloudTest.connectionLossDuringUploadThenResume` (proxy TCP que corta la conexión real a mitad de subida y de descarga) | ✅ |
| Restauración local | todas las de `VaultTest` | ✅ |
| Restauración desde la nube | `CloudTest.cloudBackupRestoreFromAnotherDeviceAndZeroKnowledge` | ✅ |
| Contraseñas incorrectas | `VaultTest.wrongPasswordAndPasswordChange`, `CloudTest` (bóveda y cuenta), pytest `test_register_login_and_wrong_password` | ✅ |
| Archivos dañados | `VaultTest.damagedAndMissingBlobs…` (el archivo dañado no se deja en el destino) | ✅ |
| Falta de espacio | `VaultTest.noSpaceDuringBackup`, `CloudTest.quotaAndHybridMode…` (cuota de la nube), pytest `test_quota_is_enforced` | ✅ |
| Modo híbrido con la nube caída | `CloudTest.quotaAndHybridModeKeepLocalCopyWhenCloudFails` | ✅ |
| Conocimiento cero | `CloudTest…ZeroKnowledge` (se revisan los archivos del servidor: ni texto ni nombres) | ✅ |
| Retención segura | `VaultTest.retentionNeverDeletesWithoutPolicyAndPrunesSafely` | ✅ |
| Backend: aislamiento entre usuarios, MFA, sesiones, borrado RGPD, S3 | pytest (8) | ✅ |

Ejecutar todo:

```bash
./gradlew :core:test                                   # CloudTest se omite si no hay servidor
SRP_PYTHON=$PWD/backend/.venv/bin/python SRP_BACKEND_DIR=$PWD/backend ./gradlew :core:test   # con servidor real
cd backend && pytest -q
```

## Versión 1.0

Todos los datos de prueba son **sintéticos** (bytes aleatorios con semilla fija y ZIP fabricados). No se usan datos personales.

## Matriz de la sección 15

| Requisito | Prueba automática | Resultado |
|---|---|---|
| Copia y restauración de archivos pequeños | `smallFilesRoundTrip` (vacío, 1 B, 12 KB, límites exactos de bloque) | ✅ |
| Copia y restauración de vídeos grandes | `largeVideoRoundTrip` (64 MiB por defecto; ejecutada con **1 GiB**) | ✅ 1 GiB en ~25 s |
| Nombres especiales | `specialFileNames` (acentos, ñ, emoji, japonés, espacios, %, #) · `hostileNamesStayInsideDestination` (`..`, `CON`, `/`, `\`) | ✅ |
| Contraseña incorrecta | `wrongPasswordIsRejected` (otra, vacía, distinta solo en mayúscula) | ✅ |
| Archivo cifrado dañado | `corruptedDataIsDetected` (1 bit cambiado) · `corruptedManifestOrInfoIsDetected` · `reorderedChunksAreDetected` | ✅ |
| Interrupción de una copia | `interruptedBackupLeavesNoValidLookingCopy` (cancelación y cierre forzoso simulado) · `truncatedDataIsDetected` · `truncationAtChunkBoundaryIsDetected` | ✅ |
| Falta de espacio | `noSpaceIsReportedClearly` (ENOSPC simulado a mitad de copia) | ✅ |
| Pérdida de conexión | `unreadableSourceIsSkippedAndReported` (origen USB que se desconecta a mitad) | ✅ (local). Red: no aplica en v1 (sin nube) |
| Permisos denegados | `permissionDeniedOnDestination` | ✅ |
| Integridad de lo restaurado | SHA-256 + relectura del destino en todas las pruebas de ida y vuelta · `verifyOnlyMode` | ✅ |
| Recuperación desde almacenamiento local | Todas las anteriores | ✅ |
| Recuperación desde almacenamiento remoto | — | ⏳ Fase 3 |
| Proveedor sin renombrado (p. ej. algunas nubes en el selector) | `providerWithoutRenameStillProducesValidBackup` | ✅ |
| Restauración que no sobrescribe | `restoreNeverOverwritesExistingFiles` | ✅ |
| Parámetros reales de KDF (600 000 iteraciones) | `productionKdfParametersWork` | ✅ |
| Exportaciones Instagram / Facebook / TikTok / ZIP no válido | `ExportAnalyzerTest` (4 pruebas) | ✅ |

**Control de la calidad de las pruebas:** se introdujo a propósito un fallo en el cifrador (no marcar el último bloque) y la prueba `truncationAtChunkBoundaryIsDetected` lo detectó. Las pruebas no "pasan siempre".

## Pruebas manuales pendientes en dispositivo (hacer antes de publicar)

Módulo Premium:
- [ ] Crear la bóveda en tarjeta SD y en pendrive; abrirla tras reiniciar el teléfono.
- [ ] Calendario: días marcados correctos con zona horaria de Canarias y de la península.
- [ ] Importar una exportación real de Instagram, Facebook y TikTok **de una cuenta de pruebas** y buscar un comentario.
- [ ] Activar copias automáticas y comprobar que el aviso «llevan N días sin copia» llega como notificación.
- [ ] Modo avión durante una copia a la nube → la copia local se completa y aparece «Error de sincronización»; al volver la red, se reanuda.
- [ ] Llenar la cuota de la nube → aviso de capacidad.
- [ ] Recuperación guiada con TalkBack activado.

Versión 1.0:

Marcar en al menos: Android 8 (API 26), Android 11, Android 14/15, un teléfono con poca RAM y uno con tarjeta SD.

- [ ] Primer arranque: asistente de 3 pasos, ventana de categorías una sola vez.
- [ ] Carpeta en memoria interna, en tarjeta SD y en pendrive OTG.
- [ ] Copia de 20 fotos + 1 vídeo de más de 2 GB; restaurar todo; comparar con un gestor de archivos.
- [ ] Rotar la pantalla y bloquear/desbloquear durante una copia.
- [ ] Desconectar el pendrive durante una copia → mensaje claro y sin copia "válida" a medias.
- [ ] Retirar el permiso de la carpeta (Ajustes › Apps › Permisos) → la app pide elegirla de nuevo.
- [ ] Contraseña incorrecta, después correcta.
- [ ] Copiar la carpeta de copias a otro teléfono e intentar restaurarla allí.
- [ ] Exportación real de Instagram, TikTok y Facebook **de una cuenta de pruebas**.
- [ ] Modo oscuro, tamaño de letra grande y TalkBack.
- [ ] Llenar el almacenamiento de destino → mensaje de falta de espacio.

# Plan de adaptación para iPhone (fase 6)

Nada de esta parte está implementado todavía. Este plan describe cómo llevar las cinco funciones a iOS manteniendo la compatibilidad de las copias.

## Estrategia de código

| Opción | Ventaja | Inconveniente |
|---|---|---|
| **Kotlin Multiplatform** para `:core` + interfaz SwiftUI (recomendada) | Un solo motor probado para Android, escritorio e iOS: mismo cifrado, mismos importadores, mismas pruebas | Hay que sustituir `javax.crypto`, `java.util.zip` y `java.io` por equivalentes multiplataforma (CryptoKit vía `expect/actual`, zlib de Apple) |
| Reescritura en Swift del formato `vault1` | 100 % nativo | Duplica el motor; hay que mantener dos implementaciones y vectores de prueba compartidos |

Primer hito: vectores de prueba (bóveda generada por `VaultTest`) que la versión iOS debe abrir y restaurar byte a byte.

## Correspondencia de piezas

| Android | iOS |
|---|---|
| Storage Access Framework | `PhotosPicker`, `UIDocumentPickerViewController` (carpetas y unidades externas), marcadores con alcance de seguridad |
| Android Keystore (`DeviceKeys`) | Keychain con `kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly` + Secure Enclave |
| WorkManager (`ProtectionWorker`) | `BGProcessingTask` / `BGAppRefreshTask`: iOS decide cuándo; normalmente con el teléfono cargando y en Wi-Fi |
| Notificaciones | `UNUserNotificationCenter` (permiso explícito) |
| PBKDF2 / AES-GCM | CommonCrypto (`CCKeyDerivationPBKDF`) / CryptoKit `AES.GCM` |
| Deflate | `Compression` framework (`COMPRESSION_ZLIB` produce deflate sin cabecera: compatible con `Inflater(nowrap)`; ajustar el formato de bloque para usar deflate "raw" en ambos lados o añadir la cabecera zlib en iOS) |

## Limitaciones propias de iOS (a comunicar al usuario)

- La copia automática de la fototeca solo es posible con permiso de Fotos y cuando iOS ejecute la tarea en segundo plano; no hay acceso a carpetas de otras apps.
- La importación de redes sociales funciona igual (archivos ZIP descargados con Archivos/Safari).
- Pagos: suscripción con StoreKit 2 (In-App Purchase) a 29,99 €/año; en la UE pueden aplicarse opciones de pago alternativas según las condiciones vigentes de Apple.

## Pasos

1. Extraer `:core` a Kotlin Multiplatform (`commonMain` + `jvmMain` + `iosMain`) manteniendo las pruebas en verde.
2. App SwiftUI con las pantallas: inicio, crear copia, Máquina del tiempo, Mis redes sociales, Estado de protección, Nube inteligente, Recuperación guiada.
3. TestFlight con usuarios de prueba; revisión de App Store (privacidad: «Datos no vinculados al usuario» salvo la cuenta de la nube).

# SocialRecover Pro — v1.1 (Android + Módulo Premium)

> **Tus archivos, siempre protegidos.**

Aplicación Android para crear **copias de seguridad cifradas** de los archivos que elige el usuario y **recuperarlos** aunque se hayan borrado los originales. Incluye un asistente para guardar las **exportaciones oficiales** de Instagram, TikTok y Facebook.

## Estado real de esta entrega

| Pieza | Estado |
|---|---|
| Motor `:core`: copias cifradas, bóveda incremental, Máquina del tiempo, redes sociales, recuperación guiada, detector, cliente de nube | **Implementado y probado**: 51 pruebas superadas, incluidas 3 contra el servidor real con cortes de red (`docs/resultados-pruebas.txt`). |
| Backend de la nube (`backend/`, FastAPI) | **Implementado y probado**: 8 pruebas (cuentas, MFA, cuotas, aislamiento, borrado RGPD, S3). Docker sin probar. |
| Componente Windows/escritorio (`desktop/`) | **Implementado y probado en JVM** (`docs/resultados-escritorio.txt`). Sin interfaz gráfica; no probado en Windows real. |
| Capa de datos Android | **Compilada** contra `android.jar`; no probada en teléfono. |
| Interfaz Android (Compose) con las 5 pantallas nuevas | **Escrita, sin compilar** (sin acceso a los repositorios de Google). |
| APK | **No generado.** Instrucciones en `docs/COMPILAR.md`. |
| iPhone | Plan en `docs/IPHONE.md`. |

Detalle: **`docs/ESTADO.md`** · Funciones nuevas: **`docs/MODULO_PREMIUM.md`**.

## Estructura

```
SocialRecoverPro/
├── core/                      Motor en Kotlin puro (sin Android): reutilizable y testeable
│   └── src/main/kotlin/com/socialrecover/core/
│       ├── crypto/            PBKDF2, clave envuelta, AES-256-GCM por bloques (STREAM)
│       ├── engine/            BackupEngine, RestoreEngine, errores en español, nombres seguros
│       ├── model/             Categorías, índice cifrado (Manifest), info pública de la copia
│       ├── store/             Interfaces de almacenamiento + implementación con java.io.File
│       ├── vault/             Bóveda incremental, Máquina del tiempo, versiones, retención, híbrido, migración
│       ├── social/            Importadores modulares de exportaciones, buscador, exportación CSV/HTML
│       ├── recovery/          Recuperación guiada y frases en español
│       ├── status/            Detector de archivos desprotegidos
│       └── cloud/             Cliente de la nube cifrada
├── backend/                   API de la nube (FastAPI + PostgreSQL/SQLite + S3) con pruebas y Docker
├── desktop/                   Windows/escritorio: línea de comandos y tarea programada
├── app/                       Aplicación Android (Kotlin + Jetpack Compose)
│   └── src/main/java/com/socialrecover/pro/
│       ├── data/              SAF, bóveda en carpeta, llavero Android, servicio de bóvedas, notificaciones
│       ├── work/              Tarea periódica del detector y copias automáticas (WorkManager)
│       └── ui/                ViewModel, estado premium, tema, pantallas
├── scripts/run-core-tests.sh  Ejecuta las pruebas del motor sin Gradle
└── docs/                      Compilación, manual, arquitectura, estado, pruebas, hoja de ruta
```

## Empezar

1. Abre la carpeta en **Android Studio** (Ladybug 2024.2 o posterior).
2. Espera a que termine la sincronización de Gradle.
3. Ejecuta la app en un emulador o teléfono (▶), o genera el APK: **Build › Build App Bundle(s) / APK(s) › Build APK(s)**.
4. Pruebas del motor: `./gradlew :core:test`.

## Principios

- Internet solo si el usuario activa la nube; los datos salen ya cifrados (conocimiento cero).
- Sin permisos de almacenamiento amplios: el usuario elige cada archivo o carpeta con el selector del sistema.
- La contraseña no se guarda. Ni la app ni un futuro servidor pueden abrir las copias.
- No se promete lo imposible: no se recuperan datos borrados de los servidores de las redes sociales ni se accede a cuentas.

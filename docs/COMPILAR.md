# Compilar SocialRecover Pro y obtener el APK

## Requisitos

- Android Studio **Ladybug (2024.2)** o posterior (incluye JDK 17/21).
- Android SDK Platform **35** (Android Studio lo descarga si falta).
- Conexión a internet la primera vez (Gradle descarga dependencias de Google Maven y Maven Central).

## 1. Abrir el proyecto

1. *File › Open…* y elige la carpeta `SocialRecoverPro`.
2. Acepta "Trust project".
3. Espera a "Gradle sync finished". Si Android Studio ofrece actualizar AGP o dependencias, **puedes aceptarlo** (Upgrade Assistant), pero haz primero una compilación con las versiones incluidas.

Versiones fijadas en `gradle/libs.versions.toml`: AGP 8.7.3, Kotlin 2.1.0, Compose BOM 2024.12.01, Gradle 8.11.1, compileSdk/targetSdk 35, minSdk 26 (Android 8.0).

> **Aviso honesto:** la interfaz Compose se escribió sin poder compilarla (sin acceso a los repositorios en el entorno de desarrollo). Si la primera compilación muestra algún error, suele ser un import o un parámetro de una API de Compose; el mensaje de Android Studio indica archivo y línea. El motor (`:core`) y la capa de almacenamiento sí se compilaron y probaron.

## 2. Ejecutar en el teléfono o emulador

- Teléfono: activa *Opciones de desarrollador › Depuración USB*, conéctalo y pulsa ▶ *Run 'app'*.
- Emulador: *Device Manager › Create device* (por ejemplo, Pixel 7, API 35) y ▶.

## 3. APK de prueba (debug)

*Build › Build App Bundle(s) / APK(s) › Build APK(s)*, o en terminal:

```bash
./gradlew :app:assembleDebug
# Resultado: app/build/outputs/apk/debug/app-debug.apk
```

Este APK se puede instalar directamente en un Android 8.0 o superior (hay que permitir "instalar apps de origen desconocido").

## 4. APK / AAB de publicación (firmado)

1. *Build › Generate Signed App Bundle / APK…* › *Create new…* para crear el almacén de claves (`.jks`). **Guárdalo y haz copia: sin él no podrás publicar actualizaciones.**
2. O crea `keystore.properties` en la raíz (no se sube a git):

```properties
storeFile=../claves/socialrecover.jks
storePassword=********
keyAlias=socialrecover
keyPassword=********
```

```bash
./gradlew :app:assembleRelease   # APK firmado:  app/build/outputs/apk/release/
./gradlew :app:bundleRelease     # AAB para Play: app/build/outputs/bundle/release/
```

Para Google Play se recomienda **Play App Signing** (Google custodia la clave de firma final; tú subes con una clave de subida).

**Antes de publicar en Google Play:** Google exige un `targetSdk` reciente para apps nuevas y actualizaciones (consulta el requisito vigente en la Play Console). Si piden un nivel superior a 35, súbelo en `app/build.gradle.kts` con la versión de AGP correspondiente y repite las pruebas.

## 5. Componente de escritorio y backend

- Escritorio/Windows: `./gradlew :desktop:fatJar` → `desktop/build/libs/srp.jar` (ver `WINDOWS.md`).
- Backend: ver `backend/README.md`. Para probar la app contra un servidor local desde el emulador, en *Nube inteligente* pon `http://10.0.2.2:8080` (solo funciona en compilaciones de depuración).

## 6. Pruebas automáticas

```bash
./gradlew :core:test                         # 51 pruebas (CloudTest se omite sin servidor)
./gradlew :core:test -Dsrp.bigTestMb=1024    # incluye un "vídeo" de 1 GiB
```

Sin Gradle (solo JDK 17+ y `kotlinc`):

```bash
KOTLINC=/ruta/kotlinc scripts/run-core-tests.sh -Dsrp.bigTestMb=1024
```

## Problemas frecuentes

| Síntoma | Solución |
|---|---|
| `Unsupported class file major version` | En *Settings › Build Tools › Gradle* elige "Gradle JDK: jbr-17" o superior. |
| `SDK location not found` | Abre el proyecto desde Android Studio (crea `local.properties`). |
| Error en una API de Compose | Acepta la actualización del BOM de Compose o corrige el import que indique el error. |
| Instalación bloqueada en el teléfono | Desinstala versiones anteriores firmadas con otra clave. |

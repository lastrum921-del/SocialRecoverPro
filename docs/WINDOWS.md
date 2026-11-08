# SocialRecover Pro para Windows (componente de escritorio)

## Qué hay y qué no

- ✅ **Motor completo** (el mismo `:core` que Android, probado): bóveda incremental, Máquina del tiempo, importación de redes, recuperación guiada, detector, verificación, retención, migración y nube.
- ✅ **Aplicación de línea de comandos `srp`** (`desktop/`), probada en JVM (ver `resultados-escritorio.txt`).
- ✅ **Copia programada** con el Programador de tareas de Windows (`srp schedule … --crear`).
- ⏳ **Interfaz gráfica**: pendiente. Recomendación: Compose for Desktop (Kotlin) para reutilizar las pantallas y el motor sin duplicar código; alternativa .NET/WinUI leyendo el formato documentado en `ARQUITECTURA.md`.
- ⏳ **Recuperación de archivos borrados del disco (NTFS)**: pendiente; se implementará en solo lectura y nunca con destino en la misma unidad.
- ⚠️ No se ha probado en Windows real (el entorno de desarrollo era Linux). El código es Java/Kotlin estándar; las rutas usan `File` y separadores normalizados.

## Compilar

Requisitos: JDK 17 o superior.

```bat
gradlew.bat :desktop:fatJar
:: Resultado: desktop\build\libs\srp.jar
java -jar desktop\build\libs\srp.jar ayuda
```

## Instalador (.msi / .exe) con jpackage (incluido en el JDK)

```bat
jpackage --type msi --name "SocialRecover Pro" --app-version 1.1.0 ^
  --input desktop\build\libs --main-jar srp.jar --main-class com.socialrecover.desktop.MainKt ^
  --win-console --win-dir-chooser --win-menu --vendor "SocialRecover Pro"
```

Para `.msi` jpackage necesita **WiX Toolset** instalado. Firmar el instalador con un certificado de firma de código para evitar avisos de SmartScreen.

## Uso

```bat
chcp 65001
srp init E:\SocialRecoverBoveda
srp backup E:\SocialRecoverBoveda "%USERPROFILE%\Pictures" "%USERPROFILE%\Documents"
srp calendar E:\SocialRecoverBoveda 2026-11
srp find E:\SocialRecoverBoveda "la factura de la luz de septiembre"
srp restore E:\SocialRecoverBoveda snap-20261108T162024-08deda D:\Restaurado
srp schedule E:\SocialRecoverBoveda "%USERPROFILE%\Pictures" --hora=21:30 --crear
```

Para las copias programadas sin preguntar la contraseña se usa la variable `SRP_PASSWORD` del usuario. **Mejora pendiente** antes de la versión comercial: guardar la clave de la bóveda con DPAPI / Administrador de credenciales de Windows (equivalente al Keystore de Android) en lugar de una variable de entorno.

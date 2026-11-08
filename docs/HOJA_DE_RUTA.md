# Hoja de ruta, versión Windows, plan iPhone y comercialización

## Fases

| Fase | Contenido | Criterio de "terminado" |
|---|---|---|
| 1 ✔ | Motor cifrado + app Android gratuita, copia manual, restauración verificada, asistente de exportaciones | APK compilado y lista manual de `PRUEBAS.md` completada en dispositivos reales |
| 1.1 ✔ (motor y backend probados; UI Android sin compilar) | Módulo Premium: Máquina del tiempo, redes sociales, detector, Nube inteligente, recuperación guiada | Ver `ESTADO.md` |
| 2 | Copias programadas (WorkManager) de carpetas autorizadas, notificaciones, incrementales (deduplicación por SHA-256 entre copias), versiones, papelera con caducidad, vista previa, cambio de contraseña, biometría | Pruebas automáticas + 2 semanas de uso beta cerrada |
| 3 | Cuenta, MFA, backend FastAPI/PostgreSQL/S3 en Docker, nube y modo híbrido, cuotas, dispositivos, reanudación de subidas | Pruebas de pérdida de conexión, restauración remota y borrado de cuenta (RGPD) |
| 4 | Windows | Ver abajo |
| 5 | Asistentes para YouTube (Google Takeout), X, Threads, Reddit, Pinterest, Snapchat; API oficiales solo donde permitan descargar el contenido propio; visor de exportaciones | Probado con cuentas de prueba |
| 6 | iPhone | Ver abajo |
| 7 | Planes, Play Billing / StoreKit / pago web, panel de administración | Compras de prueba en ambas tiendas |
| 8 | Auditoría externa (cifrado, backend, apps), pentest, política de privacidad revisada por abogado, lanzamiento | Informe de auditoría sin hallazgos críticos abiertos |

## Versión Windows (fase 4) — cómo se construirá

- **Tecnología:** .NET 8 LTS, interfaz WinUI 3 (Windows App SDK) o WPF; servicio de Windows (`Microsoft.Extensions.Hosting.WindowsServices`) para copias programadas aunque no haya sesión abierta.
- **Formato compatible:** las mismas copias que Android (`ARQUITECTURA.md`). .NET incluye `Rfc2898DeriveBytes.Pbkdf2` (SHA-256) y `AesGcm`. Primer hito: un lector .NET que restaure las copias generadas por las pruebas de `:core` (vectores de prueba compartidos).
- **Funciones propias:** proteger carpetas y unidades externas elegidas, vigilancia de cambios (`FileSystemWatcher` + escaneo periódico), copias incrementales y Volume Shadow Copy para archivos abiertos.
- **Recuperación de disco (opcional):** lectura de la MFT de NTFS en modo solo lectura con permisos de administrador; el destino nunca puede estar en la misma unidad; aviso claro sobre SSD/TRIM, sobrescritura y BitLocker.
- **Instalador:** MSIX firmado (Microsoft Store o instalación directa). Requiere un certificado de firma de código para evitar avisos de SmartScreen.
- **Generarlo:** `dotnet publish -c Release -r win-x64` y empaquetado MSIX desde Visual Studio 2022 (*Publish › Create App Packages*). Se documentará al entregar la fase.

## Plan iPhone (fase 6)

- Swift + SwiftUI, iOS 17 o superior.
- Selección con `PhotosPicker` (fotos/vídeos) y `UIDocumentPickerViewController` (archivos, carpetas, unidades externas); marcadores con alcance de seguridad para conservar el acceso.
- Cifrado con CryptoKit (`AES.GCM`) y CommonCrypto (PBKDF2) — compatible con el formato v1.
- Copias en segundo plano solo dentro de lo que permite iOS (`BGProcessingTask`, normalmente con el teléfono cargando); se informará al usuario de que la frecuencia la decide el sistema.
- Distribución: TestFlight → App Store. Requiere cuenta de Apple Developer y un Mac con Xcode.

## Plan de comercialización

**Propuesta de valor:** "Tus fotos, vídeos y redes sociales, guardados donde tú quieras y cifrados para que solo tú puedas abrirlos." Diferencial frente a Google Fotos/iCloud: cifrado de extremo a extremo, copias en pendrive/SD sin cuenta, y guardado de exportaciones de redes sociales.

**Precio decidido (v1.1):** SocialRecover Pro **29,99 €/año** con todas las funciones premium y **5 GB** de nube cifrada; copias locales sin límite; ampliaciones de nube opcionales (p. ej. +100 GB, precio a validar). La versión gratuita mantiene copias locales manuales. Los planes y cuotas se configuran en el servidor (`/v1/admin/plans`). Validar antes el coste real por GB del proveedor S3 elegido (almacenamiento + transferencia + peticiones) para que 5 GB por cliente sean rentables.

**Propuesta anterior de planes (sustituida por el precio anual único):**

| | Gratis | Pro | Premium |
|---|---|---|---|
| Copias locales ilimitadas | ✔ | ✔ | ✔ |
| Copias automáticas | — | ✔ | ✔ |
| Nube cifrada | — | p. ej. 200 GB | p. ej. 2 TB |
| Dispositivos | 1 | 3 | 10 |
| Historial de versiones | — | 30 días | 1 año |
| Soporte | Correo | Correo | Prioritario |

Precio orientativo para validar: Pro 2,99–3,99 €/mes, Premium 7,99–9,99 €/mes, con descuento anual. El coste de almacenamiento S3 por GB debe calcularse con el proveedor elegido antes de fijar cuotas.

**Pagos:** dentro de las apps, las suscripciones digitales deben cobrarse con Google Play Billing y Apple In-App Purchase (o las alternativas que cada tienda permita en la UE). La comisión de cada tienda y las condiciones vigentes deben consultarse en la Play Console y App Store Connect antes del lanzamiento. En Windows/web se puede usar una pasarela (Stripe, Paddle) con facturación conforme a IVA de la UE (OSS).

**Lanzamiento:**
1. Beta cerrada (Play Console, prueba cerrada) con 50–100 usuarios; medir copias completadas y restauraciones exitosas.
2. Ficha de Play en español con capturas reales y la sección "Seguridad de los datos" (v1: sin recopilación de datos).
3. Contenido: guías "Cómo descargar tus datos de Instagram/TikTok y guardarlos seguros" (SEO y vídeos cortos).
4. Canal empresa (fase 7+): pymes que quieren copia cifrada de portátiles Windows sin depender de un único proveedor de nube.

**Riesgos legales y de marca:**
- No usar logotipos de Instagram, TikTok, Facebook, etc. en la ficha ni en la app; describir como "compatible con exportaciones de…". Revisar las políticas de marca de cada empresa.
- Evitar en marketing promesas como "recupera lo que borraste en Instagram": son engañosas y motivo de rechazo en las tiendas.
- Comprobar disponibilidad de la marca "SocialRecover Pro" (EUIPO/OEPM) y del dominio antes de invertir en marketing.
- Política de privacidad, términos y registro de actividades de tratamiento (RGPD) obligatorios desde la fase 3.

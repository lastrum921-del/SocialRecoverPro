# Conectar Google Drive (una sola vez, unos 10 minutos)

SocialRecover Pro guarda las copias **cifradas** en tu propio Google Drive (15 GB gratis, compartidos con Gmail y Google Fotos).
Solo pide el permiso `drive.file`: la app ve **únicamente** la carpeta que ella crea («SocialRecoverPro-Boveda»), nunca el resto de tu Drive.

Google exige que cada app que usa Drive esté registrada en un proyecto de Google Cloud. Como la app es tuya, el proyecto lo creas tú (gratis):

## 1. Crear el proyecto y activar Drive
1. Entra en **https://console.cloud.google.com/** con tu cuenta de Google y acepta las condiciones.
2. Arriba, **Seleccionar proyecto › Proyecto nuevo** › nombre `SocialRecoverPro` › **Crear**.
3. Menú ☰ › **APIs y servicios › Biblioteca** › busca **Google Drive API** › **Habilitar**.

## 2. Pantalla de consentimiento
1. **APIs y servicios › Pantalla de consentimiento de OAuth** (o «Google Auth Platform › Branding»).
2. Tipo de usuario **Externo**. Nombre de la app `SocialRecover Pro`, tu correo como correo de asistencia y de contacto. Guardar.
3. En **Acceso a datos / Permisos**, añade el permiso `.../auth/drive.file`.
4. En **Público**, pulsa **Publicar aplicación** (con solo `drive.file` no hace falta verificación de Google). Si prefieres dejarla en *Prueba*, añade tu correo como usuario de prueba (en ese modo Google puede pedir permiso de nuevo cada semana).

## 3. Cliente para el móvil (Android)
1. **APIs y servicios › Credenciales › Crear credenciales › ID de cliente de OAuth**.
2. Tipo **Android**.
3. Nombre del paquete: `com.socialrecover.pro`
4. Huella SHA-1 (la del APK de prueba que compila GitHub):
   `0B:6B:3D:C2:C3:A6:6F:6C:47:7A:02:DB:9A:90:57:D8:28:31:C4:C1`
5. **Crear**. No hay que copiar nada en la app: Google reconoce la app por el paquete y la huella.

## 4. Cliente para el PC (Windows)
1. **Crear credenciales › ID de cliente de OAuth** › tipo **Aplicación de escritorio** › nombre `SocialRecover PC` › **Crear**.
2. Copia el **ID de cliente** y el **secreto del cliente** (en apps de escritorio el «secreto» no es realmente secreto; no lo publiques igualmente).
3. En el PC, dentro de la carpeta de SocialRecoverPro:
   ```bat
   SocialRecoverPro.exe drive-login --client-id=TU_ID --client-secret=TU_SECRETO
   ```
   Se abrirá el navegador para iniciar sesión. Después:
   ```bat
   SocialRecoverPro.exe backup drive "%USERPROFILE%\Pictures" "%USERPROFILE%\Documents"
   ```
   Usa **la misma contraseña de bóveda** que en el móvil: las copias del PC y del móvil aparecerán juntas en la Máquina del tiempo.

## 5. En el móvil
**Nube inteligente › Conectar Google Drive**, elige tu cuenta y acepta. Luego abre la bóveda con tu contraseña.

## Avisos
- Antes de publicar la app en Google Play hay que registrar también la huella SHA-1 de la clave de publicación (o la de Play App Signing).
- Si borras la carpeta «SocialRecoverPro-Boveda» de Drive, se borran esas copias.
- Puedes retirar el acceso cuando quieras en **myaccount.google.com › Seguridad › Conexiones de terceros**.

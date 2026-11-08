# Arquitectura técnica

## Módulos (actuales y previstos)

| # | Bloque | Fase 1 | Ubicación / plan |
|---|---|---|---|
| 1 | Aplicaciones cliente | Android | `app/` · Windows (.NET) fase 4 · iOS (SwiftUI) fase 6 |
| 2 | Motor de copias | ✔ | `core/engine/BackupEngine.kt` |
| 3 | Motor de recuperación | ✔ | `core/engine/RestoreEngine.kt` |
| 4 | Módulos de redes sociales | Análisis de ZIP oficiales | `core/social/` · API oficiales en fase 5 |
| 5 | Sistema de cifrado | ✔ | `core/crypto/` |
| 6 | Backend | — | FastAPI + PostgreSQL (fase 3) |
| 7 | Almacenamiento | Local (SAF) | `app/data/SafStorage.kt` · S3 compatible en fase 3 |
| 8 | Suscripciones | — | Play Billing / StoreKit (fase 7) |
| 9 | Panel administrativo | — | Web (fase 7) |

`core` es Kotlin puro sin dependencias: se prueba en cualquier JVM y la app Android lo usa tal cual. Para Windows (.NET) y iOS (Swift) el **formato de archivo** está documentado abajo para que las tres apps lean las mismas copias.

## Formato de copia v1

Cada copia es una carpeta:

```
SRP-AAAAMMDD-HHMMSS-xxxx/
  info.properties   público: formato, nombre, fecha, nº archivos, tamaño, KDF, sal, iteraciones, DEK envuelta, complete=true
  manifest.srpm     cifrado: nombre, carpeta, categoría, MIME, tamaño, SHA-256 y fecha de cada archivo
  data.srpk         cifrado: contenido de los archivos
```

### Claves

```
KEK  = PBKDF2-HMAC-SHA256(contraseña, sal 16 B, 600 000 iteraciones) → 32 B
DEK  = 32 B aleatorios (SecureRandom), una por copia
info.wrappedKey = AES-256-GCM(KEK, nonce 12 B, DEK, AAD = "SRPK1" ‖ sal ‖ iteraciones)
K_datos  = HMAC-SHA256(DEK, "SocialRecoverPro data v1")
K_índice = HMAC-SHA256(DEK, "SocialRecoverPro manifest v1")
```

- Contraseña incorrecta ⇒ falla la etiqueta GCM de la DEK envuelta ⇒ mensaje "contraseña incorrecta". No hay ningún "verificador" que facilite ataques.
- Manipular sal o iteraciones también rompe la etiqueta (van en el AAD).
- Cambiar la contraseña (futuro) = reenvolver la DEK, sin recifrar GB de datos.

### Cifrado en flujo (STREAM)

```
cabecera 16 B = magic(4: "SRPD"/"SRPM") ‖ versión(1) ‖ tamañoBloque(4) ‖ prefijoNonce(7)
bloque_i      = AES-256-GCM(K, nonce = prefijo ‖ i(4, BE) ‖ último(1), AAD = cabecera) — 1 MiB + 16 B de etiqueta
```

Detecta alteración, reordenación, eliminación de bloques y **truncamiento** (el último bloque va marcado). Memoria constante: un vídeo de varios GB usa ~1 MiB.

### Contenedor (dentro de data.srpk, ya descifrado)

```
"SRPA" ‖ v1 ‖ { 0x01 ‖ id(4) ‖ { long(4) ‖ bytes }* ‖ 0x00000000 }* ‖ 0x00
```

Segmentos de ≤ 64 KiB: no hace falta conocer el tamaño de antemano (útil con proveedores que no lo informan). Si un archivo de origen falla a mitad, queda fuera del índice y se ignora al restaurar.

### Atomicidad

`info.properties` (con `complete=true`) se escribe **el último**; sin él la copia aparece como "incompleta". La carpeta se crea como `*.partial` y se renombra al final si el proveedor lo permite. Ante error o cancelación se borra.

## Restauración segura

- Nombres saneados (sin `..`, separadores, caracteres no válidos en Windows, nombres reservados `CON`, `NUL`…), conservando acentos y emojis.
- Se restaura en una carpeta nueva; nunca se sobrescribe (`foto (1).jpg`).
- Doble verificación: SHA-256 del contenido descifrado frente al índice y **relectura del archivo escrito** en el destino.
- Requisito de no escribir en la unidad de origen: en Android la app no recupera archivos borrados del disco (no hay acceso a bajo nivel), así que el riesgo de sobrescritura no aplica; en Windows (fase 4) el módulo de recuperación de disco impedirá elegir la misma unidad como destino.

## Decisiones

| Decisión | Motivo |
|---|---|
| PBKDF2 en lugar de Argon2id | Nativo en Android 8+ y en .NET/iOS sin librerías nativas. Argon2id es más resistente a GPU; se valorará en formato v2 (el número de versión y el KDF están en `info.properties`). |
| Storage Access Framework, sin `MANAGE_EXTERNAL_STORAGE` | Google Play restringe el acceso total a archivos a casos muy concretos; SAF funciona con SD y USB y el usuario controla los permisos. |
| Sin permiso INTERNET en v1 | Mensaje de privacidad verificable por el usuario y por Play ("Seguridad de los datos": no se recopilan datos). |
| Índice separado y cifrado | Listar copias sin contraseña y ver contenido con contraseña sin descifrar GB de datos. |
| Formato binario propio en lugar de ZIP cifrado | ZIP+AES (WinZip AE-2) no autentica la estructura ni detecta truncamiento; STREAM sí. |

## Diseño de la fase 3 (nube, conocimiento cero)

- **Backend FastAPI + PostgreSQL + almacenamiento S3 compatible**, en Docker. FastAPI: tipado, OpenAPI automático, buen rendimiento con async; PostgreSQL para cuentas, dispositivos, cuotas y suscripciones.
- El cliente sube **los mismos tres archivos** de cada copia, ya cifrados. El servidor solo ve tamaños y fechas.
- Subida por partes (multipart) con URLs prefirmadas y reanudación: si se corta la conexión, se reanuda la parte pendiente.
- Integridad: el servidor guarda el SHA-256 de cada objeto cifrado; el cliente verifica GCM al descargar.
- Cuenta: correo + contraseña de cuenta (Argon2id en servidor), verificación de correo, TOTP (MFA), sesiones revocables. **La contraseña de la cuenta y la de las copias son independientes**; opcionalmente una "clave maestra" del usuario envuelve las DEK para sincronizar sin pedir cada contraseña, con código de recuperación impreso.
- El **panel de administración** consulta métricas, cuotas y suscripciones, nunca contenido: no hay claves en el servidor que permitan descifrarlo.
- RGPD: minimización (no hay nombres de archivos en el servidor), borrado de cuenta que elimina objetos y metadatos, registro de operaciones de seguridad, alojamiento en la UE.

## Formato de bóveda "vault1" (v1.1, Módulo Premium)

```
<bóveda>/                        (carpeta SAF "SocialRecoverPro-Boveda", carpeta de disco o prefijo en la nube)
  config                         público: formato, id, tamaño de bloque, PBKDF2 (sal, iteraciones), clave maestra envuelta
  blobs/ab/<id>                  bloque cifrado; id = HMAC-SHA256(K_id, contenido) en hexadecimal
  snapshots/snap-<AAAAMMDDThhmmss>-<rnd>   instantánea cifrada (la fecha UTC en el nombre permite pintar el calendario)
  locks/<tipo>-<ms>-<rnd>        copia en curso (evita que una limpieza borre bloques aún no referenciados)
```

- `K_enc = HMAC(master, "SRP vault enc v1")`, `K_id = HMAC(master, "SRP vault id v1")`.
- Objeto sellado: `"SRPV" ‖ 1 ‖ nonce(12) ‖ AES-256-GCM(K_enc, datos, AAD = "blob:<id>" | "snap:<nombre>")`.
- Bloque descifrado: `bandera(1: 0 = sin comprimir, 1 = Deflate) ‖ datos`; al leerlo se recalcula el id → un bloque no puede sustituirse por otro.
- Instantánea: binaria y comprimida; lista archivos (carpeta, nombre, categoría, red, MIME, tamaño, fecha, SHA-256, bloques), elementos sociales indexados y estadísticas.
- Validez: una copia existe solo cuando su instantánea está escrita (último paso). Lo interrumpido deja bloques sueltos que la siguiente copia reutiliza (reanudación); la limpieza (`Retention.prune`) los elimina solo si no hay copias en curso.
- La nube almacena exactamente los mismos objetos (`PUT /v1/repo/<clave>` con `X-Content-SHA256`).

### Decisiones

| Decisión | Motivo |
|---|---|
| Bloques fijos de 1 MiB | Sencillo, rápido en móviles y suficiente para fotos y vídeos (que no se editan por inserción). CDC queda pendiente para documentos grandes editados. |
| Id de bloque con HMAC y no SHA-256 en claro | El almacén no puede comprobar si un usuario tiene un archivo conocido (sin "confirmación de archivo"). La deduplicación es por usuario/bóveda, nunca entre clientes. |
| Una bóveda local y otra en la nube con la misma contraseña | Cada destino es independiente: si la nube falla, lo local sigue siendo válido (modo híbrido). |
| Índice social dentro de la instantánea cifrada | Buscar sin enviar nada a ningún servidor; los mensajes de terceros no se indexan. |
| Interpretación del lenguaje natural con reglas locales | Privacidad (el texto no sale del teléfono), funciona sin conexión y es predecible; se muestra al usuario lo que se ha entendido. |

# Manual de usuario — SocialRecover Pro (versión gratuita de prueba)

## Instalación

- **Desde Google Play** (cuando esté publicada): buscar "SocialRecover Pro" e instalar.
- **APK de prueba:** copia `app-debug.apk` al teléfono (Android 8.0 o superior), ábrelo y permite "Instalar apps desconocidas" para el gestor de archivos cuando Android lo pida. No necesita ningún permiso especial ni tarjeta bancaria.

## Primer uso

1. **Bienvenida.** Lee qué hace y qué no hace la aplicación y pulsa *Empezar*.
2. **Carpeta de copias.** Pulsa *Elegir carpeta* y selecciona dónde se guardarán las copias. Recomendado: una tarjeta SD o un pendrive USB (con adaptador OTG), para que la copia sobreviva si pierdes o restableces el teléfono. Pulsa *Usar esta carpeta* › *Permitir*.
3. **Categorías.** Aparece la ventana *¿Quieres proteger más información?*. Marca solo lo que quieras proteger y pulsa *Activar protección*, o *Ahora no*. Puedes cambiarlo después en *Configuración › Categorías protegidas*.

## Crear una copia

1. Pantalla principal › **Crear copia**.
2. Toca una categoría (Fotos, Vídeos, Documentos…) para abrir el selector de Android y marca los archivos. Puedes repetirlo con varias categorías o usar *Carpeta completa* para incluir una carpeta con sus subcarpetas.
3. (Opcional) Escribe un nombre para la copia. El nombre y la fecha se ven sin contraseña; los archivos, no.
4. Escribe una **contraseña** (mínimo 8 caracteres; mejor una frase larga) y repítela.
5. Pulsa **Crear copia cifrada** y mantén la aplicación abierta hasta que termine. Puedes cancelar en cualquier momento.

> **Si olvidas la contraseña no se puede recuperar la copia.** No se guarda en ningún sitio y nadie puede restablecerla.

## Recuperar archivos

1. Pantalla principal › **Recuperar archivos**.
2. Toca la copia y escribe su contraseña.
3. Busca por nombre o carpeta y filtra por categoría. Marca los archivos que quieras (por defecto, todos).
4. Pulsa **Restaurar seleccionados** y elige una carpeta de destino. Se crea dentro una carpeta nueva *SocialRecover - Restaurado (fecha)*: nunca se sobrescribe nada.
5. Al terminar verás el resultado: cada archivo se relee desde el destino y se compara su huella SHA-256 con la original. "Restauración completada" significa que son **idénticos byte a byte**.

*Comprobar integridad de la copia* revisa toda la copia sin escribir nada.

## Redes sociales

SocialRecover Pro **no entra en tus cuentas**. Usa la copia oficial que cada red te permite descargar:

1. Pantalla principal › **Redes sociales** › Instagram, TikTok o Facebook.
2. Sigue los pasos para pedir tu copia en la app de la red social (puede tardar horas o días).
3. Cuando la descargues, pulsa *Elegir archivo ZIP*. Verás un resumen (fotos, vídeos, mensajes…).
4. Pulsa *Guardar en una copia cifrada*, pon una contraseña y crea la copia. Después puedes borrar el ZIP original sin cifrar.

Recuperar esta copia te devuelve el ZIP a tu teléfono; **no vuelve a publicar nada** en la red social. Lo que la plataforma haya borrado definitivamente de sus servidores no se puede recuperar con ninguna aplicación.

## Mensajes de error

| Mensaje | Qué hacer |
|---|---|
| La contraseña no es correcta | Revisa mayúsculas, minúsculas y espacios. |
| La copia está dañada o ha sido modificada | Los archivos afectados no se restauran para no darte datos alterados. Usa otra copia. |
| La copia está incompleta | Se interrumpió al crearse. Elimínala y crea otra. |
| No queda espacio suficiente | Libera espacio o elige otra carpeta. |
| Ya no tiene permiso para acceder… | Vuelve a elegir la carpeta (por ejemplo, tras desconectar un pendrive). |

## Buenas prácticas

- Guarda copias en **dos sitios** (por ejemplo, teléfono y pendrive).
- Haz una copia nueva periódicamente; las copias automáticas llegarán en una próxima versión.
- Comprueba de vez en cuando una copia con *Comprobar integridad*.

## Novedades de la versión 1.1 (Módulo Premium)

**Tu bóveda.** La primera vez se crea con una contraseña. Todas las copias van ahí: solo se guarda lo nuevo o modificado y los duplicados ocupan una vez. Si activas *Copias automáticas en este dispositivo*, la clave se guarda en el chip de seguridad del teléfono para poder copiar sin pedirte la contraseña.

**Recuperar archivos (guiado).** 1) Toca lo que has perdido o escríbelo («la foto de la boda de diciembre»). 2) Revisa los resultados: *Disponible* se puede recuperar; *Solo registro* significa que la red social no incluyó el archivo en su exportación. 3) Marca y elige carpeta de destino.

**Máquina del tiempo.** Los días con copias aparecen marcados en el calendario. Toca un día, marca los archivos y pulsa *Recuperar*. El icono del reloj muestra las versiones de un archivo y permite compararlas.

**Mis redes sociales.** Elige la red, selecciona el ZIP descargado y espera. Después busca por palabra, red, tipo o fecha, recupera fotos y vídeos o exporta los resultados a CSV (hoja de cálculo) o HTML.

**Estado de protección.** Añade las carpetas que quieres vigilar. La app te avisará si llevan días sin copia, si una copia falló, si el espacio se acaba o si conviene verificar las copias.

**Nube inteligente.** Crea tu cuenta (su contraseña es distinta de la de la bóveda), elige qué va a la nube y qué se queda en local, y decide cuántas versiones conservar. Nunca se borra nada sin que aceptes una política. *Eliminar cuenta y datos* borra todo lo de la nube.

**Copias de la versión 1.0.** En *Almacenamiento › Copias de la versión anterior*, el botón del reloj las pasa a la Máquina del tiempo con su fecha original.

<!--
SPDX-FileCopyrightText: 2026 UltimateMail contributors
SPDX-License-Identifier: GPL-3.0-or-later
-->

# Privacy policy

*Español más abajo.*

UltimateMail is a mail client. It has no servers of its own, no accounts of its own, no ads, no
analytics, no crash reporting, no third-party services and no telemetry. Nobody but you, your mail
provider and (while you sign in) the OAuth provider sees your data.

## What data goes where

- **Your mail only travels between your device and the mail servers (IMAP and SMTP) you sign in
  to.** Connections use TLS or STARTTLS (STARTTLS is required, never downgraded to plain text) with
  full certificate and host name validation, which is never disabled. Certificate authorities
  installed on the device by you are accepted too, so self-hosted servers with their own CA work.
- **When you sign in with Google or Microsoft** (OAuth2), the app talks to that provider's sign-in
  and token endpoints to get and refresh access tokens, and then to the same provider's mail
  servers. The sign-in page opens in your browser; the app never sees your provider password.
- **On the device** the app keeps a copy of your folders, labels, message headers and the bodies and
  attachments you opened, in a local database (Room), so it works offline. Removing an account
  deletes its data from the device; uninstalling the app deletes everything.
- **Your password, app password or OAuth tokens** are encrypted with a key held in the Android
  Keystore and stored in the app's no-backup storage. They never appear in logs; neither do
  subjects, message bodies or e-mail addresses.
- **Android backup is disabled** (`allowBackup=false`, and cloud backup and device transfer rules
  exclude every data domain), so none of this is copied by Android's backup or when moving to a
  new phone.
- **Settings** (signature, offline window, and so on) stay on the device.
- **HTML e-mails** are cleaned and shown in an isolated view with scripts disabled. **Remote
  content (images, tracking pixels, styles) is blocked by default**, so senders cannot learn that
  you opened a message; you can allow it for a message. Links ask for confirmation when their text
  shows a different address from where they go.

## Permissions and why

| Permission | Why |
|---|---|
| Internet (`INTERNET`) | To talk to your mail servers and, during sign-in, to the OAuth provider. |
| Ignore battery optimisations (`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`) | So you can let the sync run with the screen off. The app asks once, after the first account is added, and the choice is also in Settings; it never turns it on by itself. |

Background sync uses Android's WorkManager, which needs no extra permission of its own. Attachments
are handled through the system.

## Google API Services User Data Policy: Limited Use

The use and transfer to any other app of information received from Google APIs will adhere to the
[Google API Services User Data Policy](https://developers.google.com/terms/api-services-user-data-policy),
including the Limited Use requirements.

UltimateMail uses the `https://mail.google.com/` scope (together with `openid` and `email`, to know
which account signed in) only to let you read, organize and send your own Gmail on your device
through IMAP and SMTP. In particular:

- the data is used only to provide those user-facing features, inside the app on your device;
- the data is not transferred or sold to anyone, and not used for advertising, including
  personalized or retargeted ads;
- no human reads the data: the app has no servers and the developers have no access to it;
- the data is not used to develop, improve or train generalized artificial intelligence or machine
  learning models.

## Microsoft

Equivalently, when sign-in with Microsoft is available, the app will request only the permissions
needed to read, organize and send your own mail on your device through IMAP and SMTP (the
`IMAP.AccessAsUser.All`, `SMTP.Send` and `offline_access` scopes, plus `openid` and `email` to know
which account signed in). Data obtained from Microsoft APIs is used only for those user-facing
features, never transferred or sold, never used for advertising and never used to train artificial
intelligence or machine learning models, and no human reads it. Sign-in with Microsoft is not
implemented yet.

## Contact

Questions or concerns: open an issue at <https://github.com/qtekfun/UltimateMail/issues>.

---

# Política de privacidad

UltimateMail es un cliente de correo. No tiene servidores propios, ni cuentas propias, ni anuncios,
ni analíticas, ni informes de fallos, ni servicios de terceros, ni telemetría. Nadie más que tú, tu
proveedor de correo y (mientras inicias sesión) el proveedor de OAuth ve tus datos.

## Qué datos van adónde

- **Tu correo solo viaja entre tu dispositivo y los servidores de correo (IMAP y SMTP) en los que
  inicias sesión.** Las conexiones usan TLS o STARTTLS (STARTTLS es obligatorio, nunca se degrada a
  texto plano) con validación completa del certificado y del nombre del servidor, que nunca se
  desactiva. También se aceptan las autoridades de certificación que instales tú en el dispositivo,
  para que funcionen los servidores propios con su CA.
- **Al iniciar sesión con Google o Microsoft** (OAuth2), la app habla con los servidores de
  inicio de sesión y de tokens de ese proveedor para obtener y renovar los tokens de acceso, y
  luego con los servidores de correo del mismo proveedor. La página de acceso se abre en tu
  navegador; la app nunca ve tu contraseña del proveedor.
- **En el dispositivo** la app guarda una copia de tus carpetas, etiquetas, cabeceras de mensajes
  y de los cuerpos y adjuntos que abres, en una base de datos local (Room), para funcionar sin
  conexión. Al quitar una cuenta se borran sus datos del dispositivo; al desinstalar la app, todo.
- **Tu contraseña, contraseña de aplicación o tokens OAuth** se cifran con una clave guardada en el
  Android Keystore y se almacenan en el almacenamiento de la app excluido de copias. Nunca aparecen
  en registros; tampoco asuntos, cuerpos de mensajes ni direcciones de correo.
- **La copia de seguridad de Android está desactivada** (`allowBackup=false`, y las reglas de copia
  en la nube y de transferencia a otro dispositivo excluyen todos los dominios de datos): nada de
  esto lo copia Android ni viaja a un móvil nuevo.
- **Los ajustes** (firma, ventana sin conexión, etc.) se quedan en el dispositivo.
- **Los correos HTML** se limpian y se muestran en una vista aislada con scripts desactivados. **El
  contenido remoto (imágenes, píxeles de seguimiento, estilos) se bloquea por defecto**, así que
  los remitentes no saben que abriste un mensaje; puedes permitirlo en un mensaje. Los enlaces
  piden confirmación cuando su texto muestra una dirección distinta de adonde llevan.

## Permisos y por qué

| Permiso | Por qué |
|---|---|
| Internet (`INTERNET`) | Para hablar con tus servidores de correo y, al iniciar sesión, con el proveedor de OAuth. |
| Ignorar la optimización de batería (`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`) | Para que puedas dejar que la sincronización siga con la pantalla apagada. La app lo pregunta una vez, tras añadir la primera cuenta, y la opción también está en Ajustes; nunca lo activa por sí sola. |

La sincronización en segundo plano usa WorkManager de Android, que no necesita permisos propios. Los
adjuntos se gestionan a través del sistema.

## Política de datos de usuario de las API de Google: uso limitado

El uso y la transferencia a cualquier otra app de la información recibida de las API de Google
cumplirán la
[Política de datos de usuario de los servicios de las API de Google](https://developers.google.com/terms/api-services-user-data-policy),
incluidos los requisitos de uso limitado.

UltimateMail usa el ámbito `https://mail.google.com/` (junto con `openid` y `email`, para saber qué
cuenta ha iniciado sesión) solo para que leas, organices y envíes tu propio Gmail en tu dispositivo
mediante IMAP y SMTP. En concreto:

- los datos se usan solo para ofrecer esas funciones al usuario, dentro de la app en tu dispositivo;
- los datos no se transfieren ni se venden a nadie, ni se usan para publicidad, incluida la
  personalizada o de remarketing;
- ninguna persona lee los datos: la app no tiene servidores y los desarrolladores no tienen acceso;
- los datos no se usan para desarrollar, mejorar ni entrenar modelos de inteligencia artificial o
  aprendizaje automático generalistas.

## Microsoft

De forma equivalente, cuando esté disponible el acceso con Microsoft, la app pedirá solo los
permisos necesarios para leer, organizar y enviar tu propio correo en tu dispositivo mediante IMAP
y SMTP (los ámbitos `IMAP.AccessAsUser.All`, `SMTP.Send` y `offline_access`, más `openid` y
`email` para saber qué cuenta ha iniciado sesión). Los datos obtenidos de las API de Microsoft se
usan solo para esas funciones, nunca se transfieren ni se venden, no se usan para publicidad ni
para entrenar modelos de inteligencia artificial o aprendizaje automático, y ninguna persona los
lee. El acceso con Microsoft todavía no está implementado.

## Contacto

Dudas o problemas: abre una incidencia en <https://github.com/qtekfun/UltimateMail/issues>.

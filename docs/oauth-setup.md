<!--
SPDX-FileCopyrightText: 2026 UltimateMail contributors
SPDX-License-Identifier: GPL-3.0-or-later
-->

# Signing in with Google or Microsoft (OAuth2)

UltimateMail ships **no** OAuth client of its own: Google and Microsoft would have to review and
vouch for a shared client, and a client ID baked into a free-software app is shared by everyone.
Instead you register a (free) app under your own Google or Microsoft account and paste its
client ID into the app. The client ID is a public value, not a secret: there is no client secret
anywhere, the app is a "public client" that uses PKCE. It is stored only on your phone and
Android backup is off for the app.

Nothing here is needed for accounts that sign in with a password. You can always use an app
password instead (see the end of each section).

The redirect the app listens on is always `com.qtekfun.ultimatemail:/oauth2redirect` (the
application ID, a colon, a slash and `oauth2redirect`).

## A. Google (Gmail)

1. Open the [Google Cloud Console](https://console.cloud.google.com/) and create a project (any
   name, for example "UltimateMail").
2. **APIs & Services > OAuth consent screen** (called "Google Auth Platform" in newer consoles):
   - User type **External**. Fill in the app name, your e-mail as support and developer contact.
   - Add the scope `https://mail.google.com/` (full access to the mailbox, which is what IMAP
     needs; it is a "restricted" scope).
   - Leave the publishing status on **Testing** and add your own Google address (and the
     addresses of anyone else who will use your build) under **Test users**.
   - Note that in Testing mode Google expires the refresh token after **7 days**, so you will have
     to sign in again every week. Publishing the app would remove this but requires Google's
     verification of a restricted scope, which is not realistic for a personal client.
3. **APIs & Services > Credentials > Create credentials > OAuth client ID**:
   - Application type **Android**.
   - Package name `com.qtekfun.ultimatemail`.
   - SHA-1 certificate fingerprint of the key that signs your build. For a debug build:
     `keytool -list -v -keystore ~/.android/debug.keystore -alias androiddebugkey -storepass android -keypass android`
     and copy the `SHA1:` line. A release build signed with another key needs its own fingerprint
     (or its own client).
   - Open **Advanced settings** and turn on **Enable custom URI scheme**. Without it Google
     answers `invalid_request` when you come back from the browser.
4. Copy the **Client ID** (`123456789012-abc...apps.googleusercontent.com`).
5. In UltimateMail: **Add account**, type your Gmail address, paste the client ID into the field
   under "Or sign in with the provider" and tap **Sign in with Google**.

**Alternative without any of this: an app password.** Turn on 2-Step Verification in your Google
Account, then Security > 2-Step Verification > App passwords, create one and enter it as the
password when adding the account.

## B. Microsoft (Outlook.com, Hotmail, Microsoft 365 work or school)

These steps have **not** been verified against real Microsoft accounts yet.

1. Open the [Microsoft Entra admin center](https://entra.microsoft.com/) (or the Azure portal)
   and go to **App registrations > New registration**. A personal Microsoft account can use it
   too: sign in with it and Azure creates a free directory.
2. Name it (for example "UltimateMail"). **Supported account types**: "Accounts in any
   organizational directory (Any Microsoft Entra ID tenant - Multitenant) and personal Microsoft
   accounts (e.g. Skype, Xbox)". The app uses the `common` endpoint, so this option is required
   for both kinds of accounts to work.
3. Under **Redirect URI** choose the platform **Mobile and desktop applications** (or add it
   later under **Authentication > Add a platform**) and enter the custom redirect URI
   `com.qtekfun.ultimatemail:/oauth2redirect`.
4. Under **Authentication**, make sure **Allow public client flows** is set to **Yes**. Create no
   client secret.
5. **API permissions > Add a permission > APIs my organization uses**, search for
   **Office 365 Exchange Online**, choose **Delegated permissions** and add `IMAP.AccessAsUser.All`
   and `SMTP.Send`. Also add (Microsoft Graph, delegated) `offline_access`, `openid`, `email` and
   `profile` if they are not there already.
6. From the **Overview** page copy the **Application (client) ID**, a GUID such as
   `0a1b2c3d-4e5f-6789-abcd-ef0123456789`.
7. In UltimateMail: **Add account**, type your Outlook address, paste the application ID under
   "Or sign in with the provider" and tap **Sign in with Microsoft**. For a work or school
   account on your own domain, open "Advanced server settings" and set the incoming server to
   `outlook.office365.com` and the outgoing one to `smtp.office365.com`; the Microsoft button then
   appears.

Things that can get in the way:

- **Organisations may require admin consent.** A work or school tenant can forbid users from
  consenting to apps, or require an administrator to approve the permissions. If the browser says
  "Need admin approval", send the administrator the application ID, or use a personal account.
- **SMTP AUTH may be disabled for Microsoft 365 tenants.** Many tenants turn off "Authenticated
  SMTP" (and sometimes IMAP) per organisation or per mailbox; sending then fails even though
  signing in and reading mail work. An administrator can enable it for the mailbox in the
  Exchange admin center (Mailbox > Manage email apps).
- Personal Outlook.com accounts accept IMAP/SMTP with OAuth2 as long as IMAP access is enabled
  in the account's settings.
- Passwords (including app passwords) are no longer accepted by Outlook.com and Microsoft 365 for
  IMAP/SMTP, so for Microsoft this OAuth flow is normally the only way in.

## Español (resumen)

UltimateMail no incluye un cliente OAuth propio: registras una aplicación gratuita con tu cuenta
de Google o de Microsoft y pegas su client ID en la app (es un valor público, no hay secreto; se
guarda solo en el teléfono). La redirección es siempre
`com.qtekfun.ultimatemail:/oauth2redirect`.

**Google**: en Google Cloud Console crea un proyecto; en la pantalla de consentimiento usa
usuario **Externo**, añade el ámbito `https://mail.google.com/`, déjala en **Testing** y añade tu
cuenta como **usuario de prueba** (en ese modo el refresh token caduca a los 7 días). Crea un ID
de cliente OAuth de tipo **Android** con el paquete `com.qtekfun.ultimatemail` y la huella SHA-1
de la clave con la que firmas la app, y en Configuración avanzada activa **Enable custom URI
scheme**. Copia el client ID, pégalo en la app y pulsa "Iniciar sesión con Google". Alternativa:
activa la verificación en dos pasos y usa una **contraseña de aplicación**.

**Microsoft**: en Microsoft Entra, Registros de aplicaciones > Nuevo registro, con tipos de
cuenta "cualquier directorio de organización y cuentas Microsoft personales". Plataforma
**Aplicaciones móviles y de escritorio** con la redirección
`com.qtekfun.ultimatemail:/oauth2redirect`, activa "Permitir flujos de cliente público" y no
crees secreto. Permisos delegados de Office 365 Exchange Online: `IMAP.AccessAsUser.All` y
`SMTP.Send`, más `offline_access`, `openid`, `email` y `profile`. Copia el ID de aplicación
(cliente), pégalo en la app y pulsa "Iniciar sesión con Microsoft". Ten en cuenta que una
organización puede exigir **consentimiento del administrador** y que muchos inquilinos de
Microsoft 365 tienen **SMTP AUTH desactivado**, de modo que el envío falla aunque la lectura
funcione. Esto todavía no se ha verificado con cuentas reales de Microsoft.

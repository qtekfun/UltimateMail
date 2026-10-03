# UltimateMail — Especificación (SPEC)

Versión del documento: 0.1 · Estado: borrador para implementación del MVP

## 1. Objetivo
Cliente Android de correo IMAP/SMTP con UI inspirada en Mail de iOS y Gmail, offline-first, GPLv3 y publicable en F-Droid. Ver `PRD.md` para el contexto de producto.

### Problemas que resuelve (Thunderbird Android)
1. UI anticuada.
2. Gestos lentos o ausentes.
3. Rendimiento y sincronización.
4. Lectura/redacción incómodas.
5. Imposible buscar en la lista de etiquetas/carpetas al mover correos.

## 2. Alcance y supuestos

| Tema | Decisión |
|---|---|
| Protocolos | IMAP4rev1 + SMTP (submission, STARTTLS/TLS implícito). Extensiones Gmail (`X-GM-LABELS`, `X-GM-THRID`, `X-GM-MSGID`) cuando existan |
| Librería de correo | Librería libre existente; elegida en el prototipo T03 y anotada en la sección 9 |
| Proveedores de prueba | Gmail y Microsoft 365/Outlook; además Dovecot genérico |
| Autenticación | Contraseña de aplicación y OAuth2 (AppAuth) para Google y Microsoft (obligatorio en M365, que no admite auth básica) |
| Plataforma | Android `minSdk` 26; móvil en vertical primero |
| Cuentas | Multicuenta desde el MVP con bandeja unificada; todo el modelo indexado por `accountId` |
| Almacenamiento offline | Por cuenta y configurable: por defecto cabeceras y cuerpos completos de los últimos 90 días (los cuerpos se descargan durante el sync, ver sección 9), adjuntos bajo demanda; opción de ventana distinta o buzón completo |
| Licencia | GPL-3.0-or-later |
| Idiomas | Inglés y español |
| Distribución | F-Droid (no en el MVP, pero diseñado para cumplirlo) |

## 3. Requisitos funcionales (MVP)

### RF-01 Cuentas y autenticación
- Añadir cuenta: autodetección de servidores (ISPDB de Thunderbird/autoconfig/SRV) con edición manual.
- Gmail y Outlook/M365: OAuth2 con AppAuth (PKCE, navegador externo). Otros: contraseña de aplicación.
- Credenciales y tokens cifrados con Android Keystore. Refresco de tokens transparente.
- Eliminar cuenta: borra credenciales y datos locales de esa cuenta.
- **Criterios:** URL/servidor inválido → error claro y accionable; token revocado → se pide reautenticar sin perder datos locales ni acciones pendientes; credenciales ausentes de logs, backups y preferencias en claro.

### RF-02 Lista de carpetas/etiquetas
- Las carpetas y etiquetas se muestran en un **menú lateral** (navigation drawer) como en Gmail: bandeja unificada arriba, selector de cuenta en la cabecera y el árbol de la cuenta activa debajo.
- Árbol de carpetas por cuenta, con carpetas especiales detectadas (`SPECIAL-USE`: Inbox, Sent, Drafts, Trash, Archive, Junk).
- Gmail: etiquetas como etiquetas (un correo puede tener varias).
- **Criterio:** funciona offline con lo último sincronizado.

### RF-03 Bandeja y conversaciones
- Lista de conversaciones (hilos) por carpeta y bandeja unificada de todas las cuentas.
- Hilos: `X-GM-THRID` en Gmail; en el resto `THREAD` de IMAP si existe o, si no, `References`/`In-Reply-To` + asunto (algoritmo en `domain`, con tests).
- Fila: avatar/inicial, remitente, asunto, extracto, hora, indicadores (no leído, destacado, adjunto, etiquetas de color).
- Paginación y listas perezosas; estado de scroll conservado.

### RF-04 Lectura
- Vista de conversación con mensajes plegables y citas colapsadas.
- HTML renderizado de forma aislada (WebView sin JavaScript, sin acceso a archivos); **imágenes y contenido remoto bloqueados por defecto**, con permiso por mensaje/remitente.
- Enlaces: confirmar destino cuando el texto del enlace difiera de la URL.
- Adjuntos: descarga bajo demanda, abrir/guardar/compartir.
- **Criterio:** un mensaje con HTML hostil no ejecuta scripts ni hace peticiones de red sin permiso.

### RF-05 Gestos y acciones
- Deslizar izquierda/derecha en la fila con **acción configurable**: archivar, borrar, mover, marcar leído/no leído, destacar, etiquetar.
- Pulsación larga → selección múltiple y acciones por lote.
- Deshacer (snackbar) para archivar/borrar/mover.
- **Criterios:** acción reflejada al instante en local y encolada; si falla el servidor se reintenta, nunca se pierde.

### RF-06 Mover / etiquetar con búsqueda
- Diálogo con lista de carpetas/etiquetas de la cuenta del mensaje y **campo de búsqueda con filtrado en vivo** (insensible a mayúsculas y tildes, también por ruta jerárquica).
- Recientes/frecuentes arriba.
- Gmail: casillas para aplicar/quitar varias etiquetas. Resto: mover a una carpeta.
- **Criterio:** teclear 2-3 letras deja el destino a un toque; funciona offline.

### RF-07 Redactar
- Nuevo, responder, responder a todos, reenviar; destinatarios con autocompletado (histórico local, contactos del sistema solo con permiso opcional).
- Adjuntos desde cámara, galería y selector de archivos.
- Borradores guardados automáticamente (local y carpeta Borradores del servidor).
- **Envío mediante cola** (WorkManager) que sobrevive a cierres; estados: pendiente, enviando, error, enviado; copia en Enviados.
- Texto enriquecido básico (negrita, cursiva, listas, enlaces); cita del original en respuestas.
- **Criterio:** un correo escrito sin conexión se envía solo al volver la red; un fallo permanente se muestra con opción de reintentar o conservar como borrador.

### RF-08 Firmas por cuenta
- Cada cuenta tiene su propia firma, editable en Ajustes de la cuenta y activable/desactivable.
- MVP: firma en **texto plano** (la rica queda en decisiones abiertas). Se almacena en Room junto a la cuenta.
- Se inserta automáticamente al redactar/responder/reenviar con el delimitador estándar `-- ` (guion guion espacio).
- Al responder o reenviar, la firma va **antes del texto citado** por defecto (ajuste por cuenta: antes/después).
- Al cambiar la cuenta remitente en el redactor, la firma se sustituye por la de la nueva cuenta sin tocar el texto del usuario.
- La firma no se duplica en borradores reabiertos ni en respuestas encadenadas.
- **Criterios:** dos cuentas con firmas distintas envían cada una la suya; cambiar de remitente cambia solo el bloque de firma; firma vacía/desactivada no añade delimitador.

### RF-09 Búsqueda
- Local: FTS de Room sobre remitente, destinatarios, asunto y cuerpos cacheados, con filtros (cuenta, carpeta/etiqueta, no leídos, adjuntos).
- Servidor: `SEARCH` IMAP (en Gmail `X-GM-RAW`) cuando el usuario lo solicita o los resultados locales no bastan; resultados mezclados y marcados.

### RF-10 Offline-first y sincronización
- **Room es la fuente de verdad.** La UI lee solo de Room.
- Sync por carpeta con `UIDVALIDITY`/`UIDNEXT`/`HIGHESTMODSEQ` y `CONDSTORE`/`QRESYNC` cuando existan; reconciliación de flags y borrados.
- Acciones del usuario en una **cola de operaciones pendientes** idempotente con backoff exponencial.
- Sync: al abrir, pull-to-refresh y periódica (~15 min, WorkManager). Sin IDLE en el MVP.
- Indicador discreto de "pendiente de sincronizar" por mensaje.
- Política offline configurable por cuenta (ventana de días, solo Wi-Fi para adjuntos).

### RF-11 Ajustes e internacionalización
- Inglés y español siguiendo el sistema; tema claro/oscuro/sistema y colores dinámicos.
- Ajustes globales: gestos, densidad, imágenes remotas. Por cuenta: nombre, firma, política offline, carpetas a sincronizar.

### RF-12 Exportar e importar cuentas
- Ajustes → "Exportar cuentas": un fichero de configuración (JSON cifrado) con, por cuenta, servidores, firma, política offline, carpetas sincronizadas y client ID de OAuth, más los ajustes de la app. **Nunca incluye correo.**
- Las credenciales no se exportan salvo que el usuario lo pida, y entonces van cifradas con una frase de contraseña (PBKDF2-HMAC-SHA256 + AES-GCM del JDK).
- "Importar cuentas" valida el fichero (versión, integridad, tamaño), recrea las cuentas y, si no traía credenciales, pide iniciar sesión de nuevo. Nada se sobrescribe sin confirmar; los duplicados se saltan.
- El fichero se crea y se abre con el selector de ficheros del sistema.

## 4. Fuera de alcance (MVP)
- Notificaciones push (IMAP IDLE) y servicio en primer plano. *(v1.1)*
- Posponer (snooze). *(v1.1)*
- PGP y S/MIME. *(v2)*
- Aliases/identidades múltiples por cuenta, firma rica (HTML), plantillas.
- JMAP, Exchange ActiveSync, POP3.
- Calendario, contactos propios, widgets, tablet/apaisado optimizado.
- Reglas/filtros de servidor, cualquier servicio de Google o telemetría.

## 5. Política de conflictos y consistencia
1. **Flags** (leído, destacado): gana el último cambio; las operaciones pendientes se reaplican sobre el estado del servidor tras cada sync.
2. **Mover/etiquetar** de un mensaje que ya no existe o cambió de UID: se resuelve por `Message-ID`/`X-GM-MSGID`; si desapareció, la operación se descarta y se avisa.
3. **Borrador editado en dos dispositivos:** no se pisa; se conservan ambas versiones y se avisa.
4. **UIDVALIDITY cambiada:** se invalida y resincroniza la carpeta sin perder operaciones pendientes ni borradores.
5. Operaciones **idempotentes** con backoff exponencial; el envío SMTP nunca se reintenta tras un acuse parcial dudoso sin confirmar en Enviados (evitar duplicados).

**Requisito de test:** cola de operaciones y resolutor con **100% de cobertura**, con un caso por regla más fallos de red a mitad de operación.

## 6. Requisitos no funcionales
- **Rendimiento:** arranque en frío con datos locales < 1,5 s en gama media; scroll a 60 fps con el volumen de referencia (~50.000 mensajes en Room, ventana de 90 días).
- **Privacidad:** sin telemetría ni terceros; tráfico solo entre dispositivo y servidores de correo/OAuth del usuario. TLS obligatorio con validación completa (CAs del sistema; no desactivar validación).
- **Seguridad:** credenciales cifradas; `allowBackup` desactivado o excluyendo datos sensibles; sin datos de correo en logs; HTML aislado.
- **Accesibilidad:** TalkBack, táctiles ≥ 48dp, escalado de fuente, contraste.
- **Robustez:** ninguna pérdida de correo, borrador o acción ante cierres, falta de red o errores del servidor.
- **Datos:** esquema Room versionado con migraciones probadas; claves `(accountId, ...)`.

## 7. Calidad y CI
- **GitHub Actions** en cada PR: build + detekt + ktlint + Android Lint + tests unitarios + Kover.
- **Kover:** ≥85% global en `domain`/`data`/`sync`; 100% en cola de operaciones y resolutor; excluidos generado, `@Preview` y UI pura.
- **Tests de protocolo/sync:** servidor IMAP/SMTP falso con caídas, respuestas NO/BAD, timeouts, UIDVALIDITY cambiada y cambios concurrentes.
- **Tests de UI (Compose):** añadir cuenta, gesto de archivar, mover con búsqueda, redactar y enviar offline, firma por cuenta.
- **Dependabot** semanal agrupado; **licencias:** la CI falla con dependencias no libres o Play Services; verificación de dependencias de Gradle.
- **Versionado:** SemVer; Conventional Commits.

## 8. Riesgos conocidos
1. **Librería IMAP en Android:** calidad, mantenimiento, soporte de CONDSTORE/QRESYNC y extensiones Gmail. Prototipo temprano (T03).
2. **OAuth2:** Google en modo pruebas limita usuarios y exige verificación para publicar con ámbito de correo; Microsoft requiere registro de app. Afecta a la publicación en F-Droid.
3. **Diferencias entre servidores:** Gmail vs Exchange Online vs Dovecot (hilos, etiquetas, carpetas especiales).
4. **Render seguro de HTML** sin filtrar contenido legítimo.
5. **Hilos** en servidores sin soporte nativo.
6. **Rendimiento** de listas grandes e hilos con FTS en Room.

## 9. Decisiones tomadas
- **Librería IMAP/SMTP (T03): Angus Mail 2.0.5** (`org.eclipse.angus:jakarta.mail`) más `gimap` para extensiones de Gmail. Licencia doble EPL-2.0 / GPL-2.0 con Classpath Exception (compatible con GPLv3); `jakarta.activation` es BSD-3-Clause (EDL 1.0). Soporta XOAUTH2, IDLE y CONDSTORE parcial; **no** QRESYNC. Es una API bloqueante: se envuelve en `data` con dispatchers de IO. Se descartó K-9/Thunderbird por no publicarse como artefacto Maven y un cliente propio por riesgo. Validado con GreenMail (carpetas, cabeceras con UID, mover, flags, SMTP autenticado). Pendiente de comprobar contra Gmail y M365 reales cuando haya credenciales (T02).
- **HTML seguro (T04): sanitizador propio + WebView aislado, en dos capas.** (1) `domain.html.HtmlSanitizer`, en Kotlin puro y sin dependencias nuevas (jsoup descartado: peso, y una lista blanca pequeña es más fácil de auditar): un tokenizador tolerante (lineal, sin excepciones) y una reescritura desde cero con lista blanca de etiquetas y atributos; nada del marcado original sobrevive salvo lo permitido. Se eliminan `script`, `iframe`, `object`, `embed`, SVG/MathML, formularios, `meta` (refresh), `link`, `base` y los manejadores `on*`; las URL se decodifican (entidades, espacios y caracteres de control) y se juzgan ya limpias, de modo que lo que se evalúa es lo que se emite: enlaces solo `http(s)`, `mailto`, `tel`; imágenes `cid:` y `data:image` ráster (nunca SVG); CSS sin `expression()`, `@import`, `behavior`, `-moz-binding`, `url()` salvo `cid:`/`data:image`, ni `position: fixed/absolute`. Las imágenes remotas no se borran: su dirección pasa a `data-blocked-src` y `SanitizedHtml.hadBlockedRemoteContent` lo notifica para ofrecer permitirlas en ese mensaje (se re-sanea con `allowRemoteContent`, y solo `https` carga). Cada enlace expone su texto visible y si parece otra dirección (`HtmlLink.deceptive`) para pedir confirmación. (2) `ui.html.SafeHtmlView`: WebView con JavaScript, acceso a archivos y contenido, DOM storage, geolocalización, ventanas múltiples y contenido mixto desactivados y caché desactivada; página con CSP `default-src 'none'; img-src data: cid: [https:]; style-src 'unsafe-inline'; base-uri 'none'; form-action 'none'` inyectada en un `<meta>` antes del mensaje; `shouldInterceptRequest` bloquea toda petición que `RequestPolicy` no permita, y sin permiso `blockNetworkLoads` apaga la red de la vista; los toques en enlaces nunca navegan dentro de la vista: pasan por un gancho de confirmación y se abren con un intent `ACTION_VIEW` (solo web, correo y teléfono). Corpus hostil en `HostileCorpusTest`/`LegitimateMailTest` (variantes de script, `javascript:` ofuscado con entidades/espacios/mayúsculas, SVG, `expression()`, meta refresh, `base`, marcado anidado y malformado, atributos enormes, píxel de seguimiento, boletín legítimo) y una prueba de basura aleatoria con semilla fija. Pantalla de depuración solo en builds debug (`SafeHtmlDebugActivity`, con opción de saltar el sanitizador para probar solo el WebView). **Queda:** comprobación en dispositivo de la entrega de `cid:` (hoy `CidResolver` es un gancho sin implementar hasta T15), permiso por remitente (RF-04, hoy solo por mensaje), homógrafos IDN en la detección de enlaces engañosos, tablas de entidades HTML completas (solo las que importan para URL), y revisar la política cuando T15 integre el cuerpo real.

- **OAuth con Google (T02, verificado en dispositivo con Gmail personal):** AppAuth con un cliente de tipo Android del propio usuario; el client ID se introduce en la app y se guarda en el dispositivo (la tabla de proveedores por host vive en `domain.oauth`). Lecciones: (1) Google exige activar "Enable custom URI scheme" en el cliente Android, si no devuelve `invalid_request`; (2) la cuenta debe figurar como usuario de prueba mientras la app esté en modo "Testing"; (3) `RedirectUriReceiverActivity` de AppAuth necesita un tema AppCompat o la app se cae al volver del navegador; (4) XOAUTH2 en Angus Mail va integrado (`mail.imap.auth.mechanisms=XOAUTH2`), nunca con `mail.imap.sasl.*`, porque `javax.security.sasl` no existe en Android. El refresh token de Google en modo pruebas caduca a los 7 días. **Microsoft (T02, sin verificar contra cuentas reales):** registro de app propio del usuario en Microsoft Entra (cualquier directorio y cuentas personales, plataforma "Mobile and desktop applications", cliente público con PKCE y sin secreto), endpoint `common`, ámbitos `openid email profile offline_access` más `https://outlook.office.com/IMAP.AccessAsUser.All` y `SMTP.Send`, redirección `<applicationId>:/oauth2redirect`; el client ID (un GUID) se introduce en la app como el de Google y la dirección de la cuenta sale del claim `email` del ID token o, si falta, de `preferred_username`. El refresco de tokens (`data.oauth.HttpOAuthTokenSource`, HTTPS plano contra el endpoint del proveedor) devuelve `Revoked` ante `invalid_grant` y similares (la cuenta pasa a reautenticación) y `TemporaryFailure` ante red o errores del servidor; rota el refresh token si el proveedor manda uno nuevo. Las organizaciones pueden exigir consentimiento del administrador y muchos inquilinos M365 tienen SMTP AUTH desactivado. Guía de registro: `docs/oauth-setup.md`.
- **Motor de sincronización (T10), paquete `sync.engine`.** `SyncEngine` sincroniza por cuenta (un solo sync a la vez por cuenta) publicando `SyncStatus` (reposo, sincronizando, error, reautenticación necesaria). Por carpeta: empuja primero la cola y luego baja (`FolderPuller`): compara UIDVALIDITY/UIDNEXT/HIGHESTMODSEQ, baja cabeceras nuevas dentro de la ventana en lotes de 200 empezando por las más nuevas (y se detiene al llegar a un lote entero fuera de ventana), reconcilia flags y borrados de lo ya conocido y asigna hilos con `ThreadResolver` (por eso `message` guarda `In-Reply-To` y `References`, esquema Room v2). El estado de la carpeta solo se guarda al terminar: un corte de red deja Room consistente y la siguiente pasada continúa. Sin CONDSTORE se vuelven a pedir las cabeceras de lo conocido en cada pasada (la interfaz `MailSession` no tiene una consulta solo de flags; mejora pendiente); con CONDSTORE y HIGHESTMODSEQ sin cambios no se pide nada. Las reglas de la sección 5 son las de `sync.conflict` (`FlagResolver`, `TargetResolver`, `FolderResetPlanner`, `SendResolver`); al cambiar UIDVALIDITY los mensajes con operaciones pendientes pasan a un uid negativo (que ningún servidor usa) hasta que se vuelve a encontrar el mensaje por Message-ID. Las operaciones de SEND buscan el Message-ID en Enviados antes de cada intento y tras un fallo ambiguo; no se añade copia a Enviados tras enviar (Gmail ya la guarda; en otros servidores queda para T18). Las credenciales vienen de `CredentialVault`; para OAuth se refresca el token con `OAuthTokenSource` y un `AuthenticationFailed` deja la cuenta en `ReauthenticationNeeded` sin borrar datos ni reintentar en segundo plano. WorkManager (`work-runtime`): trabajo periódico único de 15 min con red, y trabajo único a demanda sin `setExpedited` (antes de Android 12 exigiría un servicio en primer plano, fuera de alcance). **Queda:** nada se ha probado contra Gmail ni M365 reales ni en dispositivo; `DraftResolver` no está conectado (no hay modelo de borrador hasta T18); el cambio de la ventana offline no vuelve a bajar cabeceras antiguas ya descartadas.
- **Motor de redacción y envío (T18a), paquetes `domain.compose`, `sync.engine` y `data.local` (esquema Room v4, migración 3→4 probada).** Sin pantalla (T18b). **Editor de texto plano en el MVP**; el texto enriquecido (negrita, listas, enlaces de RF-07) queda diferido. **Modelo:** tablas `draft` (cuenta, tipo NEW/REPLY/REPLY_ALL/FORWARD, destinatarios como `Nombre <dir>`, asunto, cuerpo con firma y cita, In-Reply-To/References, mensaje origen, firma aplicada, `key` estable entre dispositivos, estado EDITING/OUTBOX) y `outgoing_attachment`, con borrado en cascada; los ficheros adjuntos viven en `noBackupFilesDir/outbox/<draftId>/` y `AccountRemoval` los borra. **Respuestas:** a Reply-To (hoy no almacenado, se usa From) o al remitente; responder a todos suma To y Cc sin las direcciones propias ni repetidas; prefijos `Re:`/`Fwd:` sin apilar (reconoce AW, SV, RV, ENC, WG...); cita con línea de atribución localizada (`compose_*` en strings.xml) y `> `; References recortadas a 20 (la primera y las últimas). **Adjuntos:** se copian al almacenamiento privado; aviso por encima de 20 MiB en total y rechazo por encima de 25 MiB (límite de Gmail; la codificación añade ~33 %, así que un total cercano al máximo aún puede ser rechazado por el servidor); no se leen límites SIZE de SMTP. **Envío:** `SendDraft` encola una operación SEND (clave `folderPath=""`, `uid=draftId`) con payload `v2` (compatible con `v1`), el Message-ID queda fijado al encolar y el borrador pasa a OUTBOX; el ejecutor reconstruye el MIME, y tras la aceptación SMTP (guardada en el borrador, nunca se reenvía) pone `\Answered`/`$Forwarded` en el mensaje origen si su UID sigue siendo ese Message-ID, añade copia en Enviados solo si el proveedor no la guarda (Gmail y Microsoft por host o por OAuth; el resto, APPEND), borra la copia del borrador en el servidor y el borrador local. Tras un fallo ambiguo se consulta Enviados con `SendResolver`. Un rechazo permanente deja el borrador en la bandeja de salida con motivo (reintentar, editar, descartar; editar o descartar se niegan si el mensaje pudo salir). **Borradores en servidor:** SAVE_DRAFT se refresca en sitio, se encola como mucho una vez por minuto salvo forzado, sube la nueva versión (`<um-draft.CLAVE.VERSION@dominio>`), borra la anterior y, si otro dispositivo cambió el mismo borrador, `DraftResolver` conserva ambas (la local sigue con clave nueva) y emite `SyncNotice.DraftConflict`; las copias del servidor no llevan adjuntos. **No hecho:** `AcceptServer`/`DiscardLocal` para borradores sin cambios locales (solo se usa el camino de subida); adjuntos al reenviar (T18b puede añadirlos desde la ruta local); Reply-To almacenado; sugerencias con contactos del sistema; nada probado contra servidores reales ni en dispositivo.
- **Cuerpos descargados durante el sync (T10b), reversión del supuesto "cuerpos bajo demanda" de la sección 2.** `BodyDownloader` descarga el texto y el HTML completos de los mensajes dentro de la ventana offline, después de que el pull de cabeceras haya terminado en todas las carpetas, del más nuevo al más antiguo y solo de carpetas con sync activado. La lista de trabajo es Room (mensajes sin `bodyText` ni `bodyHtml`): un sync cortado continúa en el siguiente y un cuerpo guardado no se vuelve a pedir. Límites por ejecución: 500 mensajes, 25 MB de texto/HTML o 2 minutos (`BodyBudget.DEFAULT`); lo que sobra sigue en el próximo sync. Los mensajes de más de 10 MB (tamaño que informa el servidor, adjuntos incluidos; `BodyDownloader.MAX_MESSAGE_BYTES`) se quedan bajo demanda. Un mensaje que falla no frena a los demás; tras 3 fallos se omite hasta reiniciar la app (contador solo en memoria: sin cambio de esquema); un corte de red o de login termina la ejecución (y WorkManager reintenta). Los adjuntos siguen bajo demanda, salvo las imágenes `cid:` que el HTML muestra y pesan como mucho 2 MB, que se bajan con el cuerpo (misma regla que T15 al abrir) para que el HTML se vea sin conexión. Interruptor por cuenta "Descargar mensajes para leer sin conexión" (activado por defecto) en los ajustes de la cuenta, guardado en SharedPreferences tras la interfaz `OfflineDownloads` (sin migración de Room; esquema sigue en la versión 3). `LoadMessageBody` sigue primero en caché. `AttachmentFileCleaner` borra los archivos de adjuntos cuya fila ya no existe (poda, expulsiones, movimientos) al final de cada sync; al borrar la cuenta se borra su carpeta entera. El estado `DownloadingBodies(hecho, total)` llega al menú lateral ("Descargando mensajes 120/800"). **Queda:** descargar solo con Wi-Fi (RF-10 lo pedía para adjuntos), encadenar ejecuciones cuando queda trabajo en un buzón grande (hoy continúa en el siguiente sync periódico) y verificación en dispositivo/servidor real.
- **Pantalla de redacción y bandeja de salida (T18b), paquete `ui.compose`.** `Screen.Compose(draftId)`: solo el id sobrevive a la muerte del proceso (el borrador vive en Room); el `ComposerViewModel` es de la actividad como los demás. **Texto plano** con cita y firma ya puestas por el motor; destinatarios como chips (coma, punto y coma, salto de línea o espacio tras una dirección completa; el texto que no es dirección queda como chip inválido resaltado y bloquea el envío), sugerencias locales de `RecipientSuggestions` (sin permiso de contactos), Cc/Cco plegados, selector De (cambiar de cuenta guarda primero y deja que el motor cambie solo el bloque de firma), adjuntos con `OpenMultipleDocuments`, aviso a partir de 20 MiB y rechazo por encima de 25 MiB, autosave con `ComposeEngine.autosave` (800 ms). **Enviar con deshacer de 5 s:** valida (sin destinatarios, destinatario inválido y adjunto desaparecido = mensaje; asunto vacío o cuerpo vacío sin adjuntos = confirmar; un reenvío sin texto no pregunta), cierra el redactor y muestra en el snackbar único (`NoticeCenter`, con `holdUntilCleared`) "Enviando…" con Deshacer; solo al acabar la ventana se llama a `SendDraft` (en un ámbito de aplicación, para que no se pierda si la pantalla se cierra). Deshacer reabre el mismo borrador, nada se encoló. Si el proceso muere dentro de esos 5 s, el mensaje sigue siendo un borrador (ni perdido ni enviado). Atrás guarda el borrador ("Borrador guardado") y pide `DraftServerSync.request(force = true)`; un borrador nuevo sin tocar se elimina en silencio; Descartar (menú) pregunta y borra. **Puntos de entrada:** botón flotante "Redactar" (extendido, no se oculta al desplazar), Responder/Responder a todos/Reenviar del lector (`ComposeLauncher` real, `QueuedComposeLauncher` + `ComposeEntryViewModel`), tocar un borrador, Editar desde la bandeja de salida, y los intents `ACTION_SENDTO` con `mailto:`, `ACTION_SEND` y `ACTION_SEND_MULTIPLE` (`IncomingParser`, código puro: solo URI `content:` ajenas a la propia app, límites de destinatarios/adjuntos/longitudes, saltos de línea del asunto neutralizados; los ficheros se leen solo a través de `AttachmentSource`). Cuenta de partida: la que se muestra; si hay varias y ninguna se muestra (bandeja unificada), se pregunta. **Borradores y salida:** la carpeta Borradores del menú muestra borradores locales y copias solo del servidor (`observeDrafts`); los locales se abren y se borran tras confirmar; los solo del servidor se abren en el lector (el motor aún no los importa). "Bandeja de salida" en el menú, con insignia y solo mientras no esté vacía, lista en cola/enviando/fallido con el motivo traducido (`OutboxReason`) y Reintentar/Editar/Descartar; si pudo enviarse ya (`MAY_BE_SENT`) se explica que hay que mirar Enviados. Un aviso en el snackbar avisa cuando un envío falla del todo. **No hecho:** texto enriquecido, ocultar el botón al desplazar, deslizar para borrar borradores, importar borradores del servidor al redactor, adjuntos al reenviar; nada probado en dispositivo ni con servidores reales.

## 10. Decisiones abiertas
- Firma rica (HTML) en versión posterior; colocación por defecto de la firma en respuestas.
- Cliente OAuth propio de Google/Microsoft y gestión de sus credenciales públicas en el repo.
- Ventana offline por defecto (90 días propuesta).
- Estrategia de builds reproducibles (firma, versiones de Gradle/AGP fijadas).

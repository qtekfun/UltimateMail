# UltimateMail — Plan de tareas

Reglas: una tarea cada vez, en su rama `feat/<tarea>`, con `./gradlew check` en verde antes de cerrarla. Marca `[x]` al completar. Cada tarea debe poder verificarse (test o prueba manual descrita).

## Fase 0 — Cimientos y prototipos de riesgo
- [x] **T00 Proyecto base**: módulo Android, Gradle KTS, `libs.versions.toml`, Hilt, Compose, tema Material 3, `strings.xml` en/es, cabeceras SPDX, `LICENSE` (GPLv3).
  - *Verificación:* `./gradlew assembleDebug` compila y la app arranca con pantalla vacía.
- [x] **T01 CI y calidad**: detekt, ktlint, Lint (warnings como errores), Kover con umbrales, verificación de dependencias, chequeo de licencias/Play Services, GitHub Actions, Dependabot.
  - *Verificación:* un PR de prueba pasa CI; una dependencia de Play Services añadida a propósito la hace fallar.
- [x] **T02 Prototipo OAuth2**: AppAuth con Google y Microsoft, obtención de token y login IMAP XOAUTH2 contra cuentas reales de prueba. *(Gmail personal verificado en dispositivo; Microsoft implementado y probado con tests unitarios, Microsoft sin verificar contra cuentas reales. Alta de cuenta con OAuth en la app, refresco de tokens y guía en `docs/oauth-setup.md`.)*
  - *Verificación:* demo manual con ambas cuentas; decisiones sobre clientes OAuth documentadas en `SPEC.md` (sección 9).
- [x] **T03 Prototipo librería IMAP/SMTP**: evaluar candidatas (K-9/Thunderbird, Angus/Jakarta Mail) en licencia, mantenimiento, CONDSTORE/QRESYNC, extensiones Gmail, STARTTLS/TLS; listar carpetas, bajar cabeceras, mover y enviar.
  - *Verificación:* tabla comparativa y decisión en `SPEC.md`; pruebas contra Gmail, M365 y Dovecot. *(Hecho con GreenMail; la prueba contra Gmail y M365 reales queda ligada a T02.)*
- [x] **T04 Prototipo HTML seguro**: WebView aislado sin JS, bloqueo de remotos, corpus de correos hostiles.
  - *Verificación:* tests/manual con el corpus; decisión documentada.

## Fase 1 — Datos y cuentas
- [x] **T05 Modelo Room**: cuenta (incluye firma y política offline), carpeta/etiqueta, mensaje, hilo, adjunto, cola de operaciones, FTS; migraciones y tests.
- [x] **T06 Cuentas y credenciales**: alta con autodetección, contraseña de app y OAuth2, cifrado Keystore, refresco de tokens, eliminación que limpia datos.
- [x] **T07 Cliente IMAP/SMTP (capa `data`)**: envoltorio de la librería elegida en T03 detrás de interfaces de `domain`; tests con servidor falso (NO/BAD, timeouts, caídas).

## Fase 2 — Sincronización (lo más crítico)
- [x] **T08 Cola de operaciones pendientes**: idempotente, backoff exponencial, persistida. **100% de cobertura.**
- [x] **T09 Resolutor de consistencia**: reglas de la sección 5 de `SPEC.md`. **100% de cobertura**, un test por regla más fallos a mitad de operación.
- [x] **T10 Motor de sincronización**: UIDVALIDITY/UIDNEXT/CONDSTORE/QRESYNC, ventana offline por cuenta, WorkManager periódico (~15 min), sync al abrir y pull-to-refresh.
- [x] **T10b Descarga de cuerpos para uso offline**: el sync descarga los cuerpos completos (texto y HTML) de los mensajes dentro de la ventana offline, con presupuesto por ejecución, reanudable, tope de 10 MB por mensaje, imágenes `cid:` ≤ 2 MB, interruptor por cuenta (activado por defecto), progreso en el menú lateral y limpieza de archivos de adjuntos huérfanos.
- [x] **T11 Hilos**: `X-GM-THRID`, `THREAD` y algoritmo References/asunto, con tests.
- [x] **T12 Tests de sync offline**: caídas de red, UIDVALIDITY cambiada, cambios concurrentes, app cerrada a mitad de sync.

## Fase 3 — Interfaz del MVP
- [x] **T13 Añadir cuenta y lista de carpetas/etiquetas** (offline funcional). *(Solo contraseña de aplicación; el acceso con Google/Microsoft queda para después.)*
- [x] **T14 Bandeja y bandeja unificada**: lista de conversaciones, paginación, indicador "pendiente de sync".
- [x] **T14b Menú lateral de carpetas**: navigation drawer estilo Gmail con la bandeja unificada arriba, el selector de cuenta en la cabecera y todas las carpetas y etiquetas de la cuenta (especiales primero, jerarquía, contadores de no leídos); la pantalla de carpetas de T13 pasa a ser el contenido del menú. Debe ser la base de navegación de T15–T21.
- [x] **T15 Lectura de conversación**: mensajes plegables, HTML seguro (según T04), adjuntos bajo demanda.
- [x] **T16 Gestos configurables y selección múltiple**, con deshacer.
- [x] **T17 Selector mover/etiquetar con búsqueda**: diálogo con filtro en vivo, recientes, etiquetas múltiples en Gmail.
- [x] **T18 Redactar y cola de envío**: responder/reenviar, borradores, adjuntos, autocompletado, texto enriquecido básico.
- [x] **T19 Firmas por cuenta**: editor en ajustes de cuenta, inserción automática con `-- `, posición en respuestas, cambio de remitente, sin duplicados.
  - *Verificación:* tests unitarios de la lógica de firma (`domain`) y test de UI con dos cuentas.
- [x] **T20 Búsqueda**: local (FTS) y en servidor.
- [x] **T21 Ajustes**: tema, colores dinámicos, idioma, gestos, política offline por cuenta.

## Fase 4 — Cierre del MVP
- [x] **T22 Accesibilidad y rendimiento**: TalkBack, táctiles, fuente grande; medir arranque y scroll con el volumen de referencia. (auditoría y correcciones por código hechas; arranque, desplazamiento con 50.000 conversaciones y memoria medidos en el PGEM10 y dentro de los umbrales, ver docs/performance-notes.md; fuente al 200 % revisada en el menú y la lista; la pasada manual con TalkBack se omitió por decisión del usuario, 2026-10-04)
- [x] **T23 Tests de UI clave (Compose)** (compilan; ejecutarlos requiere un dispositivo o emulador aparte): añadir cuenta, archivar por gesto, mover con búsqueda, enviar offline, firma por cuenta, reautenticación y densidad; ver `docs/ui-tests.md`.
- [x] **T24 Metadatos F-Droid** *(metadatos, receta y capturas listos; el icono 512 se generó del vector adaptativo; la receta lleva marcadores REPLACE_ hasta que exista un tag)*: `fastlane/metadata/android/{en-US,es-ES}/`, iconos, capturas (bandeja unificada y lectura, con datos de demostración `@seed.invalid`); revisar builds reproducibles y ausencia de dependencias no libres.
- [x] **T25 Documentación**: `README.md`, `CONTRIBUTING.md`, política de privacidad, `CHANGELOG.md`.

- [x] **T26 Exportar e importar cuentas y ajustes**: fichero cifrado con la configuración (sin correo); credenciales solo si el usuario lo pide, con frase de contraseña; selector de ficheros del sistema; importar valida el fichero y recrea las cuentas pidiendo iniciar sesión de nuevo.

- [x] **T27 Pantalla de reautenticación**: salida cuando una cuenta queda en `ReauthenticationNeeded` (token revocado o caducado, contraseña cambiada, cuenta importada sin credenciales): contraseña (probada con el tester de conexión) o inicio de sesión del proveedor (AppAuth, con la dirección como pista y rechazando otra dirección); solo se sustituyen las credenciales, sin perder nada local; accesible desde el menú lateral y los ajustes de la cuenta.

## Después del MVP (backlog, no implementar aún)
- v1.1: notificaciones push con IMAP IDLE, snooze.
- v2: PGP y S/MIME.
- Aliases/identidades múltiples, firma rica (HTML), plantillas.
- Tablet y apaisado, widgets.
- Más idiomas.
- **Mejora a muy largo plazo: acceso OAuth2 con Google y Microsoft.** Ya está implementado y probado
  pero oculto (`OAuthFeature.ENABLED = false`): para Gmail exige una app verificada por Google (evaluación
  de seguridad de pago) y sin ella el token caduca a la semana; Microsoft exige registrar una app por
  usuario. Reactivarlo implica: decidir cómo se consiguen los clientes (uno compartido verificado o uno por
  usuario), probarlo con cuentas reales de ambos proveedores, volver a poner el interruptor y retirar la
  nota de `docs/oauth-setup.md`. Mientras tanto, contraseña de aplicación.

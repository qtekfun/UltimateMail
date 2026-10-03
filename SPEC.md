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
| Almacenamiento offline | Por cuenta y configurable: por defecto cabeceras de los últimos 90 días, cuerpos y adjuntos bajo demanda; opción de ventana distinta o buzón completo |
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

## 10. Decisiones abiertas
- Firma rica (HTML) en versión posterior; colocación por defecto de la firma en respuestas.
- Cliente OAuth propio de Google/Microsoft y gestión de sus credenciales públicas en el repo.
- Ventana offline por defecto (90 días propuesta).
- Estrategia de builds reproducibles (firma, versiones de Gradle/AGP fijadas).

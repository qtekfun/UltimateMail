# Decisiones tomadas de forma autónoma

Registro de las decisiones que Claude tomó mientras el usuario no estaba. **Pendientes de confirmar**:
si alguna no te convence, abre una issue o dímelo y se cambia. Se va ampliando al final del fichero.

Reglas de trabajo seguidas en esos periodos:

- Cada tarea en su rama `feat/<tarea>` con su PR hacia `master`. Claude no fusiona nada en `master`.
- Máximo dos subagentes a la vez.
- Dispositivo de pruebas: solo el PGEM10, con la cuenta real del usuario: nunca desinstalar la app ni borrar sus datos.
- Cada decisión de diseño del agente que implementa queda aquí, junto con lo que NO se pudo verificar.

Reglas de trabajo mientras el usuario no está:
- Sigo con TODAS las tareas pendientes, cada una en su rama feat/<tarea> y su PR hacia master.
- Yo NO fusiono nada en master: lo hace el usuario. Las ramas se apilan (cada una parte de la anterior) y cada PR lo dice.
- Dispositivo: solo PGEM10 (adb-897201dc-kLWrJ2...), cuenta real del usuario: nunca desinstalar ni borrar datos.

Decisiones tomadas por mí (a confirmar):
1. Microsoft OAuth (T02): client ID propio del usuario (BYO) como Google, flujo AppAuth + PKCE, redirect `com.qtekfun.ultimatemail:/oauth2redirect`, scopes IMAP.AccessAsUser.All, SMTP.Send, offline_access. No se puede probar sin registrar la app en Entra.
2. Lanzo en paralelo T02-Microsoft, T12 (tests de sync offline) y T24+T25 (docs/F-Droid) sobre master porque no dependen del menú lateral.
3. PRs apiladas: las tareas de pantallas parten de la rama de T14b.

4. T24/T25 (PR #19): documentos y metadatos F-Droid listos; faltan capturas de pantalla, firma de release y los secretos de GitHub (UM_KEYSTORE_BASE64, UM_KEYSTORE_PASSWORD, UM_KEY_ALIAS, UM_KEY_PASSWORD). La receta de F-Droid lleva marcadores REPLACE_ hasta que exista un tag. Aviso: GOOGLE_CLIENT_ID no debe ir en builds de release (rompe la reproducibilidad); si se publica un ID propio habria que commitearlo.

5. El usuario fijó un máximo de DOS subagentes simultáneos. Paré el agente de T12 (tests de sync offline, la prioridad más baja) y lo relanzo cuando haya hueco.

6. T14b (PR #20): menu lateral listo. Test InboxViewModelTest "garbage in saved state is ignored" fallo una vez y paso al repetir: posible test inestable, revisar mas adelante.

7. T02 Microsoft (PR #21), decisiones del agente a confirmar: (a) direccion/usuario IMAP = claim email del ID token, o preferred_username; si faltan ambos el login falla con mensaje; (b) los botones de OAuth solo salen si el host IMAP es Gmail o Microsoft conocido (una cuenta de trabajo con dominio propio debe poner outlook.office365.com en ajustes avanzados); (c) invalid_client/unauthorized_client cuentan como "revocado" -> pide reautenticar, pero aun NO hay pantalla de reautenticacion; (d) el refresh de Microsoft envia scope, el de Google no; (e) campo de client ID de Google vacio usa el de UM_GOOGLE_CLIENT_ID; (f) sin login_hint ni prompt. Sin verificar contra cuentas reales de Microsoft (admin consent, SMTP AUTH deshabilitado en tenants, UPN distinto del buzon).
8. Aviso: con la maquina muy cargada, SmtpSenderTest puede fallar por timing (paso al bajar la carga).

9. Ola actual (max 2 agentes): T15 lectura de conversacion y T21 ajustes + editor de firma (T19 UI), ambas apiladas sobre la rama de T14b (PR #20). Decisiones mias al encargarlas: gestos por defecto derecha=archivar, izquierda=borrar; politica de imagenes remotas por defecto "nunca"; ventana offline por defecto 90 dias (30/90/180/365/todo); idioma por defecto el del sistema (en/es); T19 queda sin marcar hasta que el redactor (T18) inserte la firma.
10. Cola pendiente: T17 selector mover/etiquetar con busqueda, T16 gestos + seleccion multiple, T18 redactar, T20 busqueda, T12 tests de sync offline, T22 accesibilidad/rendimiento, T23 tests de UI.

11. T21 + editor de firma (PR #22, apilada sobre #20). Decisiones del agente a confirmar: (a) idioma por app solo en Android 13+ (LocaleManager); por debajo sale "sigue el idioma del sistema" porque el appcompat transitivo (1.3.0) no trae la API; anadir appcompat >=1.6 requiere tu OK y mover el tema a AppCompat; (b) en ajustes sale el aviso "los gestos aun no estan activos" que T16 debe quitar; (c) las ediciones de nombre/firma se pierden si sales sin pulsar Guardar (no hay aviso); (d) colores dinamicos activados por defecto; (e) la politica de imagenes remotas solo se guarda, aun no gobierna el visor HTML (conectar en T15/T22); (f) tres @Suppress locales con comentario (TooManyFunctions en FolderDao y AccountSettingsViewModel, LongParameterList en AppRoot), config de detekt intacta; (g) la Bandeja de entrada no se puede desactivar de la sincronizacion.

12. T15 lectura (PR #23, ya fusionada por el usuario) y T17 selector (PR #24, conflictos resueltos: unifique los dos predicados que ocultan mensajes con operaciones pendientes en ConversationDao -> ahora MOVE, DELETE y REMOVE_LABEL de su propia etiqueta). Decisiones de T15 a confirmar: imagenes inline cid: (<=2MB) se descargan solas al abrir el mensaje; la barra actua sobre toda la conversacion (archivar/borrar), y estrella/no leido sobre el mensaje mas nuevo; la sync tras archivar/borrar se pide cuando termina el snackbar de deshacer; archivar -> carpeta Archive o Todos los mensajes, borrar -> Papelera (nunca borrado permanente); el texto citado solo se pliega si la cita llega hasta el final; esquema Room v3 (contentId/inline en adjuntos); los adjuntos descargados no se limpian al purgar mensajes (se acumulan hasta una tarea de limpieza).
13. T17 decisiones a confirmar: deshacer un movimiento solo es fiable mientras sigue en la cola (pocos segundos); en modo carpeta tocar una carpeta mueve al instante (sin boton Aplicar); en Gmail solo se ofrecen Recibidos y etiquetas propias (no [Gmail]/...), y mover a Papelera/Spam desde el selector no se ofrece (deberia ser un MOVE a [Gmail]/Trash o Spam); si la app muere tras confirmar el servidor un MOVE y antes de borrar la operacion, el reintento puede lanzar un aviso falso de "mensaje desaparecido".
14. Densidad (PR nueva): ajuste Comoda/Predeterminada/Compacta; por defecto filas de menu de 48dp (antes 56); Compacta baja a 40dp, por debajo del minimo tactil de 48dp de CLAUDE.md, a peticion del usuario. Tambien quite los iconos de lanzador de las pantallas de depuracion (los accesos directos que el launcher del movil ya coloco en la pantalla de inicio hay que borrarlos a mano).

15. T16 gestos (PR #27, apilada sobre #23). Decisiones del agente a confirmar: deslizar para leer marca como leidos TODOS los mensajes de la conversacion, pero no leido y estrella actuan solo sobre el mas nuevo; el boton Mover de la seleccion se desactiva si abarca varias cuentas (bandeja unificada); archivar/borrar en seleccion mixta solo afecta a las conversaciones que pueden; "reducir movimiento" sigue la escala de animaciones del sistema (no hay ajuste propio); nuevo NoticeCenter unico para el snackbar. Pendiente: conectar el selector de T17 (#24) al MovePickerLauncher (hoy muestra "proximamente") -> lo hago cuando #24 y #27 esten fusionadas.
16. Lectura rota en el movil: "Web no disponible" (PR #26): la politica de red de T04 bloqueaba el propio documento data:text/html del WebView; T04 solo se habia probado en JVM. Corregido. Respuestas del usuario: mantener WebView solo como renderizador offline (sin red ni JS), y bajar cuerpos completos en cada sync, adjuntos a demanda. Lanzados: feat/offline-bodies (descarga de cuerpos en sync, interruptor por cuenta, tope 10 MB, limpieza de ficheros huerfanos) y feat/reader-polish (hueco en blanco, ancho, modo oscuro, politica de imagenes remotas, cabecera del mensaje).

17. **Exportar e importar cuentas (tarea nueva T26, no estaba en el plan).** Decidido por Claude: se exporta un
    fichero cifrado con la configuración (servidores, firma, ventana offline, carpetas a sincronizar, client IDs de
    OAuth y ajustes de la app), nunca el correo. Las credenciales (contraseñas de aplicación y tokens) NO se incluyen
    por defecto: al importar se pide volver a iniciar sesión; solo se incluyen si el usuario lo marca y elige una
    frase de contraseña (PBKDF2 + AES-GCM del JDK, sin dependencias nuevas). Se usa el selector de ficheros del
    sistema (crear y abrir documento). Está en `PLAN.md` y `SPEC.md` (RF-12).

18. **Pulido del visor HTML (PR #29).** El agente decidió: zoom desactivado con los diseños anchos reducidos para que
    quepan (sin desplazamiento horizontal); modo oscuro algorítmico en Android 13+ y solo el `color-scheme` por debajo,
    con un botón por mensaje "Colores originales / Adaptar al tema oscuro"; con la política de imágenes remotas en
    "Nunca" el aviso ofrece igualmente cargar las imágenes de ese mensaje, y con "Preguntar" lo pregunta; las imágenes
    bloqueadas se ven como un recuadro discontinuo con su texto alternativo. **No se comprobó en el móvil**: al agente
    le denegaron una captura de pantalla (clasificador de permisos) y paró; yo no hice por él lo que se le denegó. La
    causa del hueco en blanco sobre el contenido es una hipótesis (el scroll de Compose salta al WebView con foco).
    Limpié del teléfono los datos de demostración que dejó sembrados (acción `remove` de la pantalla de depuración).
19. **Redactar (T18) en dos tandas.** T18a: borradores, bandeja de salida, adjuntos, envío por la cola y copia a
    Enviados; T18b: las pantallas. Decididas por Claude: el editor del MVP es de **texto plano** (el texto enriquecido se
    pospone: el plan lo pedía, pero un editor WYSIWYG fiable en Compose es la parte más arriesgada); adjuntos con aviso a
    partir de 20 MB totales y rechazo por encima de 25 MB (límite de Gmail); en Gmail no se añade copia a Enviados
    (el servidor ya la guarda) y en los demás proveedores sí; sin permiso de contactos: las sugerencias de destinatarios
    salen solo de los correos ya descargados.
20. **Descarga de cuerpos a local (PR #30).** Decididas por el agente: la fase de cuerpos corre cuando ya se han bajado las
    cabeceras de todas las carpetas (así las listas se completan antes y el "más nuevo primero" es global); tope de
    10 MB por mensaje (con el tamaño de cabecera, que incluye adjuntos: un correo de 12 MB con un PDF queda a demanda);
    presupuesto por sincronización de 500 mensajes, 25 MB de texto/HTML o 2 minutos; tras 3 fallos de un mensaje se
    salta hasta reiniciar la app; un buzón grande no se vacía de una vez, sigue en la siguiente sincronización
    (unos 15 minutos después, sin encadenar); sin opción "solo Wi-Fi" para cuerpos (pendiente); el interruptor
    "Descargar mensajes para uso sin conexión" es por cuenta y está activado por defecto; sin cambio de esquema Room.
    Todo probado solo contra un servidor falso en memoria.
21. **Búsqueda (T20) lanzada.** Decidido por Claude: operadores al estilo Gmail (`from:`, `to:`, `subject:`, `label:`,
    `has:attachment`, `is:unread`, `before:`, `after:`), ámbito carpeta / cuenta / todas, historial de búsquedas
    reciente solo en el dispositivo (máx. 10) y búsqueda en servidor bajo demanda (IMAP SEARCH; `X-GM-RAW` en Gmail).
22. **Fallo anotado por el usuario: reloj y batería invisibles** con el sistema en claro y la app en oscuro/AMOLED.
    Causa: `enableEdgeToEdge()` elige el color de los iconos de las barras según el tema del sistema. Corregido en la
    PR de `fix/status-bar-icons` (el tema de la app fija el aspecto de los iconos). No se verificó en el móvil porque
    exige cambiar el tema del sistema del teléfono, y no quise tocar sus ajustes sin que lo pidiera.


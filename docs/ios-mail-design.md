<!--
SPDX-FileCopyrightText: 2026 UltimateMail contributors
SPDX-License-Identifier: GPL-3.0-or-later
-->

# Rediseño al estilo de Mail de iOS

Petición del usuario (2026-10-04): que la app se parezca mucho más a Mail de iOS: barra de búsqueda
inferior junto con un botón de redactar con icono, selección rápida, vista previa de los correos y
solo lo imprescindible en el menú de hamburguesa, como la pantalla de buzones de iOS.

Esto es una **adaptación del diseño**, no una copia de recursos: no se usan iconos, tipografías ni
materiales de Apple (SF Symbols, Liquid Glass). Se dibujan con iconos de Material y colores del tema
(el azul de iOS como acento por defecto cuando los colores dinámicos están apagados).

## Qué dice la documentación (fuentes)

- Apple, *Organize email in mailboxes on iPhone* y *Use mailboxes to organize email messages*
  ([support.apple.com/en-us/104971](https://support.apple.com/en-us/104971)): la lista de buzones
  tiene un botón **Editar** arriba a la derecha para elegir qué buzones se ven (por ejemplo VIP);
  las carpetas propias cuelgan de su cuenta.
- Apple, *Check your email in Mail on iPhone*
  ([iph461684497](https://support.apple.com/en-gb/guide/iphone/iph461684497/ios)) y los artículos de
  iDownloadBlog sobre la vista previa
  ([idownloadblog.com](https://www.idownloadblog.com/2019/03/29/change-mail-message-list-preview-ios-mac/)):
  cada fila enseña **2 líneas** de vista previa por defecto, configurable de **ninguna a 5**; deslizar
  a la derecha deja marcar como no leído; deslizar a la izquierda ofrece Más, Marcar con bandera y
  Papelera; **Editar** permite elegir varios correos y actuar con *Marcar*, *Mover*, *Papelera*.
- WWDC25 sesión 323, *Build a SwiftUI app with the new design*
  ([developer.apple.com/videos/play/wwdc2025/323](https://developer.apple.com/videos/play/wwdc2025/323/)):
  en Mail la barra inferior lleva un elemento de **filtro alineado a la izquierda** y un grupo a la
  derecha con la **búsqueda y redactar**; el campo de búsqueda va en la parte baja, cerca del pulgar, y
  según el espacio el sistema lo **reduce a un botón** que, al tocarlo, abre un campo de ancho completo
  sobre el teclado.
- Apple, *New features available with iOS 26* (PDF): la búsqueda queda abajo en Mensajes, Mail, Notas
  y Música (no se puede subir).
- Apple, categorías de Mail (iOS 18.2): Principal, Transacciones, Novedades y Promociones y vista de
  lista alternativa. **No se copian**: es clasificación automática del lado de Apple y está fuera de
  alcance (se anota como posible mejora).

## Pantallas objetivo

### Lista de mensajes (pantalla principal)
- **Título grande** con el nombre del buzón (Recibidos, Todas las bandejas...) que se encoge al
  desplazar; debajo, en pequeño, "Actualizado ahora / a las 12:03" (o el progreso de sincronización).
- Arriba a la derecha el texto **Editar** (selección). Arriba a la izquierda el botón del menú
  (hamburguesa), que es la pantalla de buzones de abajo.
- **Fila**, sin avatar por defecto (ajuste "Mostrar avatares"):
  - punto azul de no leído a la izquierda (el espacio se reserva siempre para alinear);
  - línea 1: remitente (negrita si no leído) y, a la derecha, hora; si el hilo tiene varios mensajes,
    su número;
  - línea 2: asunto (semi-negrita) con los iconos de adjunto y bandera naranja a la derecha;
  - líneas 3 a N: vista previa en color secundario, **2 por defecto, de 0 a 5** en Ajustes;
  - etiquetas de Gmail y marca de cuenta (bandeja unificada) como ahora, más pequeñas;
  - separador inset alineado con el texto, no con el borde.
- **Barra inferior flotante** (cápsula con elevación, sin desenfoque):
  `[filtro]  [ Buscar ............ ]  [redactar]`.
  - *Filtro*: icono de embudo; un toque abre un menú Todos / No leídos / Destacados / Con adjuntos y,
    si hay filtro activo, el icono se rellena y el subtítulo dice "Filtrado por: No leídos".
  - *Buscar*: cápsula de campo falso; al tocarla se abre la búsqueda actual a pantalla completa con el
    teclado. En pantallas estrechas con la selección activa se reduce a un icono.
  - *Redactar*: botón redondo con icono de lápiz sobre papel; sustituye al FAB extendido.
  - Con la **selección activa** la barra pasa a las acciones: *Marcar* (leído, no leído, bandera),
    *Mover*, *Archivar* y *Papelera*, y arriba "Seleccionar todo" y "Listo".
- **Selección rápida**: **Editar** y también pulsación larga; en modo selección un toque alterna cada
  fila (círculo a la izquierda en lugar del punto azul).
- **Gestos** (acciones configurables, como hoy): por defecto derecha = marcar leído/no leído (azul) e
  izquierda = papelera (rojo), como iOS. La revelación de tres botones de iOS (Más, Bandera, Papelera)
  no se copia: queda una acción por lado, configurable.

### Buzones (menú de hamburguesa, imprescindible solo)
- Arriba **Todas las bandejas** (la unificada) y la **bandeja de entrada de cada cuenta** con su
  contador de no leídos; ese es el "selector de bandejas de entrada" de iOS.
- Debajo, los buzones especiales con contador: Destacados, Borradores, Enviados, Archivo / Todos los
  mensajes, Spam y Papelera (de la cuenta activa o de todas en la unificada), más Bandeja de salida si
  hay algo pendiente.
- Una sección por cuenta (plegada por defecto) con sus **carpetas y etiquetas**; así las 200 etiquetas
  de Gmail no ocupan el menú. Un buscador de etiquetas queda como mejora.
- Abajo, una sola línea de estado de sincronización y **Ajustes**. Se quita lo demás (selector de
  cuenta en cabecera con desplegable, árbol siempre abierto).

### Lectura
- Arriba: botón atrás con el nombre del buzón, y a la derecha **flechas anterior/siguiente mensaje** de
  la lista.
- Barra inferior: **Papelera** (o Archivar según ajuste), **Mover**, **Responder** (con menú:
  responder, responder a todos, reenviar) y **Redactar**. La estrella y "no leído" pasan al menú ⋮.
- Asunto grande y remitente con su hora, como ahora; vista previa de imágenes remotas igual.

## Ajustes nuevos
- Vista previa: Ninguna, 1, 2 (por defecto), 3, 4, 5 líneas.
- Mostrar avatares: apagado por defecto.
- Acento estilo iOS: azul por defecto si los colores dinámicos están apagados (los dinámicos siguen
  encendidos por defecto como hoy).

## Fases (una PR cada una, de abajo a arriba)
1. **Fila** (hecha, PR de la fase 1, decisión 36): punto azul, vista previa configurable, avatares opcionales, tipografía y separadores.
2. **Menú de buzones**: la estructura de arriba (sin tocar la lista).
3. **Lista**: título grande con "Editar", barra inferior (filtro, búsqueda, redactar), selección con
   círculos y barra de acciones, valores por defecto de los gestos.
4. **Lectura**: flechas y barra inferior.
5. **Pulido**: modo reducido de la búsqueda, tests de interfaz (documentados, no ejecutables en el
   móvil del usuario), capturas para F-Droid actualizadas.

Cada fase mantiene verdes `check`, el rendimiento (arranque < 1,5 s, desplazamiento ≤ 5 % de
fotogramas lentos con 50.000 conversaciones en producción) y la accesibilidad (táctiles de 48 dp,
`contentDescription`, fuente al 200 %).

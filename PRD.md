# UltimateMail — PRD

Versión: 0.1 · Estado: borrador

## 1. Resumen
UltimateMail es un cliente de correo Android libre (GPLv3, F-Droid) para cuentas IMAP/SMTP, con la limpieza y los gestos de Mail de iOS y la organización visual de Gmail. Offline-first y sin telemetría.

## 2. Problema
Thunderbird para Android no convence al autor por:
1. UI anticuada y poco amigable.
2. Gestos lentos o ausentes al triar correo.
3. Rendimiento y sincronización mejorables.
4. Lectura y redacción de correos incómodas.
5. **No se puede buscar dentro de la lista de etiquetas/carpetas al mover un correo.**

## 3. Usuarios objetivo
- Primario: el autor (Gmail + Microsoft 365/Outlook, varias cuentas).
- Secundario: usuarios de Android que valoran software libre y privacidad, con cuentas IMAP.

## 4. Objetivos
- Triar la bandeja deprisa: gestos configurables, selección múltiple.
- Mover a etiqueta/carpeta buscando por nombre en segundos.
- Funcionar offline y no perder nunca un correo, borrador o acción.
- Varias cuentas con bandeja unificada y **firma propia por cuenta**.
- Ser publicable en F-Droid.

## 5. No objetivos (por ahora)
Notificaciones push (IMAP IDLE), snooze, PGP/S-MIME, aliases/identidades múltiples por cuenta, JMAP, tablet/apaisado optimizado, calendario y contactos propios.

## 6. Funcionalidades del MVP (prioridad)
1. **P0** Cuentas: IMAP/SMTP con contraseña de app y OAuth2 (Google, Microsoft); multicuenta con bandeja unificada.
2. **P0** Bandeja y conversaciones (hilos), lectura con HTML seguro.
3. **P0** Gestos de deslizamiento configurables; acciones por lote.
4. **P0** Selector de mover/etiquetar con búsqueda (diálogo con lista y filtro). En Gmail, etiquetas reales.
5. **P0** Redactar, responder, reenviar, adjuntos, borradores, envío offline con cola.
6. **P0** **Firma por cuenta** (activable, editable, aplicada automáticamente al redactar/responder/reenviar).
7. **P1** Búsqueda de correos: local (FTS) y en servidor.
8. **P1** Ajustes: tema, colores dinámicos, idioma, política offline por cuenta.

## 7. Métricas de éxito
- El autor sustituye Thunderbird como app diaria durante 30 días.
- Mover un correo a una etiqueta buscada: ≤ 3 toques.
- Arranque en frío con datos locales < 1,5 s; scroll a 60 fps.
- Cero pérdidas de datos en pruebas de sync offline.

## 8. Principios de diseño
- Mezcla Mail de iOS (limpieza, gestos, jerarquía tipográfica) y Gmail (avatares, hilos, etiquetas de color).
- Material 3, colores dinámicos, modo oscuro.
- Acciones frecuentes a un gesto o un toque; sin menús intermedios innecesarios.

## 9. Riesgos
- Registro de clientes OAuth en Google (modo pruebas limita usuarios) y Microsoft.
- IMAP heterogéneo: Gmail (X-GM-*) vs Dovecot vs Exchange Online.
- Elección de librería IMAP en Android.
- Render seguro de HTML.
- Hilos en servidores sin soporte THREAD.

## 10. Hitos
Ver `PLAN.md`. Después del MVP: push IDLE y snooze (v1.1), PGP/S-MIME (v2), aliases e identidades, tablet.

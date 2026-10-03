# UltimateMail — instrucciones para Claude Code

Cliente de correo Android moderno (lo mejor de Mail de iOS + la UI de Gmail), IMAP/SMTP, offline-first, software libre (GPLv3), destino final: F-Droid.

Lee siempre `PRD.md` (por qué y para quién), `SPEC.md` (qué construir) y `PLAN.md` (en qué orden) antes de empezar. Si algo de este archivo contradice a la spec, para y pregunta.

## Identidad del proyecto
- Nombre: **UltimateMail**
- `applicationId`: `com.qtekfun.ultimatemail`
- Repositorio: https://github.com/qtekfun/UltimateMail
- Licencia: **GPL-3.0-or-later** (cabecera SPDX en cada archivo fuente)
- Idiomas de la UI: inglés (por defecto) y español. **Ninguna cadena visible va hardcodeada**: todo en `strings.xml` (`values/` y `values-es/`).

## Stack (no cambiar sin preguntar)
- Kotlin, Jetpack Compose, Material 3 (colores dinámicos + modo oscuro)
- `minSdk` 26, `targetSdk` el último estable. Subir `minSdk` solo si algo lo bloquea, y dejarlo anotado en `SPEC.md`.
- Arquitectura: MVVM + capas `ui` / `domain` / `data`, flujo de datos unidireccional (StateFlow)
- Inyección: Hilt
- Persistencia: Room (fuente de verdad única) con FTS para búsqueda local
- Segundo plano: WorkManager
- Correo: **librería IMAP/SMTP libre existente**, elegida en el prototipo T03 (candidatas: módulos de K-9 Mail/Thunderbird Android, Jakarta/Angus Mail). Hasta decidirlo, nada de código de red de correo fuera de ese prototipo.
- OAuth2: **AppAuth-Android** (libre, sin Play Services)
- Gradle con Kotlin DSL y catálogo de versiones (`gradle/libs.versions.toml`)

## Reglas de software libre (F-Droid) — innegociables
- **Prohibido**: Firebase, Google Play Services, Crashlytics, analíticas, SDKs propietarios, cualquier dependencia no libre.
- **Prohibido** telemetría de ningún tipo.
- Antes de añadir una dependencia: comprueba su licencia (compatible con GPLv3) y pregunta al usuario.
- Metadatos de publicación en formato fastlane: `fastlane/metadata/android/{en-US,es-ES}/`.
- Builds reproducibles: sin timestamps ni valores no deterministas en el build.

## Comandos
- Build debug: `./gradlew assembleDebug`
- Tests unitarios: `./gradlew testDebugUnitTest`
- Tests instrumentados: `./gradlew connectedDebugAndroidTest`
- Lint y estilo: `./gradlew detekt ktlintCheck lintDebug`
- Cobertura: `./gradlew koverVerify koverHtmlReport`
- Todo lo anterior (lo que corre la CI): `./gradlew check`

## Calidad y tests
- Cada tarea termina con `./gradlew check` en verde. No marques una tarea como hecha si falla.
- Stack de tests: JUnit5 + MockK, Turbine (flows), servidor IMAP/SMTP falso o embebido para tests de protocolo, Room en memoria, tests de UI con Compose solo en flujos clave.
- **Cobertura (Kover):**
  - Umbral global mínimo **85%** sobre `domain`, `data` y `sync`.
  - **100% obligatorio** en el resolutor de conflictos/estado de sync IMAP y en la cola de operaciones pendientes.
  - Excluido de la medición: código generado (Hilt, Room), `@Preview`, UI Compose pura.
  - Objetivo aspiracional: 100% global, pero **nunca escribas tests vacíos o tautológicos** para subir el número. Un test debe poder fallar por una razón real.
- Warnings de Kotlin y Lint tratados como errores.

## Flujo de trabajo
- Trabaja **una tarea de `PLAN.md` cada vez**, en una rama `feat/<tarea>`.
- Empieza en modo plan: propón el enfoque y espera confirmación antes de tocar código.
- Commits siguiendo **Conventional Commits** (`feat:`, `fix:`, `test:`, `chore:`, `docs:`...), pequeños y atómicos.
- No hagas `git push --force`, no reescribas historia compartida, no toques `main` directamente.
- Al terminar cada tarea: resume en 2-3 líneas qué se hizo y qué queda; marca la tarea en `PLAN.md`.
- Si la spec es ambigua o falta información: **pregunta**, no inventes.

## Convenciones de código
- Un archivo por clase pública relevante; paquetes por feature dentro de cada capa.
- Sin lógica de negocio en composables ni en ViewModels pesados: va en `domain`.
- Inmutabilidad por defecto (`val`, `data class`, colecciones inmutables).
- Errores de red/IO modelados con tipos sellados (`Result`/sealed), no con excepciones sueltas hacia la UI.
- Todo el acceso a Room y red fuera del hilo principal (Dispatchers inyectables para poder testear).
- Secretos (contraseñas de aplicación, tokens OAuth) cifrados con Android Keystore; nunca en logs ni en texto plano. **Nunca registrar asunto, cuerpo ni direcciones de correo en logs.**
- El HTML de los correos se renderiza aislado y con scripts desactivados; contenido remoto bloqueado por defecto.
- Accesibilidad: `contentDescription`, tamaños táctiles mínimos de 48dp, soporte de fuente grande.
- Todo es **por cuenta**: claves `(accountId, ...)`, y ajustes, firma y política offline también por cuenta.

## Qué NO hacer
- No implementes nada marcado como "Fuera de alcance" en `SPEC.md` (push IDLE, snooze, PGP/S-MIME, aliases, etc.).
- No cambies versiones de dependencias manualmente: lo gestiona Dependabot.
- No desactives ni relajes detekt, ktlint, Lint o Kover para que pase la CI.
- No modifiques ni borres correo en el servidor de forma destructiva sin que la acción venga explícitamente del usuario.

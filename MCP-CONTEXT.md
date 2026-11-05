# Contexto del Proyecto Via2

## Objetivo
- Navegador web optimizado para Android TV basado en WebView con soporte para D-Pad / cursor virtual, bloqueo de anuncios y gestion DNS.
- Deseado: usar YouTube TV (youtube.com/tv con User-Agent de TV) dentro del navegador, sin pausas al perder el foco y con anuncios filtrados.

## Estado actual (hito cerrado 2026-11-05)
- D-Pad passthrough en YouTube TV: implementado y funcionando.
- User-Agent de TV: aplicado correctamente en youtube.com/tv (Tizen 7.0).
- Navegacion desde la home (marcador YouTube TV) y desde el historial: funciona.
- Drawer flotante (menu rapido animado): implementado.
- BACK corto / BACK largo: comportamiento diferenciado por sitio (ver bloque de archivos estables).
- `BUILD SUCCESSFUL` en compileDebugKotlin tras todos los cambios.

## ⚠️ NO TOCAR SIN NECESIDAD — Ya funciona y esta verificado

Los siguientes comportamientos estan implementados y **funcionando en pruebas**. No modificar estos archivos ni estas rutas de codigo sin pedir confirmacion al usuario y sin recompilar/probar de nuevo.

### TVWebView.kt
- `TV_USER_AGENT` = Tizen 7.0 / Chrome 94. YouTube TV lo acepta y sirve la interfaz de TV.
- `applySiteProfile(url)` activa/desactiva el perfil TV segun `isTvUrl(url)`.
- `ensureTvProfileFor(url)` y `isTvUrlUrl(url)` son los metodos que usa `TVWebViewClient`.
- `loadUrl(url)` aplica el perfil ANTES de `super.loadUrl` para evitar recargas.
- Inyeccion de `keep_alive.js` via `addDocumentStartJavaScript` en origenes de YouTube.
- `@Suppress("DEPRECATION")` en `configureSecuritySettings()` silencia el warning de `saveFormData`.

### TVWebViewClient.kt
- `shouldOverrideUrlLoading`: si el destino es de TV, aplica perfil, consume la navegacion y relanza `loadUrl` diferido (`view.post`). Esto evita que la home se recargue al hacer clic en el marcador.
- `onPageStarted` / `doUpdateVisitedHistory` solo consultan el UA, no lo cambian (para no abortar cargas).
- Filtro de ads y DNS en `shouldInterceptRequest`.

### DPadController.kt
- Flag `passthroughToPage` + `setPassthroughToPage(enabled)`: cuando esta activo, las flechas y ENTER/CENTER no se consumen y llegan al WebView (necesario para YouTube TV).
- El resto de modos (CURSOR, DESPLAZAMIENTO, FOCO) sin cambios.

### MainActivity.kt
- `handleBackKey(event)` se procesa al INICIO de `dispatchKeyEvent`, antes de cualquier estado de UI:
  - DOWN (repeatCount=0): cierra fullscreen / tabs drawer / quick menu / setup / edicion URL, o arranca el timer de long-press de 1s.
  - DOWN (repeatCount>0): ignorado (evita que el auto-repeat cierre el drawer).
  - UP corto: en YouTube TV reenvia DOWN+UP sinteticos al WebView (retroceso real de YT TV); fuera de YouTube TV hace toggle de la toolbar.
  - UP tras long-press: no hace nada (el drawer ya se abrio/cerro).
- `MENU` / `GUIDE` / `PROG_RED` abren/cierran el drawer.
- `INFO` hace toggle de la toolbar (bloqueado en YouTube TV).
- `showToolbar()` / `toggleToolbar()` tienen guard `if (isYoutubeTvSite) return` para no mostrar la barra blanca en YouTube TV.
- `showQuickMenu()` / `hideQuickMenu()` animan el panel con translationX + alpha + scale (efecto tarjeta flotante sobre el WebView, sin afectar dimensiones de la pagina).

### home_page.html
- Listener explicito `click` en `.tarjeta-youtube` y en cada tarjeta generada por `crearTarjeta()`. Fuerza `window.location.href = url`.
- Listener de `Enter` sobre tarjetas enfocadas.
- Listener `click` en el boton restaurar pestañas.

### activity_main.xml / quick_menu.xml
- `CursorOverlayView` tiene `clickable=false` y `focusable=false` para no interceptar toques del emulador.
- `quickMenuPanel` es el contenedor animado del drawer (id referenciado en `showQuickMenu`/`hideQuickMenu`).

## Hallazgos historicos (sondeo inicial 2026-11-05)
1. Tabs = lista de URLs sobre un unico WebView.
2. `onPause` no llama a `CookieManager.flush()`. Incognito borra cookies de YouTube.
3. UA global: solo cambia con modo escritorio. (Resuelto: ahora hay perfil por sitio en TVWebView.)
4. Scripts se inyectan con `evaluateJavascript`. (Resuelto: keep_alive.js usa DOCUMENT_START_SCRIPT.)
5. libs.versions.toml declara leanback y glide sin uso. No tocar.
6. AdBlockerManager descarta cosmeticos/scriptlets; no bloquea ads dentro del reproductor de YouTube.
7. `VIDEO_RESTORE_JS` usa origin + pathname: podria restaurar segundo de otro video.
8. Contador de bloqueados sube +1 por pagina cargada (bug conocido, no critico).
9. D-pad: CURSOR/DESPLAZAMIENTO consumen flechas; FOCO las deja pasar pero UP con scrollY=0 abre barra.
10. Atras/Escape: resuelto con `handleBackKey` centralizado.

## Pendientes (no urgentes, requieren confirmacion del usuario)
1. `CookieManager.flush()` en `onPause`.
2. Excluir YouTube de `VIDEO_RESTORE_JS` o incluir query+hash en la clave.
3. Corregir el contador de bloqueados (hallazgo 8).
4. Refactor multi-WebView si se quiere video vivo entre pestañas.

## Archivos modificados en este hito
- `app/src/main/java/com/pekorinz/via2/webview/TVWebView.kt`
- `app/src/main/java/com/pekorinz/via2/webview/TVWebViewClient.kt`
- `app/src/main/java/com/pekorinz/via2/dpad/DPadController.kt`
- `app/src/main/java/com/pekorinz/via2/ui/MainActivity.kt`
- `app/src/main/assets/home_page.html`
- `app/src/main/res/layout/activity_main.xml`
- `app/src/main/res/layout/quick_menu.xml`
- `MCP-CONTEXT.md` (este archivo)

## Comprobaciones
- `gradlew.bat :app:compileDebugKotlin`: BUILD SUCCESSFUL tras cada commit del hito.
- Pruebas manuales en Mac (emulador/dispositivo): navegacion desde home, YouTube TV con UA de TV, drawer flotante, BACK corto/largo diferenciado. Todas confirmadas por el usuario.

## Commits del hito (rama feature/fullscreen-video)
- `bd22fa0` fix: clic YouTube TV + bloqueo barra blanca en TV
- `ab13bce` feat: long-press back para drawer y short-press para navegar
- `aa17e30` fix: restaurar goBackInSetup
- `5e5a488` fix: centralizar BACK/ESCAPE antes de estados de UI
- `3a9f1a0` fix: BACK corto muestra toolbar fuera de YouTube TV, BACK largo abre drawer

## Siguiente paso
- Hito cerrado. Antes de tocar cualquier archivo listado en "NO TOCAR", pedir confirmacion al usuario y volver a compilar/probar.
- Pendientes opcionales: flush de cookies, exclusion de YouTube en VIDEO_RESTORE_JS, contador de bloqueados.

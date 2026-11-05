# Contexto del Proyecto Via2

## Objetivo
- Navegador web optimizado para Android TV basado en WebView con soporte para D-Pad / cursor virtual, bloqueo de anuncios y gestion DNS.
- Deseado: usar YouTube TV (youtube.com/tv con User-Agent de TV) dentro del navegador, sin pausas al perder el foco y con anuncios filtrados.

## Estado actual (hito cerrado 2026-11-05)
- D-Pad passthrough en YouTube TV: implementado y funcionando.
- User-Agent de TV: aplicado correctamente en youtube.com/tv (Tizen 7.0).
- Navegacion desde home (marcador YouTube TV) y desde historial: funciona.
- Drawer lateral (menu rapido) fuera de YT TV: funciona.
- Bottom sheet horizontal de 4 opciones SOLO para YT TV: implementado con animacion y foco atrapado.
- BACK corto / BACK largo: comportamiento diferenciado por sitio.
- `youtube_tv_scrim.js` inyectado al inicio del documento en origenes de YouTube: aplica estilos y velo cuando el reproductor esta activo.
- `sendBackToPage()` reforzado: dispara Escape en 5 targets para asegurar que YT TV lo reciba.
- `BUILD SUCCESSFUL` en compileDebugKotlin tras todos los cambios.

## ⚠️ NO TOCAR SIN NECESIDAD — Ya funciona y esta verificado

Los siguientes comportamientos estan implementados y **funcionando en pruebas** (confirmado por el usuario). No modificar estos archivos ni estas rutas de codigo sin pedir confirmacion al usuario y sin recompilar/probar de nuevo.

### TVWebView.kt
- `TV_USER_AGENT` = Tizen 7.0 / Chrome 94. YT TV lo acepta y sirve la interfaz de TV.
- `applySiteProfile(url)`, `ensureTvProfileFor(url)`, `isTvUrlUrl(url)` funcionan.
- `loadUrl(url)` aplica el perfil ANTES de `super.loadUrl` para evitar recargas.
- `installDocumentStartScripts()` inyecta keep_alive.js Y youtube_tv_scrim.js al inicio del documento.
  - `SCRIM_ORIGINS` = www.youtube.com, youtube.com, m.youtube.com
  - El script se ejecuta tambien en subpáginas /tv#/watch.
- `sendBackToPage()` dispara `KeyboardEvent('Escape', keyCode=27)` en 5 targets: activeElement, body, document, documentElement, window. Confirmado que hace retroceder YT TV.
- `@Suppress("DEPRECATION")` silencia el warning de saveFormData.

### youtube_tv_scrim.js (assets)
- Inyectado al inicio del documento en origenes de YouTube.
- Solo se activa cuando el reproductor esta presente (`html[data-tv-player]`).
- Limpia cajas negras de textos, tiempos y separadores.
- Velo (scrim) con degradado que aparece al abrir el panel de sugeridos.
- setInterval cada 250ms que reevalua el estado.
- Reemplaza al antiguo YOUTUBE_TV_SHADOW_JS (ya eliminado).

### TVWebViewClient.kt
- `shouldOverrideUrlLoading`: si el destino es de TV, aplica perfil, consume navegacion y relanza loadUrl diferido.
- `onPageStarted` / `doUpdateVisitedHistory`: solo consultan el UA (no lo cambian) para no abortar cargas.
- Filtro de ads y DNS en shouldInterceptRequest.

### DPadController.kt
- `passthroughToPage` + `setPassthroughToPage(enabled)`: cuando activo, flechas y ENTER/CENTER no se consumen y llegan al WebView (necesario para YT TV).

### MainActivity.kt
- `handleBackKey(event)`: procesado al INICIO de dispatchKeyEvent.
  - DOWN: cierra fullscreen / tabs / drawer / setup / URL, o arranca el timer de long-press de 1s.
  - DOWN (repeatCount>0): ignorado.
  - UP corto: en YT TV -> `webView.sendBackToPage()`. Fuera de YT TV -> toggle toolbar.
  - UP tras long-press: no hace nada.
- `MENU` / `GUIDE` / `PROG_RED`: abren/cierran el drawer (lateral o bottom sheet segun sitio).
- `INFO`: toggle toolbar (bloqueado en YT TV).
- `showToolbar()` / `toggleToolbar()` tienen guard `if (isYoutubeTvSite) return`.
- `showQuickMenu()`: si `isYoutubeTvSite` desvia a `showBottomSheetForTv()`; si no, al drawer lateral.
- `hideQuickMenu()`: si `isYoutubeTvSite` desvia a `hideBottomSheetForTv()`; si no, al drawer lateral.
- `showBottomSheetForTv()` / `hideBottomSheetForTv()`: animacion translationY + alpha + scale. Bloquea foco del resto de la app y enfoca el primer item.
- `dispatchKeyEventInBottomSheet(event)`: atrapa Izq/Der entre las 4 opciones (Navegador, Bloquear anuncios, Cerrar app, Ajustes). Enter ejecuta. Back cierra (via handleBackKey).
- `hookQuickMenuBottom()`: Navegador -> home + `setHomePageNavigation(true)` (fix del cursor). AdBlock -> toggle + cambia icono (`ic_via_shield` / `ic_via_shield_off`) + reload. Cerrar app -> finish(). Ajustes -> showSettingsScreen().

### home_page.html
- Listeners de `click` en `.tarjeta-youtube` y cada tarjeta generada. Fuerzan `window.location.href`.
- Listener de Enter sobre tarjetas enfocadas.
- Listener click en el boton restaurar pestañas.

### activity_main.xml / quick_menu.xml / quick_menu_bottom.xml
- `quickMenu` (lateral) y `quickMenuBottom` (inferior) coexisten en activity_main.xml. Se muestra uno u otro segun isYoutubeTvSite.
- `quickMenuBottom` con panel de esquinas redondeadas arriba (bg_quick_menu_bottom_panel.xml).
- Cada item del bottom sheet tiene bg_quick_bottom_item.xml (selector con estado focused: fondo claro + borde amarillo).
- `qmbAdBlockIcon` con id propio para cambiar el drawable dinamicamente.
- `CursorOverlayView` con clickable=false y focusable=false.

## Hallazgos historicos (sondeo inicial 2026-11-05)
1. Tabs = lista de URLs sobre un unico WebView.
2. onPause no llama a CookieManager.flush().
3. UA global cambiado por modo escritorio. (Resuelto: perfil por sitio en TVWebView.)
4. Scripts con evaluateJavascript. (Resuelto: keep_alive y scrim usan DOCUMENT_START_SCRIPT.)
5. libs.versions.toml declara leanback y glide sin uso. No tocar.
6. AdBlockerManager descarta cosmeticos/scriptlets; no bloquea ads dentro del reproductor de YouTube.
7. VIDEO_RESTORE_JS usa origin + pathname: podria restaurar segundo de otro video.
8. Contador de bloqueados sube +1 por pagina cargada (bug conocido, no critico).
9. D-pad: CURSOR/DESPLAZAMIENTO consumen flechas; FOCO las deja pasar.
10. Atras/Escape: resuelto con handleBackKey centralizado.

## Pendientes (no urgentes, requieren confirmacion del usuario)
1. CookieManager.flush() en onPause.
2. Excluir YouTube de VIDEO_RESTORE_JS o incluir query+hash en la clave.
3. Corregir el contador de bloqueados (hallazgo 8).
4. Refactor multi-WebView si se quiere video vivo entre pestañas.
5. Valorar si sendBackToPage() con 5 targets produce doble retroceso en alguna subpantalla especifica de YT TV. Si pasa, reducir a activeElement + body.

## Archivos modificados en este hito
- app/src/main/java/com/pekorinz/via2/webview/TVWebView.kt
- app/src/main/java/com/pekorinz/via2/webview/TVWebViewClient.kt
- app/src/main/java/com/pekorinz/via2/dpad/DPadController.kt
- app/src/main/java/com/pekorinz/via2/ui/MainActivity.kt
- app/src/main/assets/home_page.html
- app/src/main/assets/youtube_tv_scrim.js (nuevo)
- app/src/main/res/layout/activity_main.xml
- app/src/main/res/layout/quick_menu.xml
- app/src/main/res/layout/quick_menu_bottom.xml (nuevo)
- app/src/main/res/drawable/bg_quick_bottom_item.xml (nuevo)
- app/src/main/res/drawable/bg_quick_menu_bottom_panel.xml (nuevo)
- app/src/main/res/drawable-xxxhdpi/ic_via_shield_off.png (nuevo)
- MCP-CONTEXT.md (este archivo)

## Comprobaciones
- gradlew.bat :app:compileDebugKotlin: BUILD SUCCESSFUL tras cada commit.
- Pruebas manuales en Mac: navegacion, YT TV con UA, bottom sheet, BACK corto/largo, scrim, icono dinamico AdBlock, fix del cursor. Todas confirmadas por el usuario.

## Commits del hito (rama feature/fullscreen-video)
- bd22fa0 fix: clic YouTube TV + bloqueo barra blanca en TV
- ab13bce feat: long-press back para drawer y short-press para navegar
- aa17e30 fix: restaurar goBackInSetup
- 5e5a488 fix: centralizar BACK/ESCAPE antes de estados de UI
- 3a9f1a0 fix: BACK corto muestra toolbar fuera de YouTube TV, BACK largo abre drawer
- 8660fdc docs: MCP-CONTEXT con bloque NO TOCAR
- 3818514 fix: Escape JS a YouTube TV en BACK corto
- a841cf2 / cac83d1 feat: rediseno bottom sheet YouTube TV
- 2c14702 feat: inyectar youtube_tv_scrim.js, retirar YOUTUBE_TV_SHADOW_JS
- af16c52 feat: actualizar scrim (solo con reproductor) y reforzar sendBackToPage

## Siguiente paso
- Hito cerrado. Antes de tocar cualquier archivo listado en "NO TOCAR", pedir confirmacion al usuario y volver a compilar/probar.
- Pendientes opcionales documentados arriba.

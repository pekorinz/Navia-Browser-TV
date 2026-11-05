# Contexto del Proyecto Via2

## Objetivo
- Navegador web optimizado para Android TV basado en WebView con soporte para D-Pad / cursor virtual, bloqueo de anuncios y gestión DNS.
- Deseado (conversación del 05/11/2026): usar YouTube TV (youtube.com/tv con User-Agent de TV) dentro del navegador, sin pausas al perder el foco y con anuncios filtrados.

## Estado actual
- Hito anterior completado: manejo del botón Atrás/Salir y persistencia de caché/progreso de video (`BUILD SUCCESSFUL`).
- Sondeo de SOLO LECTURA completo (05/11/2026): TVWebViewClient, AdBlockerManager, TVWebView, MainActivity, DPadController, build.gradle.kts y libs.versions.toml. No se modificó código.
- Pendiente: que el usuario confirme qué entregables implementar.

## Decisiones e implementaciones recientes
1. Botón Atrás/Salir: solo expande o repliega la barra superior; el diálogo de salida se invoca únicamente desde `qmPower` (menú sándwich).
2. Persistencia de estado y autorestauración de video: `TVWebView.goBackPreservingState()` (LOAD_CACHE_ELSE_NETWORK), ajustes de dom storage y bloqueo de popups, y script `VIDEO_RESTORE_JS` inyectado en `onPageFinished`.

## Hallazgos del sondeo (05/11/2026)
1. Las pestañas son una lista de URLs (`tabsList`) sobre un único WebView: cambiar de pestaña hace `loadUrl` y recarga la página. Mantener video vivo entre pestañas exigiría un refactor (varios WebView).
2. `onPause` no llama a `CookieManager.flush()`. Incógnito borra TODAS las cookies (incluida la sesión de YouTube); Ajustes avanzados también tiene borrar cookies.
3. El User-Agent es global (solo cambia con modo escritorio, `R.string.ua_desktop`). No hay regla por sitio.
4. Los scripts se inyectan con `evaluateJavascript` en `onPageFinished`. `androidx.webkit` NO está en las dependencias (build.gradle.kts solo tiene core-ktx, appcompat y lifecycle-runtime-ktx), por lo que `addDocumentStartJavaScript` no está disponible hasta añadirla. Hay que elegir una versión compatible con compileSdk 34 (no verificada) y editar libs.versions.toml y build.gradle.kts.
5. libs.versions.toml declara `leanback` y `glide` que build.gradle.kts no usa (restos sin usar; no tocar sin pedirlo). agp 9.3.3, minSdk 26, targetSdk 34.
6. `AdBlockerManager`: reglas de red por dominio y por ruta, excepciones y lista blanca. Descarta reglas cosméticas (##) y scriptlets, por lo que no frena anuncios dentro del reproductor de YouTube. `shouldBlock` recorre `pathRules` completa bajo `synchronized` en cada petición (posible costo en TV modesta, sin medir).
7. `VIDEO_RESTORE_JS` usa la clave origin + pathname: en YouTube es igual para todos los videos y podría restaurar el segundo de otro video (deducido del código, no probado).
8. Error: `onPageStarted` llama a `onBlockedCountChanged(0)` y el callback de MainActivity hace `addBlockedCount(1L)` sin mirar el valor, así que el total bloqueado sube 1 por cada página cargada.
9. D-pad: en modos CURSOR (predeterminado) y DESPLAZAMIENTO las flechas se consumen y no llegan a la página. En modo FOCO las flechas pasan, pero Arriba con scrollY == 0 abre la barra (en Leanback scrollY suele ser 0, así que secuestraría esa tecla). `setHomePageNavigation` ya delega teclas al WebView en la página de inicio: es el patrón a reutilizar.
10. Atrás/Escape se consume en `MainActivity.dispatchKeyEvent` para mostrar u ocultar la barra; la página nunca lo recibe. Para YouTube TV haría falta reenviarlo a la página (código de tecla que usa Leanback: sin verificar).

## Archivos modificados
- `MCP-CONTEXT.md` (solo este registro; ningún archivo de código cambiado en este sondeo)

## Comprobaciones
- gradlew.bat compileDebugKotlin: BUILD SUCCESSFUL (hito anterior). El sondeo no compiló ni ejecutó nada.

## Pendientes propuestos (esperan confirmación del usuario)
1. Añadir androidx.webkit (libs.versions.toml + build.gradle.kts) y verificar que compila.
2. Inyección al inicio del documento con keep_alive.js (assets) solo para YouTube.
3. User-Agent de TV por sitio (override de loadUrl en TVWebView + shouldOverrideUrlLoading), constante fácil de cambiar.
4. Modo de paso directo del D-pad y Atrás para sitios de TV (youtube.com/tv).
5. Excluir YouTube de VIDEO_RESTORE_JS o incluir query y hash en su clave.
6. CookieManager.flush() en onPause.
7. Corregir el contador de bloqueados (hallazgo 8).

## Siguiente paso
- Esperar la confirmación del usuario sobre los pendientes. Después implementar en orden 1 a 4 y compilar con un único comando aprobado.

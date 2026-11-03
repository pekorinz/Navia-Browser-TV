package com.pekorinz.via2.ui

import android.app.DownloadManager
import android.content.Context
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.pekorinz.via2.R
import com.pekorinz.via2.adblock.AdBlockerManager
import com.pekorinz.via2.adblock.FilterSubscriptions
import com.pekorinz.via2.data.PreferenceManager
import com.pekorinz.via2.dns.DnsManager
import com.pekorinz.via2.dns.DnsProvider
import com.pekorinz.via2.dpad.CursorOverlayView
import com.pekorinz.via2.dpad.DPadController
import com.pekorinz.via2.dpad.DPadMode
import com.pekorinz.via2.webview.TVWebChromeClient
import com.pekorinz.via2.webview.TVWebView
import com.pekorinz.via2.webview.TVWebViewClient

class MainActivity : AppCompatActivity() {

    private lateinit var preferenceManager: PreferenceManager
    private lateinit var adBlockerManager: AdBlockerManager
    private lateinit var dpadController: DPadController

    private lateinit var toolbarContainer: ViewGroup
    private lateinit var webView: TVWebView
    private lateinit var cursorOverlay: CursorOverlayView
    private lateinit var etUrl: EditText
    private lateinit var btnAdBlock: ImageButton
    private lateinit var pbLoading: ProgressBar
    private lateinit var settingsScreen: View
    private lateinit var ivSecurity: android.widget.ImageView
    private lateinit var tvBlockedBadge: TextView
    private lateinit var quickMenu: View

    private val localTestUrl = "file:///android_asset/test_page.html"
    private val tabsList = mutableListOf<String>()
    private var currentTabIndex = 0

    /** Marcadores guardados como "url\u001Ftitulo" en una sola entrada de prefs. */
    private val bookmarkSep = "\u001F"

    private var quickMenuHooked = false

    /** true mientras el menu rapido esta abierto (bloquea el resto de la UI). */
    private var isQuickMenuOpen = false

    /** Pantalla de ajuste visible ahora mismo (null = ninguna). */
    private var currentSetupScreen: View? = null

    /** Vista que tenia el foco antes de abrir menus o ajustes, para restaurarlo al cerrar. */
    private var lastFocusedView: View? = null

    private var isDesktopMode = false
    private var isNightMode = false
    private var currentPageTitle: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        preferenceManager = PreferenceManager(this)
        adBlockerManager = AdBlockerManager(this, preferenceManager)

        // Aplicar DNS DoH seleccionado antes de instanciar WebView
        DnsManager.applyFromPreferences(this, preferenceManager)

        initViews()
        setupWebView()
        setupDPadController()
        setupToolbarListeners()
        setupSettingsActions()
        setupBackPressedHandler()

        // Inicializar gestor de pestañas ligero
        tabsList.add(localTestUrl)

        // Ocultar la barra de herramientas por defecto para navegación inmersiva a pantalla completa
        toolbarContainer.visibility = View.GONE

        // Cargar página de prueba local por defecto para Fase B / C
        webView.loadUrl(localTestUrl)
        webView.applyDnsProxyIfNeeded()
    }

    private fun initViews() {
        toolbarContainer = findViewById(R.id.toolbarContainer)
        webView = findViewById(R.id.webView)
        cursorOverlay = findViewById(R.id.cursorOverlay)
        settingsScreen = findViewById(R.id.settingsScreen)
        etUrl = findViewById(R.id.etUrl)
        btnAdBlock = findViewById(R.id.btnAdBlock)
        pbLoading = findViewById(R.id.pbLoading)
        ivSecurity = findViewById(R.id.ivSecurity)
        tvBlockedBadge = findViewById(R.id.tvBlockedBadge)
        quickMenu = findViewById(R.id.quickMenu)
        updateAppThemeColors()
    }

    private fun setupWebView() {
        webView.webViewClient = TVWebViewClient(
            adBlockerManager = adBlockerManager,
            dohResolver = DnsManager.currentResolver,
            onPageUrlChanged = { url ->
                if (!etUrl.hasFocus()) {
                    etUrl.setText(url)
                }
                if (currentTabIndex in 0 until tabsList.size) {
                    tabsList[currentTabIndex] = url
                }
                updateSecurityIcon(url)
            },
            onLoadingStateChanged = { isLoading ->
                pbLoading.visibility = if (isLoading) View.VISIBLE else View.GONE
            },
            onHttpWarning = { url, proceed ->
                showHttpWarningDialog(url, proceed)
            },
            onBlockedCountChanged = { count ->
                runOnUiThread {
                    preferenceManager.addBlockedCount(1L)
                    updateBlockedBadge(count)
                }
            }
        )

        webView.webChromeClient = TVWebChromeClient(
            onProgressChanged = { progress ->
                pbLoading.progress = progress
            },
            onTitleReceived = { title ->
                // Se guarda el titulo para el historial y los marcadores
                currentPageTitle = title
                val url = webView.url
                if (!url.isNullOrBlank() && !preferenceManager.isIncognito()) {
                    preferenceManager.addHistory(url, title)
                }
            }
        )

        // Integración con el gestor de descargas del sistema Android
        webView.setDownloadHandler { url, mimeType ->
            showDownloadConfirmationDialog(url, mimeType)
        }

        // Al terminar de cargar, se reaplica el modo nocturno si estaba activo
        (webView.webViewClient as? TVWebViewClient)?.onPageFinishedCallback = { _, _ ->
            if (isNightMode) applyNightMode()
        }
    }

    private fun setupDPadController() {
        dpadController = DPadController(
            webView = webView,
            toolbarContainer = toolbarContainer,
            cursorOverlay = cursorOverlay,
            preferenceManager = preferenceManager,
            onModeChanged = { mode ->
                updateModeUi(mode)
            },
            onRequestShowToolbar = {
                showToolbar()
            },
            onRequestHideToolbar = {
                hideToolbar()
            }
        )
        updateModeUi(dpadController.currentMode)
    }

    /** Candado de la barra de URL: cerrado en HTTPS, abierto en HTTP. */
    private fun updateSecurityIcon(url: String) {
        val secure = url.startsWith("https://")
        ivSecurity.setImageResource(
            if (secure) R.drawable.ic_via_lock else R.drawable.ic_via_close
        )
        ivSecurity.contentDescription = getString(
            if (secure) R.string.btn_secure else R.string.btn_insecure
        )
    }

    /** Contador de bloqueos de la pagina actual. */
    private fun updateBlockedBadge(count: Int) {
        if (count <= 0) {
            tvBlockedBadge.visibility = View.GONE
        } else {
            tvBlockedBadge.visibility = View.VISIBLE
            tvBlockedBadge.text = count.toString()
        }
    }

    private fun updateModeUi(mode: DPadMode) {
        // El chip de modo esta oculto en la barra; solo se avisa con un toast.
        val message = getString(R.string.mode_changed_format, getString(mode.labelResId))
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    fun showToolbar() {
        if (toolbarContainer.visibility != View.VISIBLE) {
            toolbarContainer.visibility = View.VISIBLE
            toolbarContainer.getChildAt(0)?.requestFocus()
        }
    }

    fun hideToolbar() {
        if (toolbarContainer.visibility != View.GONE) {
            toolbarContainer.visibility = View.GONE
            hideKeyboard()
            webView.requestFocus()
        }
    }

    fun toggleToolbar() {
        if (toolbarContainer.visibility == View.VISIBLE) {
            hideToolbar()
        } else {
            showToolbar()
        }
    }

    private fun setupToolbarListeners() {
        findViewById<ImageButton>(R.id.btnBack).setOnClickListener {
            if (webView.canGoBack()) {
                webView.goBack()
            } else {
                showExitConfirmationDialog()
            }
        }

        findViewById<ImageButton>(R.id.btnForward).setOnClickListener {
            if (webView.canGoForward()) {
                webView.goForward()
            }
        }

        findViewById<ImageButton>(R.id.btnHome).setOnClickListener {
            webView.loadUrl(localTestUrl)
        }

        findViewById<ImageButton>(R.id.btnRefresh).setOnClickListener {
            webView.reload()
        }

        btnAdBlock.setOnClickListener {
            val enabled = !preferenceManager.isAdBlockEnabled()
            preferenceManager.setAdBlockEnabled(enabled)
            val msgRes = if (enabled) R.string.adblock_active else R.string.adblock_disabled
            Toast.makeText(this, msgRes, Toast.LENGTH_SHORT).show()
            webView.reload()
        }

        findViewById<ImageButton>(R.id.btnTabs).setOnClickListener {
            showTabsDialog()
        }

        // Sanduche: abre el menu rapido blanco estilo Via
        findViewById<ImageButton>(R.id.btnSettings).setOnClickListener {
            showQuickMenu()
        }

        etUrl.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_DONE) {
                loadEnteredUrl()
                true
            } else {
                false
            }
        }
    }

    private fun loadEnteredUrl() {
        var input = etUrl.text.toString().trim()
        if (input.isNotEmpty()) {
            if (!input.startsWith("http://") && !input.startsWith("https://") && !input.startsWith("file://")) {
                input = if (input.contains(".") && !input.contains(" ")) {
                    "https://$input"
                } else {
                    "https://www.google.com/search?q=${UriEncode(input)}"
                }
            }
            webView.loadUrl(input)
            webView.applyDnsProxyIfNeeded()
            hideKeyboard()
            webView.requestFocus()
        }
    }

    private fun UriEncode(input: String): String {
        return java.net.URLEncoder.encode(input, "UTF-8")
    }

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(etUrl.windowToken, 0)
    }

    private fun setupBackPressedHandler() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (isQuickMenuOpen) {
                    hideQuickMenu()
                } else if (isSetupVisible()) {
                    goBackInSetup()
                } else if (toolbarContainer.visibility != View.VISIBLE) {
                    showToolbar()
                }
            }
        })
    }

    /**
     * ATRAS dentro de ajustes: sube un nivel, no cierra la pantalla entera.
     *   suscripciones -> bloqueo de anuncios -> raiz -> navegador
     */
    private fun goBackInSetup() {
        val subs = findViewById<View>(R.id.subscriptionsScreen)
        val adb = findViewById<View>(R.id.adBlockScreen)
        when {
            subs.visibility == View.VISIBLE ->
                showSetup(adb, R.id.rowAdBlockSubscriptions)
            adb.visibility == View.VISIBLE ->
                showSetup(null, R.id.rowMenuPrivacy)
            else -> showBrowserScreen()
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // MENU ABIERTO: el foco pertenece al menu y a nada mas.
        if (isQuickMenuOpen) {
            return dispatchKeyEventInQuickMenu(event)
        }

        if (isSetupVisible()) {
            return dispatchKeyEventInSetup(event)
        }

        if (toolbarContainer.visibility == View.VISIBLE &&
            event.action == KeyEvent.ACTION_DOWN &&
            event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN
        ) {
            hideToolbar()
            return true
        }

        if (toolbarContainer.visibility == View.VISIBLE && toolbarContainer.hasFocus()) {
            return super.dispatchKeyEvent(event)
        }

        if (event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_PROG_RED, KeyEvent.KEYCODE_GUIDE, KeyEvent.KEYCODE_MENU -> {
                    toggleToolbar()
                    return true
                }
            }
        }

        if (dpadController.handleKeyEvent(event)) {
            return true
        }

        return super.dispatchKeyEvent(event)
    }

    /**
     * Manejo de teclas con una pantalla de ajustes abierta.
     *
     * ANTES se hacia "return super.dispatchKeyEvent(event)" sin mas, y eso era
     * el fallo: con la pagina enfocable aun por detras, al pulsar ARRIBA en la
     * primera fila el sistema buscaba un vecino arriba, no lo encontraba en la
     * pantalla y se lo llevaba al WebView. Se veia como que el puntero "se iba
     * a la pestana del navegador".
     *
     * Ahora:
     *   - ARRIBA/ABAJO en los bordes NO hace nada (no hay ninguna fila).
     *   - Si el foco se perdio de la pantalla, se recupera en la primera fila.
     *   - ATRAS sube un nivel de ajustes.
     *   - OK/ENTER se pasan al elemento enfocado.
     */
    private fun dispatchKeyEventInSetup(event: KeyEvent): Boolean {
        val screen = currentSetupScreen ?: settingsScreen
        val isDown = event.action == KeyEvent.ACTION_DOWN

        if (event.keyCode == KeyEvent.KEYCODE_BACK || event.keyCode == KeyEvent.KEYCODE_ESCAPE) {
            if (isDown) goBackInSetup()
            return true
        }

        // OK / ENTER: los procesa el propio boton enfocado
        if (event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
            event.keyCode == KeyEvent.KEYCODE_ENTER ||
            event.keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER
        ) {
            if (currentFocus == null || !isDescendantOf(currentFocus, screen)) {
                focusFirstInScreen(screen)
                return true
            }
            return super.dispatchKeyEvent(event)
        }

        if (!isDown) {
            // Las flechas ya se atendieron en DOWN; su UP se consume sin repetir
            return when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_UP,
                KeyEvent.KEYCODE_DPAD_DOWN,
                KeyEvent.KEYCODE_DPAD_LEFT,
                KeyEvent.KEYCODE_DPAD_RIGHT -> true
                else -> super.dispatchKeyEvent(event)
            }
        }

        // Si el foco no esta en esta pantalla, se recupera aqui y no se escapa
        if (currentFocus == null || !isDescendantOf(currentFocus, screen)) {
            focusFirstInScreen(screen)
            return true
        }

        // Se deja navegar al sistema DENTRO de la pantalla. Si no encuentra
        // vecino (por ejemplo ARRIBA en la primera fila), el foco se queda
        // donde estaba: no hay forma de llegar a la pagina de atras.
        val antes = currentFocus
        val consumido = super.dispatchKeyEvent(event)
        if (currentFocus == null) {
            // El sistema solto el foco: se devuelve a donde estaba
            antes?.requestFocus() ?: focusFirstInScreen(screen)
        }
        return consumido
    }

    /** true si [v] esta dentro de [root]. */
    private fun isDescendantOf(v: View?, root: View): Boolean {
        var p: android.view.ViewParent? = v?.parent
        while (p != null) {
            if (p === root) return true
            p = p.parent
        }
        return false
    }

    /** Primer control enfocable de la pantalla abierta. */
    private fun focusFirstInScreen(screen: View): Boolean {
        val ids = when (screen.id) {
            R.id.adBlockScreen -> intArrayOf(
                R.id.rowAdBlockMaster, R.id.rowAdBlockBuiltin, R.id.rowAdBlockCustom,
                R.id.rowAdBlockSubscriptions, R.id.rowAdBlockCurrentSite,
                R.id.rowSettingBlockedStats
            )
            R.id.subscriptionsScreen -> intArrayOf(
                R.id.rowSubsInterval, R.id.btnSubsUpdate
            )
            else -> intArrayOf(
                R.id.rowMenuGeneral, R.id.rowMenuAppearance, R.id.rowMenuPrivacy,
                R.id.rowMenuDns, R.id.rowMenuAdvanced, R.id.rowMenuAbout
            )
        }
        for (id in ids) {
            val v = findViewById<View>(id) ?: continue
            if (v.visibility == View.VISIBLE && v.isEnabled && v.requestFocus()) return true
        }
        // Ultimo recurso: el boton de volver de esa pantalla
        return false
    }

    /**
     * Manejo de teclas con el menu rapido abierto.
     *
     * Las flechas y el OK se dejan pasar (los mueve el propio ViewGroup del
     * menu, que esta aislado). Todo lo demas se corta aqui:
     *   - ATRAS / ESCAPE  -> cierra el menu (no sale de la app)
     *   - MENU / guia     -> cierra el menu (no abre la barra)
     *   - resto (el cursor virtual, atajos) -> ignorado
     * Asi el D-pad no puede interactuar con la pagina de atras.
     */
    private fun dispatchKeyEventInQuickMenu(event: KeyEvent): Boolean {
        val isDown = event.action == KeyEvent.ACTION_DOWN

        // OK / ENTER: se pasan al boton enfocado (el clic lo dispara su ACTION_UP).
        // Va ANTES del when para que no los capture la rama de flechas.
        if (event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
            event.keyCode == KeyEvent.KEYCODE_ENTER ||
            event.keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER
        ) {
            if (currentFocus == null) {
                focusFirstQuickMenuItem()
                return true
            }
            return super.dispatchKeyEvent(event)
        }

        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                // Si se perdio el foco, se recupera en el primer icono visible
                if (!isQuickMenuFocused()) {
                    focusFirstQuickMenuItem()
                    return true
                }
                // Segun el icono enfocado, se salta de fila o de columna.
                return moveFocusInGrid(event)
            }
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> {
                if (isDown) hideQuickMenu()
                return true
            }
            KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_GUIDE, KeyEvent.KEYCODE_PROG_RED -> {
                if (isDown) hideQuickMenu()
                return true
            }
            else -> return true
        }
    }

    /**
     * Navegacion del D-pad dentro del menu, hecha a mano.
     *
     * No se delega en el sistema porque la barra de arriba (encendido/cerrar)
     * es un LinearLayout horizontal suelto: delegando, las flechas se van a
     * la pagina de atras. Aqui el foco esta encerrado en dos listas:
     *
     *   barra : [encendido] [cerrar]        (indice -1)
     *   rejilla: 12 iconos en 3 filas x 4   (indices 0..11)
     *
     * ARRIBA / ABAJO saltan entre barra y rejilla; si no, pasan a la fila
     * vecina. IZQUIERDA / DERECHA recorren la misma fila. En los bordes no
     * pasa nada: no hay forma de salir del menu.
     */
    private fun moveFocusInGrid(event: KeyEvent): Boolean {
        // Los 12 iconos en el orden en que aparecen en el layout
        val grid = GRID_IDS.mapNotNull { id -> findViewById<View>(id) }
        val top = TOP_IDS.mapNotNull { id -> findViewById<View>(id) }

        val focused = currentFocus
        val topIndex = top.indexOf(focused)
        val gridIndex = grid.indexOf(focused)

        if (topIndex < 0 && gridIndex < 0) {
            // El foco NO esta en el menu (se perdio al abrir, o quedo en la
            // pagina). Se entra al menu por la posicion que pida la flecha,
            // en vez de devolver true sin mover nada y quedar atascado.
            focusFirstQuickMenuItem()
            return true
        }

        val isDown = event.action == KeyEvent.ACTION_DOWN

        // OK / ENTER se pasan siempre al boton enfocado, tanto DOWN como UP:
        // el clic lo dispara el ACTION_UP del propio boton. Si se consumiera
        // aqui el UP, el icono nunca se activaria.
        if (event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
            event.keyCode == KeyEvent.KEYCODE_ENTER ||
            event.keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER
        ) {
            return super.dispatchKeyEvent(event)
        }

        // Solo las flechas se procesan; su UP se consume sin repetir el salto
        if (!isDown) return true

        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (gridIndex < 0) {
                    // Desde la barra se baja a la rejilla: bajo encendido -> columna 0,
                    // bajo cerrar -> ultima columna de la primera fila
                    val column = if (topIndex == 0) 0 else GRID_COLS - 1
                    grid.getOrNull(column)?.requestFocus()
                } else if (gridIndex + GRID_COLS <= grid.lastIndex) {
                    grid[gridIndex + GRID_COLS].requestFocus()
                }
                // Ultima fila: no baja
            }

            KeyEvent.KEYCODE_DPAD_UP -> {
                if (gridIndex >= 0) {
                    if (gridIndex - GRID_COLS >= 0) {
                        grid[gridIndex - GRID_COLS].requestFocus()
                    } else {
                        // Primera fila: sube a la barra
                        top.getOrNull(if (gridIndex == 0) 0 else 1)?.requestFocus()
                    }
                }
                // Ya en la barra: no sube
            }

            KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (topIndex > 0) {
                    top[topIndex - 1].requestFocus()
                } else if (gridIndex % GRID_COLS > 0) {
                    grid[gridIndex - 1].requestFocus()
                }
            }

            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (topIndex >= 0 && topIndex < top.lastIndex) {
                    top[topIndex + 1].requestFocus()
                } else if (gridIndex % GRID_COLS < GRID_COLS - 1 &&
                    gridIndex + 1 <= grid.lastIndex
                ) {
                    grid[gridIndex + 1].requestFocus()
                }
            }

            else -> return true
        }
        return true
    }

    override fun onDestroy() {
        DnsManager.stop()
        super.onDestroy()
    }

    /**
     * Si la ventana se va (dialogo del sistema, cambia de app, pierde el foco),
     * el menu se cierra y se libera el bloqueo. Si no, quedaria "abierto" con
     * toda la pagina enfocable desactivada y el navegador muerto al volver.
     */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus && isQuickMenuOpen) {
            hideQuickMenu()
        }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // Liberar caché en memoria cuando el televisor se quede sin memoria RAM suficiente (~1.9 GB)
        if (level >= TRIM_MEMORY_RUNNING_LOW) {
            webView.clearCache(false)
        }
    }

    /**
     * Menu secundario del chevron ">".
     * Aqui vive el cambio de modo del D-pad: es una accion de uso ocasional,
     * no merece un boton fijo gastando sitio en la barra.
     */
    // ==================================================================
    //  MENU RAPIDO (estilo Via blanco)
    // ==================================================================

    /**
     * Abre el menu rapido y engancha los clicks (solo una vez).
     *
     * El foco se pide en el post() de la vista, no aqui directamente:
     * acaba de pasar de GONE a VISIBLE, y Android no deja enfocar una vista
     * que todavia no tiene layout (ancho/alto 0). Pidiendolo en el post, el
     * layout ya corrio y requestFocus() si funciona.
     */
    private fun showQuickMenu() {
        lastFocusedView = currentFocus
        if (!quickMenuHooked) {
            hookQuickMenu()
            quickMenuHooked = true
        }
        isQuickMenuOpen = true
        quickMenu.visibility = View.VISIBLE
        trapFocusBehindMenu(true)

        // Primer intento: en el post de la vista.
        // Segundo intento: en el primer onLayout del overlay. Segun la TV, el
        // post llega antes de que el hijo tenga ancho/alto y requestFocus()
        // falla en silencio; el addOnLayoutChangeListener garantiza que para
        // entonces el layout ya corrio.
        quickMenu.post { focusFirstQuickMenuItem() }
        quickMenu.addOnLayoutChangeListener(object : View.OnLayoutChangeListener {
            override fun onLayoutChange(
                v: View, l: Int, t: Int, r: Int, b: Int,
                ol: Int, ot: Int, or: Int, ob: Int
            ) {
                v.removeOnLayoutChangeListener(this)
                if (isQuickMenuOpen && !isQuickMenuFocused()) {
                    focusFirstQuickMenuItem()
                }
            }
        })
    }

    private fun hideQuickMenu() {
        if (!isQuickMenuOpen) return
        isQuickMenuOpen = false
        quickMenu.visibility = View.GONE
        trapFocusBehindMenu(false)
        val target = lastFocusedView ?: findViewById<View>(R.id.btnSettings)
        if (target != null && target.isAttachedToWindow) {
            target.requestFocus()
        } else {
            findViewById<View>(R.id.btnSettings)?.requestFocus()
        }
    }

    /** true si el foco esta en algun boton del menu (no en la pagina de atras). */
    private fun isQuickMenuFocused(): Boolean {
        val f = currentFocus ?: return false
        return TOP_IDS.any { it == f.id } || GRID_IDS.any { it == f.id }
    }

    /**
     * Da el foco al primer icono visible del menu.
     *
     * Prefiere el modo nocturno (arriba a la izquierda de la rejilla); si por
     * lo que sea no acepta el foco, se prueba el resto en orden. Devuelve true
     * si alguno lo consiguio.
     */
    private fun focusFirstQuickMenuItem(): Boolean {
        val orden = GRID_IDS + TOP_IDS
        for (id in orden) {
            val v = findViewById<View>(id) ?: continue
            // Sin comprobar isShown: exige que TODOS los ancestros esten ya
            // visibles, y en el post() el overlay puede no estarlo todavia.
            // Se descartaba el foco en silencio por eso. Con visibility basta.
            if (v.visibility == View.VISIBLE && v.requestFocus()) {
                return true
            }
        }
        return false
    }

    /**
     * Apaga todo lo que hay DETRAS del menu para que el D-pad no se escape.
     *
     * Tres capas de defensa:
     *   1. La pagina y la barra de atras pasan a focusable=false mientras el
     *      menu esta abierto: Android no puede darles foco desde la rejilla.
     *      (Ojo: NO se usa descendantFocusability=blocksDescendants en el
     *      overlay, eso bloquea a los propios iconos del menu.)
     *   2. dispatchKeyEvent manda las flechas a moveFocusInGrid, que navega
     *      solo entre los 14 botones del menu.
     *   3. ATRAS y MENU cierran el menu en vez de actuar sobre la pagina.
     */
    private fun trapFocusBehindMenu(blocked: Boolean) {
        val behind = listOf(webView, cursorOverlay, settingsScreen, toolbarContainer)
        for (v in behind) {
            v.isFocusable = !blocked
            v.isFocusableInTouchMode = !blocked
        }
        // Al bloquear, si el foco estaba en la pagina, Android puede dejarlo
        // huerfano (currentFocus=null) y las flechas dejan de responder.
        // Se rescata de inmediato aqui; el post() de showQuickMenu lo confirma
        // una vez medido el layout.
        if (blocked && !isQuickMenuFocused()) {
            focusFirstQuickMenuItem()
        }
    }

    private fun hookQuickMenu() {
        findViewById<View>(R.id.qmClose).setOnClickListener { hideQuickMenu() }
        findViewById<View>(R.id.qmPower).setOnClickListener {
            hideQuickMenu()
            showExitConfirmationDialog()
        }
        findViewById<View>(R.id.qmSettings).setOnClickListener {
            hideQuickMenu()
            showSettingsScreen()
        }
        findViewById<View>(R.id.qmTabs).setOnClickListener {
            hideQuickMenu()
            showTabsDialog()
        }
        findViewById<View>(R.id.qmHistory).setOnClickListener {
            hideQuickMenu()
            showHistoryDialog()
        }
        findViewById<View>(R.id.qmBookmarks).setOnClickListener {
            hideQuickMenu()
            showBookmarksDialog()
        }
        findViewById<View>(R.id.qmAddBookmark).setOnClickListener {
            hideQuickMenu()
            toggleCurrentBookmark()
        }
        findViewById<View>(R.id.qmDownloads).setOnClickListener {
            hideQuickMenu()
            openDownloadsFolder()
        }
        findViewById<View>(R.id.qmNight).setOnClickListener {
            hideQuickMenu()
            toggleNightMode()
        }
        findViewById<View>(R.id.qmIncognito).setOnClickListener {
            hideQuickMenu()
            toggleIncognito()
        }
        findViewById<View>(R.id.qmDesktop).setOnClickListener {
            hideQuickMenu()
            toggleDesktopMode()
        }
        findViewById<View>(R.id.qmTools).setOnClickListener {
            hideQuickMenu()
            showMoreMenu()
        }
        findViewById<View>(R.id.qmFindInPage).setOnClickListener {
            hideQuickMenu()
            showFindInPageDialog()
        }
    }

    // -------------------------------------------------- acciones del menu

    private fun toast(resId: Int) {
        Toast.makeText(this, resId, Toast.LENGTH_SHORT).show()
    }

    /** Guarda o quita la pagina actual de marcadores (prefs, sin base de datos). */
    private fun toggleCurrentBookmark() {
        val url = webView.url ?: return
        val title = webView.title.orEmpty().ifBlank { Uri.parse(url).host ?: url }
        val current = preferenceManager.getBookmarks()
        val exists = current.any { it.first == url }
        if (exists) {
            preferenceManager.setBookmarks(current.filter { it.first != url })
            toast(R.string.qm_bookmark_exists)
        } else {
            preferenceManager.setBookmarks(current + (url to title))
            toast(R.string.qm_bookmark_added)
        }
    }

    private fun showBookmarksDialog() {
        val items = preferenceManager.getBookmarks()
        if (items.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle(R.string.qm_bookmarks)
                .setMessage(R.string.qm_bookmarks_empty)
                .setPositiveButton(R.string.dialog_ok, null)
                .showThemed()
            return
        }
        val labels = items.map { it.second }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.qm_bookmarks)
            .setItems(labels) { d, which ->
                webView.loadUrl(items[which].first)
                webView.applyDnsProxyIfNeeded()
                d.dismiss()
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .showThemed()
    }

    /** Historial de visitas: lista, navega y permite borrarlo. */
    private fun showHistoryDialog() {
        val items = preferenceManager.getHistory()
        if (items.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle(R.string.qm_history)
                .setMessage(R.string.qm_history_empty)
                .setPositiveButton(R.string.dialog_ok, null)
                .showThemed()
            return
        }
        val labels = items.map { it.second }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.qm_history)
            .setItems(labels) { d, which ->
                webView.loadUrl(items[which].first)
                webView.applyDnsProxyIfNeeded()
                d.dismiss()
            }
            .setNeutralButton(R.string.qm_history_clear) { _, _ ->
                preferenceManager.clearHistory()
                toast(R.string.qm_history_cleared)
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .showThemed()
    }

    private fun openDownloadsFolder() {
        try {
            val intent = android.content.Intent(android.app.DownloadManager.ACTION_VIEW_DOWNLOADS)
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
        } catch (e: Exception) {
            // Esta TV no trae app de descargas: se avisa en vez de fallar seco
            toast(R.string.qm_no_downloads_app)
        }
    }

    /**
     * Modo nocturno real: inyecta una hoja de estilos que invierte y oscurece.
     *
     * No es un filtro de pantalla: se aplica al DOM, asi que el texto queda
     * claro sobre fondo oscuro y las imagenes recuperan su color (se vuelven
     * a invertir para que no salgan en negativo).
     */
    private fun toggleNightMode() {
        isNightMode = !isNightMode
        applyNightMode()
        toast(if (isNightMode) R.string.qm_night_on else R.string.qm_night_off)
    }

    private fun applyNightMode() {
        val js = if (isNightMode) NIGHT_MODE_JS else NIGHT_MODE_OFF_JS
        webView.evaluateJavascript(js, null)
        updateAppThemeColors()
    }

    private fun updateAppThemeColors() {
        val screenBg = if (isNightMode) Color.parseColor("#0F172A") else Color.WHITE
        val qmCardBg = if (isNightMode) Color.parseColor("#1E293B") else Color.WHITE
        val qmTextColor = if (isNightMode) Color.parseColor("#F8FAFC") else Color.parseColor("#3C3C3C")
        val qmIconColor = if (isNightMode) Color.parseColor("#F8FAFC") else Color.parseColor("#1C1B1D")

        // 1. Setup / Settings screens background and text updates
        for (v in setupScreens()) {
            v.setBackgroundColor(screenBg)
            updateTextViewsRecursive(v, isNightMode)
        }

        for (screenId in listOf(R.id.adBlockScreen, R.id.subscriptionsScreen, R.id.dnsScreen, R.id.navigationScreen, R.id.appearanceScreen, R.id.advancedScreen, R.id.aboutScreen)) {
            findViewById<View>(screenId)?.let {
                it.setBackgroundColor(screenBg)
                updateTextViewsRecursive(it, isNightMode)
            }
        }

        // 2. Quick Menu card background tint and items
        val qmCardContainer = (quickMenu as? ViewGroup)?.getChildAt(0)
        qmCardContainer?.background?.setTint(qmCardBg)

        for (id in TOP_IDS + GRID_IDS) {
            val cell = findViewById<View>(id) ?: continue
            if (cell is ViewGroup) {
                for (i in 0 until cell.childCount) {
                    when (val child = cell.getChildAt(i)) {
                        is ImageView -> child.setColorFilter(qmIconColor)
                        is TextView -> child.setTextColor(qmTextColor)
                    }
                }
            }
        }
    }

    private fun updateTextViewsRecursive(view: View, isNight: Boolean) {
        val textColor = if (isNight) Color.parseColor("#F8FAFC") else Color.parseColor("#DE000000")
        val textSecColor = if (isNight) Color.parseColor("#94A3B8") else Color.parseColor("#8A000000")

        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                updateTextViewsRecursive(view.getChildAt(i), isNight)
            }
        } else if (view is TextView) {
            if (view.textSize > 15f || view.id == R.id.tvHeaderTitle) {
                view.setTextColor(textColor)
            } else {
                view.setTextColor(textSecColor)
            }
        }
    }

    private fun AlertDialog.Builder.showThemed(): AlertDialog {
        val dialog = this.create()
        val isNight = isNightMode
        val bgColor = if (isNight) Color.parseColor("#1E293B") else Color.WHITE

        dialog.window?.let { w ->
            w.setGravity(android.view.Gravity.END or android.view.Gravity.FILL_VERTICAL)
            w.setLayout(
                (420 * resources.displayMetrics.density).toInt(),
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            w.setWindowAnimations(R.style.RightDrawerAnimation)
            w.setBackgroundDrawable(android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = 20f * resources.displayMetrics.density
                setColor(bgColor)
            })
        }

        dialog.setOnShowListener {
            applyThemeToDialogView(dialog.window!!.decorView, isNight)
        }

        dialog.show()
        return dialog
    }

    private fun applyThemeToDialogView(view: View, isNight: Boolean) {
        val textColor = if (isNight) Color.parseColor("#F8FAFC") else Color.parseColor("#1C1B1D")
        val textSecColor = if (isNight) Color.parseColor("#94A3B8") else Color.parseColor("#616161")

        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                applyThemeToDialogView(view.getChildAt(i), isNight)
            }
        } else if (view is TextView) {
            view.setTextColor(textColor)
        }
    }

    /**
     * Incognito real: al activarlo se limpian cookies, cache, historial WebView
     * y el historial propio, y se dejan de guardar las visitas.
     */
    private fun toggleIncognito() {
        val on = !preferenceManager.isIncognito()
        preferenceManager.setIncognito(on)

        if (on) {
            val cm = android.webkit.CookieManager.getInstance()
            cm.removeAllCookies(null)
            cm.flush()
            webView.clearHistory()
            webView.clearCache(true)
            webView.clearFormData()
            preferenceManager.clearHistory()
            toast(R.string.qm_incognito_on)
        } else {
            toast(R.string.qm_incognito_off)
        }
        webView.reload()
    }

    /**
     * Buscar texto en la pagina con la API nativa findAllAsync.
     * Los resultados se resaltan solos; se informa del numero encontrado.
     */
    private fun showFindInPageDialog() {
        val input = EditText(this).apply {
            hint = getString(R.string.qm_find_hint)
            setSingleLine()
            setPadding(48, 32, 48, 32)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.qm_find)
            .setView(input)
            .setPositiveButton(R.string.qm_find_next) { _, _ ->
                val q = input.text.toString()
                if (q.isNotEmpty()) {
                    webView.findAllAsync(q)
                    toast(R.string.qm_find_started)
                }
            }
            .setNeutralButton(R.string.qm_find_clear) { _, _ ->
                webView.clearMatches()
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .showThemed()
    }

    /** Alterna el User-Agent entre movil y escritorio y recarga. */    private fun toggleDesktopMode() {
        isDesktopMode = !isDesktopMode
        val ua = if (isDesktopMode) {
            getString(R.string.ua_desktop)
        } else {
            null
        }
        webView.settings.userAgentString = ua
            ?: android.webkit.WebSettings.getDefaultUserAgent(this)
        webView.reload()
        toast(if (isDesktopMode) R.string.qm_desktop_on else R.string.qm_desktop_off)
    }

    private fun shareCurrentPage() {
        val url = webView.url
        if (url.isNullOrBlank()) {
            toast(R.string.qm_nothing_to_share)
            return
        }
        try {
            val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(android.content.Intent.EXTRA_TEXT, url)
            }
            startActivity(android.content.Intent.createChooser(send, getString(R.string.qm_share)))
        } catch (e: Exception) {
            // Sin apps que reciban texto: se copia la URL al portapapeles
            copyUrlToClipboard(url)
        }
    }

    /** Copia la URL al portapapeles como respaldo de "Compartir". */
    private fun copyUrlToClipboard(url: String) {
        try {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText("url", url))
            toast(R.string.qm_url_copied)
        } catch (e: Exception) {
            toast(R.string.qm_nothing_to_share)
        }
    }

    private fun showMoreMenu() {
        val modes = DPadMode.entries
        val labels = modes.map { getString(it.labelResId) }.toTypedArray()
        val current = dpadController.currentMode.ordinal

        AlertDialog.Builder(this)
            .setTitle(R.string.mode_dialog_title)
            .setSingleChoiceItems(labels, current) { dialog, which ->
                dpadController.switchMode(modes[which])
                dialog.dismiss()
            }
            .setNeutralButton(R.string.setting_center_cursor_summary) { _, _ ->
                cursorOverlay.centerCursor()
            }
            .setNegativeButton(R.string.btn_exit_menu) { _, _ ->
                showExitConfirmationDialog()
            }
            .showThemed()
    }

    private fun showTabsDialog() {
        val tabOptions = tabsList.toTypedArray().plus(getString(R.string.new_tab))
        AlertDialog.Builder(this)
            .setTitle(R.string.tabs_title)
            .setSingleChoiceItems(tabOptions, currentTabIndex) { dialog, which ->
                if (which == tabsList.size) {
                    tabsList.add(localTestUrl)
                    currentTabIndex = tabsList.size - 1
                    webView.loadUrl(localTestUrl)
                } else {
                    currentTabIndex = which
                    webView.loadUrl(tabsList[which])
                }
                dialog.dismiss()
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .showThemed()
    }

    private fun showDownloadConfirmationDialog(url: String, mimeType: String?) {
        AlertDialog.Builder(this)
            .setTitle("Descargar Archivo")
            .setMessage("¿Deseas descargar este archivo mediante el gestor del sistema?")
            .setPositiveButton(R.string.dialog_yes) { _, _ ->
                try {
                    val request = DownloadManager.Request(Uri.parse(url)).apply {
                        setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                        setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, Uri.parse(url).lastPathSegment ?: "archivo")
                    }
                    val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                    dm.enqueue(request)
                    Toast.makeText(this, "Descarga iniciada", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(this, "Error al iniciar la descarga", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(R.string.dialog_no, null)
            .showThemed()
    }

    private fun showHttpWarningDialog(url: String, onProceed: () -> Unit) {
        AlertDialog.Builder(this)
            .setTitle(R.string.http_warning_title)
            .setMessage(R.string.http_warning_message)
            .setPositiveButton(R.string.dialog_yes) { _, _ -> onProceed() }
            .setNegativeButton(R.string.dialog_no, null)
            .showThemed()
    }

    private fun showExitConfirmationDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.exit_dialog_title)
            .setMessage(R.string.exit_dialog_message)
            .setPositiveButton(R.string.dialog_yes) { _, _ -> finish() }
            .setNegativeButton(R.string.dialog_no, null)
            .showThemed()
    }

    private fun showSettingsScreen() {
        lastFocusedView = currentFocus
        showSetup(null, R.id.rowMenuGeneral)
    }

    // ==================================================================
    //  NAVEGACION DE AJUSTES (estilo Via: raiz + submenus)
    // ==================================================================

    /**
     * Muestra una pantalla de ajustes y esconde las demas.
     *
     * Via no usa un solo scroll con todo dentro: tiene una raiz con las
     * secciones y cada seccion abre su propia pantalla con la flecha "<"
     * para volver. Se replica igual.
     *
     * [screen] = null -> raiz. Si no, la vista de esa pantalla.
     * [focusId] = que fila recibe el foco al entrar.
     */
    @Suppress("UNUSED_PARAMETER")
    private fun showSetup(screen: View?, focusId: Int) {
        // Ocultar todo y mostrar solo lo pedido
        for (v in setupScreens()) v.visibility = View.GONE
        val target = screen ?: settingsScreen
        target.visibility = View.VISIBLE
        currentSetupScreen = target

        // La pagina y la barra de atras dejan de aceptar foco: aunque una
        // flecha encontrara un vecino "arriba", no puede saltar al WebView.
        setFocusableBehindSetup(true)

        updateSetupValues()

        // El foco se pide tras el layout (ver nota en el menu rapido)
        target.post { findViewById<View>(focusId)?.requestFocus() }
    }

    /** Bloquea (o libera) el foco de todo lo que queda DETRAS de ajustes. */
    private fun setFocusableBehindSetup(blocked: Boolean) {
        // cursorOverlay queda fuera a proposito: solo dibuja el puntero, no
        // es enfocable, y tocarlo lo dejaria sin poder reaparecer.
        for (v in listOf(webView, toolbarContainer)) {
            v.isFocusable = !blocked
            v.isFocusableInTouchMode = !blocked
        }
    }

    /** Las tres pantallas de ajuste que se manejan por separado. */
    private fun setupScreens(): List<View> = listOf(
        settingsScreen,
        findViewById(R.id.adBlockScreen),
        findViewById(R.id.subscriptionsScreen)
    )

    private fun showBrowserScreen() {
        for (v in setupScreens()) v.visibility = View.GONE
        settingsScreen.visibility = View.GONE
        currentSetupScreen = null
        setFocusableBehindSetup(false)
        val target = lastFocusedView ?: findViewById<View>(R.id.btnSettings)
        if (target != null && target.isAttachedToWindow) {
            target.requestFocus()
        } else {
            findViewById<View>(R.id.btnSettings)?.requestFocus()
        }
    }

    /** true si hay alguna pantalla de ajuste abierta. */
    private fun isSetupVisible(): Boolean =
        settingsScreen.visibility == View.VISIBLE ||
            findViewById<View>(R.id.adBlockScreen).visibility == View.VISIBLE ||
            findViewById<View>(R.id.subscriptionsScreen).visibility == View.VISIBLE

    /** Refresca todos los valores de las pantallas de ajuste. */
    private fun updateSetupValues() {
        updateSettingsScreen()
        updateAdBlockScreen()
        updateSubscriptionsScreen()
    }

    /** Solo pinta el intervalo; la lista la redibuja refreshSubscriptionsList(). */
    private fun updateSubscriptionsScreen() {
        val days = preferenceManager.getSubsIntervalDays()
        findViewById<TextView>(R.id.tvSubsInterval).text =
            if (days <= 0) getString(R.string.subs_interval_never)
            else getString(R.string.subs_interval_days, days)
    }

    private fun updateAdBlockScreen() {
        val on = preferenceManager.isAdBlockEnabled()
        val total = preferenceManager.getBlockedTotal()
        val subsEnabled = preferenceManager.isAdBlockSubscriptionsEnabled()
        val builtinEnabled = preferenceManager.isAdBlockBuiltinEnabled()

        // Resumen del maestro: numero real de bloqueos
        findViewById<TextView>(R.id.tvAdBlockMasterSummary).text =
            if (on) getString(R.string.setting_adblock_master_summary_on, total)
            else getString(R.string.setting_adblock_master_summary_off)

        setSwitch(R.id.swAdBlockMaster, on)
        setSwitch(R.id.swAdBlockBuiltin, builtinEnabled)
        setSwitch(R.id.swAdBlockCustom, subsEnabled && subsEnabled)

        // Listas descargadas
        val states = adBlockerManager.subscriptions.states()
        val active = states.values.count { it.enabled && it.rules > 0 }
        val totalRules = adBlockerManager.subscriptions.activeRuleCount()
        findViewById<TextView>(R.id.tvAdBlockCustomSummary).text =
            if (active > 0) getString(R.string.setting_adblock_custom_summary_active, active, totalRules)
            else getString(R.string.setting_adblock_custom_summary_none)

        // Bloqueo en el sitio actual
        val currentUrl = webView.url.orEmpty()
        val currentHost = Uri.parse(currentUrl).host
        val siteRow = findViewById<View>(R.id.rowAdBlockCurrentSite)
        val siteHost = findViewById<TextView>(R.id.tvCurrentSiteHost)
        if (currentHost == null) {
            siteHost.setText(R.string.setting_adblock_site_no_page)
            siteRow.isEnabled = false
            siteRow.isFocusable = false
            setSwitch(R.id.swAdBlockCurrentSite, false)
        } else {
            val whitelisted = adBlockerManager.domainWhitelist.isWhitelisted(currentUrl)
            siteHost.text = currentHost
            siteRow.isEnabled = true
            siteRow.isFocusable = true
            // El interruptor de sitio: encendido = se bloquea (no esta en lista blanca)
            setSwitch(R.id.swAdBlockCurrentSite, !whitelisted)
        }

        findViewById<TextView>(R.id.tvSettingBlockedStats).text =
            getString(R.string.setting_blocked_stats_value_format, total)
    }

    /** Pinta el circulo del interruptor: relleno si on, hueco si off. */
    private fun setSwitch(id: Int, on: Boolean) {
        findViewById<View>(id)?.setBackgroundResource(
            if (on) R.drawable.bg_switch_on else R.drawable.bg_switch_off
        )
    }

    /** Primer intento: la primera fila; si no se deja, la cabecera de volver. */
    private fun focusFirstSettingRow(): Boolean {
        findViewById<View>(R.id.rowMenuGeneral)?.let { if (it.requestFocus()) return true }
        return findViewById<View>(R.id.settingsHeader)?.findViewById<View>(R.id.btnHeaderBack)?.requestFocus() ?: false
    }

    /**
     * Valores de la pantalla raiz de ajustes.
     *
     * En el diseno Via la raiz NO muestra valores: son filas de seccion con
     * ">". Los submenus se refrescan en updateSetupValues().
     */
    private fun updateSettingsScreen() {
    }

    private fun setupSettingsActions() {
        // ---------------------------------------------- raiz: secciones
        findViewById<View>(R.id.settingsHeader).findViewById<View>(R.id.btnHeaderBack).setOnClickListener {
            showBrowserScreen()
        }

        findViewById<View>(R.id.rowMenuGeneral).setOnClickListener {
            showGeneralDialog()
        }
        findViewById<View>(R.id.rowMenuAppearance).setOnClickListener {
            showAppearanceDialog()
        }
        findViewById<View>(R.id.rowMenuPrivacy).setOnClickListener {
            showSetup(findViewById(R.id.adBlockScreen), R.id.rowAdBlockMaster)
        }
        findViewById<View>(R.id.rowMenuDns).setOnClickListener {
            showDnsSelectionDialog()
        }
        findViewById<View>(R.id.rowMenuAdvanced).setOnClickListener {
            showAdvancedDialog()
        }
        findViewById<View>(R.id.rowMenuAbout).setOnClickListener {
            showAboutDialog()
        }

        // ---------------------------------------- bloqueo de anuncios
        findViewById<View>(R.id.adBlockHeader).findViewById<View>(R.id.btnHeaderBack).setOnClickListener {
            showSetup(null, R.id.rowMenuPrivacy)
        }

        findViewById<View>(R.id.rowAdBlockMaster).setOnClickListener {
            preferenceManager.setAdBlockEnabled(!preferenceManager.isAdBlockEnabled())
            updateAdBlockScreen()
            webView.reload()
        }

        findViewById<View>(R.id.rowAdBlockBuiltin).setOnClickListener {
            preferenceManager.setAdBlockBuiltinEnabled(
                !preferenceManager.isAdBlockBuiltinEnabled()
            )
            updateAdBlockScreen()
            webView.reload()
        }

        findViewById<View>(R.id.rowAdBlockCustom).setOnClickListener {
            preferenceManager.setAdBlockSubscriptionsEnabled(
                !preferenceManager.isAdBlockSubscriptionsEnabled()
            )
            updateAdBlockScreen()
            webView.reload()
        }

        findViewById<View>(R.id.rowAdBlockSubscriptions).setOnClickListener {
            showSetup(findViewById(R.id.subscriptionsScreen), R.id.rowSubsInterval)
            refreshSubscriptionsList()
        }

        findViewById<View>(R.id.rowAdBlockCurrentSite).setOnClickListener {
            val currentUrl = webView.url.orEmpty()
            if (Uri.parse(currentUrl).host == null) return@setOnClickListener
            val whitelisted = adBlockerManager.domainWhitelist.isWhitelisted(currentUrl)
            adBlockerManager.domainWhitelist.setWhitelisted(currentUrl, !whitelisted)
            updateAdBlockScreen()
            webView.reload()
        }

        findViewById<View>(R.id.rowSettingBlockedStats).setOnClickListener {
            preferenceManager.resetBlockedTotal()
            updateAdBlockScreen()
            toast(R.string.setting_blocked_reset)
        }
        findViewById<View>(R.id.tvSettingBlockedReset).setOnClickListener {
            preferenceManager.resetBlockedTotal()
            updateAdBlockScreen()
            toast(R.string.setting_blocked_reset)
        }

        // -------------------------------------------------- suscripciones
        findViewById<View>(R.id.btnSubsBack).setOnClickListener {
            showSetup(findViewById(R.id.adBlockScreen), R.id.rowAdBlockSubscriptions)
        }

        findViewById<View>(R.id.btnSubsUpdate).setOnClickListener {
            downloadAllSubscriptions()
        }

        findViewById<View>(R.id.rowSubsDownloadAll).setOnClickListener {
            downloadAllSubscriptions()
        }

        findViewById<View>(R.id.rowSubsInterval).setOnClickListener {
            showSubsIntervalDialog()
        }
    }

    // ==================================================================
    //  DIALOGOS DE LAS SECCIONES
    // ==================================================================

    private fun showGeneralDialog() {
        val speed = preferenceManager.getCursorSpeedMultiplier()
        val speedLabel = getString(
            when {
                speed < 0.8f -> R.string.speed_slow
                speed > 1.2f -> R.string.speed_fast
                else -> R.string.speed_medium
            }
        )
        val modeLabel = getString(preferenceManager.getSavedMode().labelResId)
        AlertDialog.Builder(this)
            .setTitle(R.string.settings_section_navigation)
            .setMessage(getString(R.string.general_dialog_format, speedLabel, modeLabel))
            .setPositiveButton(R.string.setting_cursor_speed) { _, _ ->
                val next = when {
                    speed < 0.8f -> 1.0f
                    speed in 0.8f..1.2f -> 2.0f
                    else -> 0.5f
                }
                preferenceManager.setCursorSpeedMultiplier(next)
                toast(R.string.settings_saved)
            }
            .setNeutralButton(R.string.setting_default_mode) { _, _ ->
                dpadController.switchMode(preferenceManager.getSavedMode().next())
                toast(R.string.settings_saved)
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .showThemed()
    }

    private fun showAppearanceDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.settings_section_appearance)
            .setItems(
                arrayOf(
                    getString(R.string.qm_night),
                    getString(R.string.qm_desktop),
                    getString(R.string.qm_incognito)
                )
            ) { _, which ->
                when (which) {
                    0 -> toggleNightMode()
                    1 -> toggleDesktopMode()
                    2 -> toggleIncognito()
                }
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .showThemed()
    }

    private fun showAdvancedDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.settings_section_advanced)
            .setItems(
                arrayOf(
                    getString(R.string.btn_center_cursor),
                    getString(R.string.setting_dns_clear_cache),
                    getString(R.string.setting_dns_test)
                )
            ) { _, which ->
                when (which) {
                    0 -> cursorOverlay.centerCursor()
                    1 -> {
                        DnsManager.currentResolver?.clearCache()
                        toast(R.string.dns_cache_cleared)
                    }
                    2 -> runDnsTest()
                }
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .showThemed()
    }

    private fun showAboutDialog() {
        val subRules = adBlockerManager.subscriptions.activeRuleCount()
        val msg = getString(
            R.string.about_dialog_format,
            adBlockerManager.statsLine(),
            subRules,
            preferenceManager.getBlockedTotal()
        )
        AlertDialog.Builder(this)
            .setTitle(R.string.settings_section_about)
            .setMessage(msg)
            .setPositiveButton(R.string.dialog_ok, null)
            .showThemed()
    }

    // ==================================================================
    //  SUSCRIPCIONES DE FILTROS
    // ==================================================================

    /** Redibuja la lista de suscripciones segun lo descargado. */
    private fun refreshSubscriptionsList() {
        val container = findViewById<LinearLayout>(R.id.subsListContainer)
        val empty = findViewById<View>(R.id.tvSubsEmpty)
        container.removeAllViews()

        val states = adBlockerManager.subscriptions.states()
        val cats = adBlockerManager.subscriptions.catalog

        for (f in cats) {
            val st = states[f.id] ?: FilterSubscriptions.State()
            container.addView(buildSubsRow(f, st))
            container.addView(buildDivider())
        }
        empty.visibility = View.GONE

        val days = preferenceManager.getSubsIntervalDays()
        findViewById<TextView>(R.id.tvSubsInterval).text =
            if (days <= 0) getString(R.string.subs_interval_never)
            else getString(R.string.subs_interval_days, days)
    }

    /** Construye una fila de suscripcion en runtime (nombre + estado + switch). */
    private fun buildSubsRow(
        f: FilterSubscriptions.Filter,
        st: FilterSubscriptions.State
    ): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            isFocusable = true
            isFocusableInTouchMode = false
            isClickable = true
            setBackgroundResource(R.drawable.bg_setup_item)
            minimumHeight = dp(72)
        }

        val textCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val title = TextView(this).apply {
            text = f.name
            setTextColor(getColor(R.color.setup_text))
            textSize = 16f
        }
        textCol.addView(title)

        val summary = TextView(this).apply {
            text = when {
                st.rules > 0 -> getString(
                    R.string.subs_updated_format, st.rules, st.dateLabel()
                )
                st.enabled -> getString(R.string.subs_not_downloaded)
                else -> f.description
            }
            setTextColor(getColor(R.color.setup_text_secondary))
            textSize = 13f
            setPadding(0, dp(2), 0, 0)
        }
        textCol.addView(summary)
        row.addView(textCol)

        val sw = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(22), dp(22)).apply {
                marginStart = dp(12)
            }
            setBackgroundResource(
                if (st.enabled && st.rules > 0) R.drawable.bg_switch_on
                else R.drawable.bg_switch_off
            )
        }
        row.addView(sw)

        // Tocar la fila descarga (si falta) o activa/desactiva
        row.setOnClickListener {
            if (st.rules <= 0) {
                downloadSingle(f)
            } else {
                adBlockerManager.subscriptions.setEnabled(f.id, !st.enabled)
                adBlockerManager.reloadSubscriptions()
                refreshSubscriptionsList()
                webView.reload()
            }
        }
        return row
    }

    private fun buildDivider(): View = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(1)
        ).apply { marginStart = dp(16) }
        setBackgroundColor(getColor(R.color.setup_divider))
    }

    private fun dp(v: Int): Int =
        (v * resources.displayMetrics.density).toInt()

    /** Descarga una sola lista. */
    private fun downloadSingle(filter: FilterSubscriptions.Filter) {
        Toast.makeText(this, getString(R.string.subs_downloading, 1), Toast.LENGTH_SHORT).show()
        Thread {
            val r = adBlockerManager.subscriptions.download(filter)
            runOnUiThread {
                if (r.ok) {
                    adBlockerManager.reloadSubscriptions()
                    refreshSubscriptionsList()
                    updateAdBlockScreen()
                    webView.reload()
                } else {
                    toastMsg(getString(R.string.subs_download_failed, r.error ?: "?"))
                }
            }
        }.apply { isDaemon = true; name = "via2-subs" }.start()
    }

    /** Descarga todas las listas del catalogo de forma progresiva (uno por uno). */
    private fun downloadAllSubscriptions() {
        val all = adBlockerManager.subscriptions.catalog
        toastMsg(getString(R.string.subs_downloading, all.size))
        Thread {
            var ok = 0
            var fail = 0
            var rules = 0
            for ((index, f) in all.withIndex()) {
                runOnUiThread {
                    toastMsg("(${index + 1}/${all.size}) Descargando: ${f.name}...")
                }
                val r = adBlockerManager.subscriptions.download(f)
                if (r.ok) {
                    ok++
                    rules += r.rules
                } else {
                    fail++
                }
                // Refresco en tiempo real tras la descarga individual de cada filtro
                runOnUiThread {
                    refreshSubscriptionsList()
                }
            }
            preferenceManager.setSubsLastUpdate(System.currentTimeMillis())
            runOnUiThread {
                adBlockerManager.reloadSubscriptions()
                refreshSubscriptionsList()
                updateAdBlockScreen()
                webView.reload()
                val msg = if (fail == 0) {
                    getString(R.string.subs_download_done, ok, rules)
                } else if (ok == 0) {
                    getString(R.string.subs_download_failed, "$fail listas")
                } else {
                    getString(R.string.subs_download_partial, ok, fail)
                }
                toastMsg(msg)
            }
        }.apply { isDaemon = true; name = "via2-subs-all" }.start()
    }

    private fun showSubsIntervalDialog() {
        val options = arrayOf(
            getString(R.string.subs_interval_never),
            getString(R.string.subs_interval_days, 1),
            getString(R.string.subs_interval_days, 3),
            getString(R.string.subs_interval_days, 7),
            getString(R.string.subs_interval_days, 30)
        )
        val values = intArrayOf(0, 1, 3, 7, 30)
        val current = values.indexOf(preferenceManager.getSubsIntervalDays())
            .coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle(R.string.subs_interval)
            .setSingleChoiceItems(options, current) { d, which ->
                preferenceManager.setSubsIntervalDays(values[which])
                refreshSubscriptionsList()
                d.dismiss()
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .showThemed()
    }

    private fun toastMsg(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }


    /**
     * Prueba el DNS de verdad: resuelve un dominio por el proveedor activo y
     * cronometra. No simula nada: si el proveedor no responde, se dice.
     */
    private fun runDnsTest() {
        val resolver = DnsManager.currentResolver
        if (resolver == null || !resolver.isEnabled) {
            AlertDialog.Builder(this)
                .setTitle(R.string.setting_dns_test)
                .setMessage(R.string.setting_dns_test_no_doh)
                .setPositiveButton(R.string.dialog_ok, null)
                .showThemed()
            return
        }
        val host = "example.com"
        val started = System.currentTimeMillis()
        val resultado = Runnable {
            val ips = try {
                resolver.resolveAllIpv4(host)
            } catch (e: Exception) {
                emptyList()
            }
            val ms = System.currentTimeMillis() - started
            runOnUiThread {
                val mensaje = if (ips.isNotEmpty()) {
                    getString(
                        R.string.setting_dns_test_ok_format,
                        host, ips.first(), ms
                    )
                } else {
                    getString(
                        R.string.setting_dns_test_fail_format,
                        host, ms, resolver.lastError ?: getString(R.string.setting_dns_test_no_response)
                    )
                }
                AlertDialog.Builder(this)
                    .setTitle(R.string.setting_dns_test)
                    .setMessage(mensaje)
                    .setPositiveButton(R.string.dialog_ok, null)
                    .showThemed()
                updateSettingsScreen()
            }
        }
        Thread(resultado, "via2-dns-test").apply { isDaemon = true }.start()
        toast(R.string.setting_dns_test_running)
    }

    /** Edita la URL DoH propia (cuando el proveedor elegido es "Personalizado"). */
    private fun showDnsCustomUrlDialog() {
        val input = EditText(this).apply {
            hint = getString(R.string.setting_dns_custom_hint)
            setSingleLine()
            setText(preferenceManager.getDnsCustomUrl())
            setPadding(48, 32, 48, 32)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.setting_dns_custom)
            .setView(input)
            .setPositiveButton(R.string.dialog_ok) { _, _ ->
                val url = input.text.toString().trim()
                preferenceManager.setDnsCustomUrl(url)
                DnsManager.applyFromPreferences(this, preferenceManager)
                val r = DnsManager.currentResolver
                if (r != null) {
                    r.configure(DnsProvider.fromId(preferenceManager.getDnsProvider()), url)
                }
                webView.applyDnsProxyIfNeeded()
                updateSettingsScreen()
                toast(R.string.setting_dns_custom_saved)
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .showThemed()
    }

    private fun showDnsSelectionDialog() {
        val providers = DnsProvider.entries.toTypedArray()
        val titles = providers.map { it.title }.toTypedArray()
        val currentId = preferenceManager.getDnsProvider()
        val currentIndex = providers.indexOfFirst { it.id == currentId }.coerceAtLeast(0)

        AlertDialog.Builder(this)
            .setTitle(R.string.dns_dialog_title)
            .setSingleChoiceItems(titles, currentIndex) { dialog, which ->
                val selected = providers[which]
                preferenceManager.setDnsProvider(selected.id)
                DnsManager.applyFromPreferences(this, preferenceManager)
                webView.applyDnsProxyIfNeeded()
                updateSettingsScreen()
                Toast.makeText(
                    this,
                    getString(R.string.dns_applied, selected.title),
                    Toast.LENGTH_SHORT
                ).show()
                dialog.dismiss()
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .showThemed()
    }

    companion object {
        /** Columnas de la rejilla del menu (3 filas x 4 columnas = 12 iconos). */
        private const val GRID_COLS = 4

        /** Barra superior del menu (encendido, cerrar), en orden de layout. */
        private val TOP_IDS = arrayOf(R.id.qmPower, R.id.qmClose)

        /** Los iconos de la rejilla, en orden de lectura (izq->der, arriba->abajo). */
        private val GRID_IDS = arrayOf(
            R.id.qmNight, R.id.qmBookmarks, R.id.qmHistory, R.id.qmDownloads,
            R.id.qmIncognito, R.id.qmAddBookmark, R.id.qmDesktop, R.id.qmTools,
            R.id.qmSettings, R.id.qmFindInPage, R.id.qmTabs
        )

        /**
         * Modo nocturno: invierte los colores del documento y vuelve a invertir
         * imagenes y videos para que no salgan en negativo. Los fondos blancos
         * pasan a negro y el texto a claro.
         */
        private const val NIGHT_MODE_JS = """
        (function(){
          var id='via2-night';
          if(document.getElementById(id)) return;
          var s=document.createElement('style');
          s.id=id;
          s.textContent='html{filter:invert(1) hue-rotate(180deg);background:#000}'+
            'img,video,picture,canvas,svg,iframe,[style*="background-image"]{filter:invert(1) hue-rotate(180deg)}';
          (document.head||document.documentElement).appendChild(s);
        })();
        """

        private const val NIGHT_MODE_OFF_JS = """
        (function(){
          var s=document.getElementById('via2-night');
          if(s) s.remove();
        })();
        """
    }
}

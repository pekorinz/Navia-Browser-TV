package com.pekorinz.via2.webview

import android.webkit.WebChromeClient
import android.webkit.WebView

/**
 * WebChromeClient del navegador: informa del progreso de carga y del titulo
 * del documento a la barra superior.
 */
class TVWebChromeClient(
    private val onProgressChanged: (Int) -> Unit,
    private val onTitleReceived: (String) -> Unit
) : WebChromeClient() {

    override fun onProgressChanged(view: WebView, newProgress: Int) {
        super.onProgressChanged(view, newProgress)
        onProgressChanged(newProgress)
    }

    override fun onReceivedTitle(view: WebView, title: String) {
        super.onReceivedTitle(view, title)
        onTitleReceived(title)
    }
}

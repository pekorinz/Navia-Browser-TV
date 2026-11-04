package com.pekorinz.via2.webview

import android.graphics.Bitmap
import android.webkit.WebChromeClient
import android.webkit.WebView

/**
 * WebChromeClient del navegador: informa del progreso de carga y del titulo
 * del documento a la barra superior.
 */
class TVWebChromeClient(
    private val onProgressChanged: (Int) -> Unit,
    private val onTitleReceived: (String) -> Unit,
    private val onIconReceived: (WebView, Bitmap) -> Unit = { _, _ -> }
) : WebChromeClient() {

    override fun onProgressChanged(view: WebView, newProgress: Int) {
        super.onProgressChanged(view, newProgress)
        onProgressChanged(newProgress)
    }

    override fun onReceivedTitle(view: WebView, title: String) {
        super.onReceivedTitle(view, title)
        onTitleReceived(title)
    }

    override fun onReceivedIcon(view: WebView, icon: Bitmap) {
        super.onReceivedIcon(view, icon)
        onIconReceived(view, icon)
    }
}

package com.pekorinz.via2.dns

/**
 * Error de "host inexistente" reconocido por el WebView.
 *
 * Android no tiene un codigo de error para "bloqueado por el DNS", asi que el
 * proxy devuelve un host que no existe y este client lo traduce a una pantalla
 * de "bloqueado por DNS" en vez del tipico ERR_NAME_NOT_RESOLVED.
 */
object DnsBlockedHost {
    const val HOST = "dns-bloqueado.via2.invalid"
    const val HOST_WITH_PORT = "$HOST:1"
}

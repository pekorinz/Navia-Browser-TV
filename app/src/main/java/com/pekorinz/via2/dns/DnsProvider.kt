package com.pekorinz.via2.dns

/**
 * Catalogo de proveedores DNS que usa el navegador.
 *
 * Todo se resuelve con DNS-over-HTTPS (RFC 8484): se manda un POST con la
 * consulta en binario y la respuesta llega en binario. Eso permite usar
 * cualquier DNS sin permisos especiales de Android y, sobre todo, sin tocar
 * el DNS del sistema.
 */
enum class DnsProvider(
    val id: String,
    val displayName: String,
    val dohUrl: String?,
    val fallbackIps: List<String> = emptyList()
) {
    SYSTEM(
        id = "system",
        displayName = "Sistema (como viene en la TV)",
        dohUrl = null
    ),

    CLOUDFLARE(
        id = "cloudflare",
        displayName = "Cloudflare 1.1.1.1",
        dohUrl = "https://cloudflare-dns.com/dns-query",
        fallbackIps = listOf("1.1.1.1", "1.0.0.1")
    ),

    CLOUDFLARE_BLOCK(
        id = "cloudflare-family",
        displayName = "Cloudflare 1.1.1.2 (bloquea malware)",
        dohUrl = "https://family.cloudflare-dns.com/dns-query",
        fallbackIps = listOf("1.1.1.2", "1.0.0.2")
    ),

    GOOGLE(
        id = "google",
        displayName = "Google 8.8.8.8",
        dohUrl = "https://dns.google/dns-query",
        fallbackIps = listOf("8.8.8.8", "8.8.4.4")
    ),

    QUAD9(
        id = "quad9",
        displayName = "Quad9 9.9.9.9 (bloquea malware)",
        dohUrl = "https://dns.quad9.net/dns-query",
        fallbackIps = listOf("9.9.9.9", "149.112.112.112")
    ),

    QUAD9_INSECURE(
        id = "quad9-nofilter",
        displayName = "Quad9 9.9.9.10 (sin filtro)",
        dohUrl = "https://dns10.quad9.net/dns-query",
        fallbackIps = listOf("9.9.9.10", "149.112.112.10")
    ),

    ADGUARD(
        id = "adguard",
        displayName = "AdGuard DNS (bloquea anuncios)",
        dohUrl = "https://dns.adguard-dns.com/dns-query",
        fallbackIps = listOf("94.140.14.14", "94.140.15.15")
    ),

    ADGUARD_FAMILY(
        id = "adguard-family",
        displayName = "AdGuard Family (anuncios + adultos)",
        dohUrl = "https://family.adguard-dns.com/dns-query",
        fallbackIps = listOf("94.140.14.15", "94.140.15.16")
    ),

    NEXTDNS(
        id = "nextdns",
        displayName = "NextDNS",
        dohUrl = "https://dns.nextdns.io",
        fallbackIps = listOf("45.90.28.0", "45.90.30.0")
    ),

    DNS0(
        id = "dns0",
        displayName = "DNS0.eu (europeo, sin filtro)",
        dohUrl = "https://dns0.eu/dns-query",
        fallbackIps = listOf("193.110.81.0", "185.253.5.0")
    ),

    MULLVAD(
        id = "mullvad",
        displayName = "Mullvad DNS (sin logs)",
        dohUrl = "https://dns.mullvad.net/dns-query",
        fallbackIps = listOf("194.242.2.2")
    ),

    CUSTOM(
        id = "custom",
        displayName = "Personalizado",
        dohUrl = null
    );

    /** El navegador puede resolver con este perfil solo si tiene una URL DoH. */
    fun isUsable(customUrl: String): Boolean = when (this) {
        SYSTEM -> false
        CUSTOM -> customUrl.trim().startsWith("https://")
        else -> dohUrl != null
    }

    /** Nombre que se muestra en la interfaz. Alias de [displayName]. */
    val title: String get() = displayName

    fun urlFor(customUrl: String): String? = when (this) {
        SYSTEM -> null
        CUSTOM -> customUrl.trim().ifBlank { null }
        else -> dohUrl
    }

    companion object {
        fun fromId(id: String?): DnsProvider =
            entries.firstOrNull { it.id == id } ?: SYSTEM
    }
}

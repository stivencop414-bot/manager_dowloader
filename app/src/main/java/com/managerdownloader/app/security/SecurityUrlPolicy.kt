package com.managerdownloader.app.security

import android.net.Uri
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.Locale
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.ResponseBody

/**
 * Central policy for remote URLs that originate from untrusted web content or extractors.
 * User-controlled generic downloads are still validated by the platform/network stack, but
 * automatic analyzers and the WebView media bridge are restricted to public HTTPS endpoints.
 */
object SecurityUrlPolicy {
    const val MAX_HLS_MANIFEST_BYTES = 2 * 1024 * 1024
    const val MAX_EXTRACTOR_RESPONSE_BYTES = 24 * 1024 * 1024

    val publicDns: Dns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            validateHostName(hostname)
            val addresses = Dns.SYSTEM.lookup(hostname)
            if (addresses.isEmpty() || addresses.any(::isNonPublicAddress)) {
                throw UnknownHostException("Destino de red privada o no pública bloqueado: $hostname")
            }
            return addresses
        }
    }

    fun requirePublicHttps(raw: String): HttpUrl {
        val parsed = raw.trim().toHttpUrlOrNull()
            ?: throw IOException("URL remota inválida")
        if (!parsed.isHttps) {
            throw IOException("Solo se permiten conexiones HTTPS seguras")
        }
        validateHostName(parsed.host)
        if (isNumericHost(parsed.host)) {
            val address = runCatching { InetAddress.getByName(parsed.host) }.getOrNull()
                ?: throw IOException("Host IP inválido")
            if (isNonPublicAddress(address)) {
                throw IOException("Destino de red privada o local bloqueado")
            }
        }
        return parsed
    }

    fun isSafePublicHttps(raw: String?): Boolean =
        !raw.isNullOrBlank() && runCatching { requirePublicHttps(raw) }.isSuccess

    fun sameHttpsOrigin(pageUrl: String?, sourceOrigin: Uri): Boolean {
        if (pageUrl.isNullOrBlank()) return false
        val page = runCatching { Uri.parse(pageUrl) }.getOrNull() ?: return false
        if (!page.scheme.equals("https", ignoreCase = true)) return false
        if (!sourceOrigin.scheme.equals("https", ignoreCase = true)) return false
        val pageHost = page.host?.lowercase(Locale.US) ?: return false
        val sourceHost = sourceOrigin.host?.lowercase(Locale.US) ?: return false
        if (pageHost != sourceHost) return false
        val pagePort = if (page.port == -1) 443 else page.port
        val sourcePort = if (sourceOrigin.port == -1) 443 else sourceOrigin.port
        return pagePort == sourcePort && isSafePublicHttps(pageUrl)
    }

    fun readUtf8Limited(body: ResponseBody?, maxBytes: Int): String {
        if (body == null) return ""
        val safeLimit = maxBytes.coerceIn(1, 32 * 1024 * 1024)
        val declared = body.contentLength()
        if (declared > safeLimit) {
            throw IOException("Respuesta remota demasiado grande (${declared} bytes)")
        }

        body.byteStream().use { input ->
            val output = ByteArrayOutputStream(minOf(safeLimit, 256 * 1024))
            val buffer = ByteArray(64 * 1024)
            var total = 0
            while (true) {
                val read = input.read(buffer)
                if (read == -1) break
                total += read
                if (total > safeLimit) {
                    throw IOException("Respuesta remota excede el límite de seguridad")
                }
                output.write(buffer, 0, read)
            }
            return output.toString(Charsets.UTF_8.name())
        }
    }

    private fun validateHostName(hostname: String) {
        val host = hostname.trim().lowercase(Locale.US).trimEnd('.')
        if (host.isBlank() || host.length > 253) throw UnknownHostException("Host inválido")
        if (
            host == "localhost" ||
            host.endsWith(".localhost") ||
            host.endsWith(".local") ||
            host.endsWith(".internal") ||
            host.endsWith(".lan") ||
            host.endsWith(".home")
        ) {
            throw UnknownHostException("Host local bloqueado")
        }
    }

    private fun isNumericHost(host: String): Boolean =
        host.contains(':') || host.matches(Regex("""^\d{1,3}(?:\.\d{1,3}){3}$"""))

    private fun isNonPublicAddress(address: InetAddress): Boolean {
        if (
            address.isAnyLocalAddress ||
            address.isLoopbackAddress ||
            address.isLinkLocalAddress ||
            address.isSiteLocalAddress ||
            address.isMulticastAddress
        ) return true

        val bytes = address.address
        if (address is Inet4Address && bytes.size == 4) {
            val a = bytes[0].toInt() and 0xff
            val b = bytes[1].toInt() and 0xff
            if (a == 0 || a >= 240) return true
            if (a == 100 && b in 64..127) return true // RFC 6598 CGNAT
        }
        if (address is Inet6Address && bytes.isNotEmpty()) {
            val first = bytes[0].toInt() and 0xff
            if ((first and 0xfe) == 0xfc) return true // fc00::/7 unique local
        }
        return false
    }
}

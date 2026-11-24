package et.addisreel.app

import java.net.URI

/**
 * Strict navigation allowlist for the embedded staff-only Creator Studio.
 * Authentication is always enforced by the Cloudflare server, not this policy.
 */
object StudioNavigationPolicy {
    const val ORIGIN = "https://addisreel-catalog.eddieaman475.workers.dev"
    private const val HOST = "addisreel-catalog.eddieaman475.workers.dev"
    private val agreementPath = Regex("^/v1/admin/rights/[a-f0-9-]{36}/document$")

    fun isTrusted(url: String): Boolean {
        val uri = try { URI(url) } catch (_: Exception) { return false }
        return !uri.isOpaque && uri.scheme.equals("https", ignoreCase = true) &&
            uri.host.equals(HOST, ignoreCase = true) &&
            (uri.port == -1 || uri.port == 443) &&
            uri.rawUserInfo == null
    }

    fun isPrivateAgreement(url: String): Boolean {
        if (!isTrusted(url)) return false
        val uri = try { URI(url) } catch (_: Exception) { return false }
        return uri.rawQuery == null && agreementPath.matches(uri.path ?: "")
    }
}

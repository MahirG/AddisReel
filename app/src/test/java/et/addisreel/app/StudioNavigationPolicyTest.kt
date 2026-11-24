package et.addisreel.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StudioNavigationPolicyTest {
    @Test fun trustedCloudflareRoutesOnly() {
        assertTrue(StudioNavigationPolicy.isTrusted(StudioNavigationPolicy.ORIGIN + "/admin"))
        assertTrue(StudioNavigationPolicy.isTrusted(StudioNavigationPolicy.ORIGIN + "/admin/creators"))
        assertTrue(StudioNavigationPolicy.isTrusted(StudioNavigationPolicy.ORIGIN + "/admin/rights"))
        assertTrue(StudioNavigationPolicy.isTrusted(StudioNavigationPolicy.ORIGIN + "/v1/auth/session"))
        assertFalse(StudioNavigationPolicy.isTrusted("http://addisreel-catalog.eddieaman475.workers.dev/admin"))
        assertFalse(StudioNavigationPolicy.isTrusted("https://addisreel-catalog.eddieaman475.workers.dev.attacker.test/admin"))
        assertFalse(StudioNavigationPolicy.isTrusted("https://evil.test@attacker.test/admin"))
        assertFalse(StudioNavigationPolicy.isTrusted("javascript:alert(1)"))
        assertFalse(StudioNavigationPolicy.isTrusted("intent://something"))
        assertFalse(StudioNavigationPolicy.isTrusted("file:///etc/passwd"))
        assertFalse(StudioNavigationPolicy.isTrusted("https://addisreel-catalog.eddieaman475.workers.dev:8443/admin"))
    }

    @Test fun signedAgreementsRequirePrivateOwnerRoute() {
        val id = "f6a3fb08-77ab-4cf9-acbd-2fbc69948aae"
        assertTrue(StudioNavigationPolicy.isPrivateAgreement(StudioNavigationPolicy.ORIGIN + "/v1/admin/rights/" + id + "/document"))
        assertFalse(StudioNavigationPolicy.isPrivateAgreement(StudioNavigationPolicy.ORIGIN + "/v1/admin/creators/" + id))
        assertFalse(StudioNavigationPolicy.isPrivateAgreement("https://evil.test/v1/admin/rights/" + id + "/document"))
        assertFalse(StudioNavigationPolicy.isPrivateAgreement(StudioNavigationPolicy.ORIGIN + "/v1/admin/rights/" + id + "/document?redirect=https://evil.test"))
        assertFalse(StudioNavigationPolicy.isPrivateAgreement(StudioNavigationPolicy.ORIGIN + "/v1/admin/rights/../document"))
    }
}

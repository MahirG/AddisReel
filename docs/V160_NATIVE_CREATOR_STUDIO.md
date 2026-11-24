# AddisReel 1.6.0 — Build 25 · Native Creator Studio

## Scope

This release integrates the **existing, deployed Cloudflare Content Studio + Creator Studio Phase 1 + Exclusive Rights Phase 2** into the installed Android app, preserving the same signed `et.addisreel.app` package and the official in-app APK update channel. It does not duplicate the backend or create another admin authentication system.

**Entry:** Open AddisReel → **Premium / App updates** → **Creator Studio · Staff access** → **Open Creator Studio**. A dedicated native Android activity hosts the owner workspace.

Use the native top toolbar to switch between **Content Studio**, **Filmmakers**, and **Exclusive rights** without leaving the app. If signed out, use the existing password flow inside the embedded Studio; credentials are never embedded in the APK.

### WebView isolation

- Only `https://addisreel-catalog.eddieaman475.workers.dev` is permitted for top-level navigation; JavaScript URLs, HTTP, external sites, custom schemes and alternate ports are rejected.
- `CreatorStudioActivity` is `android:exported="false"`; no Android deep link exposes it.
- Strict TLS errors are cancelled, mixed-content and WebView local file access are disabled, popups and automatic JavaScript windows are disabled, third-party cookies are refused. No JavaScript bridge is registered.
- Storage Access Framework document selection is deliberately enabled for **explicitly user-selected** content URI files so the existing contract PDF uploader functions. This does not grant broad storage permissions.
- Owner session remains a server-authorized `HttpOnly`, `Secure`, `SameSite=Strict` Cloudflare cookie. The Android activity never supplies or persists a hardcoded administrator token.
- Staff screens use `FLAG_SECURE` to discourage casual screen recording of legal agreements and filmmaker contact details.
- Signed agreement PDF downloads are allowlisted to the exact authenticated `/v1/admin/rights/:id/document` path. A same-origin HTTPS request reuses the existing session cookie, limits downloaded files to 5 MB, checks the PDF signature, stores it only in private app cache, and renders it **inside Android** with `PdfRenderer`. File is removed when the reader closes.
- UI supports back navigation and reload via the native toolbar. Android back keeps browsing state.

### What users can do

- Register and review filmmaker / studio profiles (Phase 1).
- Create, upload, review, approve, reject and revoke signed exclusive first-premiere agreements (Phase 2).
- Continue using existing Content Studio workflows for catalog and uploads. **Note:** Android WebView's Storage Access Framework file chooser supports file selection and PDF uploads; full folder / `webkitdirectory` selection for HLS collections may still require the dedicated desktop Studio. This build makes no claim of complete desktop folder-upload parity.
- Public viewers cannot access the administrative data without the Cloudflare owner credentials. The launch card is visible as a staff-sign-in entry, but successful access requires the existing server session.

### Preserved

- Existing public Watch, Home, Library, Premium, player, color themes, Android animations and playback state
- Previous release 1.5.0 as base; Cloudflare catalog, D1, private R2, rights gate and updater Worker are unchanged
- Existing signing identity; explicit version bump to **1.6.0, Build 25**

### Validation and limitations

- Android debug, JUnit navigation/origin tests, signed-release APK build, Node backend tests and production preflight must pass before release marker is added.
- CI release workflow verifies **live** Creator Studio protection, exclusive rights readiness, public catalog continuity, and the existing release manifest before signing and promoting Build 25.
- No test can assert actual credentialed owner login or production signed-PDF rendering without a consenting owner test session; this must be checked on a real Android device after rollout.
- The app does not provide new public creator signup, e-signature identity vetting, DRM, region enforcement or automatic rights clearance; those remain separate milestones.

Release is promoted only after CI through `.release/1.6.0-publish.json` and the guarded `publish-update.yml` workflow; no direct APK distribution overrides.

# AddisReel Studio 2.0 — Account & publishing milestone

Status: implementation specification; not yet deployed.

## Goals
- Installable admin PWA with cinematic AddisReel branding and responsive desktop/mobile layouts.
- Account-based sign-in: exchange existing bootstrap admin token **once**, server-side, for a real admin account; never store the bootstrap token in localStorage, sessionStorage, IndexedDB, or PWA cache.
- Server-managed sessions using Secure, HttpOnly, SameSite cookies; rotate session identifiers on login, enforce idle/absolute expiry, revoke sessions on sign-out and password change. Use CSRF protection for cookie-authenticated mutations.
- Hash passwords with Argon2id (or vetted equivalent), rate-limit sign-in, avoid account enumeration, log security events without secrets, require re-authentication for sensitive actions.
- Roles: owner, editor, publisher, viewer. Enforce permissions in API/backend, not just UI.
- Preserve login across page refreshes while session is valid. Provide explicit sign-out and device/session revocation.
- Migrate existing admin API token access safely; keep existing workflow functional until the account login path is verified; then revoke legacy credentials after owner confirmation.

## Publishing workflow
- Smart Paste accepts clipboard JSON or structured text only after a user-initiated Paste action (no background clipboard surveillance).
- Map title (Amharic/English), synopsis, genre, language, cast, age rating, poster, trailer, episode metadata, premium/free, release state.
- Validate schema, show differences from existing draft, preview before applying; never auto-publish from clipboard.
- Persist drafts and support undo/version history; prevent accidental overwrite.
- Upload assets via signed URLs with size/MIME validation and malware scanning where available; store playback media in appropriate object storage/CDN, not database rows.
- Publish only licensed Ethiopian content.

## Acceptance tests
1. First-time owner enrolls using existing token once; bootstrap token is invalidated only after successful verified migration.
2. Login survives refresh/relaunch without re-entering token.
3. Sign-out invalidates the session on the server.
4. Unauthorized users cannot read/write content endpoints, even with crafted requests.
5. Editor cannot grant permissions or publish; publisher can publish; viewer is read-only.
6. Clipboard parse errors and duplicates are safe; review is required before saving.
7. PWA install works on supported Android/desktop browsers, with no cached sensitive API responses.
8. Existing movie drafts and episodes remain intact through migration.

## Delivery order
1. Audit existing admin frontend, API and authentication.
2. Add database migration, account/session API, tests and rollback.
3. Integrate sign-in and installable PWA UI.
4. Integrate Smart Paste and episode publishing workflows.
5. Run CI/security checks and ship APK/PWA release only after verification.

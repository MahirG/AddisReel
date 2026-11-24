# AddisReel Creator Studio 2.0 — Phase 1: Filmmaker onboarding

Release scope: **owner-assisted creator registration, private creator profiles, independent identity/production-authority review, and an immutable-in-practice append-only review log**. This is a Cloudflare Studio backend release, **not** an Android APK update.

## Production entry points

- Existing Content Studio: https://addisreel-catalog.eddieaman475.workers.dev/admin
- Owner-only Creator Studio: https://addisreel-catalog.eddieaman475.workers.dev/admin/creators
- Admin API: `GET/POST /v1/admin/creators`, `PATCH /v1/admin/creators/:id/review`, `GET /v1/admin/creators/:id/events`

The dashboard redirects anonymous visitors to the existing Studio sign-in. All API endpoints require the existing HttpOnly owner session. State-changing requests also require exact same-origin checks. No public creator directory, no public applications API, and no creator credentials are created in Phase 1.

## Review lifecycle

1. Sign in as the existing AddisReel Studio owner; open **Creator Studio 2.0**.
2. Enter a filmmaker or registered production studio's professional name, legal name, email, and optional location/filmography, with recorded consent for storing the contact details.
3. The record starts in **pending**. A duplicate contact email is rejected.
4. Open **Review creator** and choose pending, needs information, verified, or rejected.
5. **Verified** requires **both** manual identity and production-authority confirmations and at least 20 characters explaining the checks performed.
6. The system writes each creation and review to `creator_review_events`. Previous verified status cannot be revoked without a written explanation.

Never upload passports or national IDs to this form. There is no secure document vault yet. Record decisions without copying document numbers, addresses, or sensitive ID details into notes. Only authorized owners should process these records, with a documented data-retention and deletion policy before broad onboarding.

**Important:** A verified creator is NOT an automatically licensed film. Existing series and episode publication gates still require separately proven distribution rights. No filmmaker content is made public by creating or verifying a creator.

## Storage and migrations

Existing Cloudflare D1 binding: `DB`. Additive and idempotent migration `backend/migrations/002_creator_onboarding.sql` adds `creator_profiles` and `creator_review_events` and two indexes, without altering existing series, episodes, admin auth, processor jobs or media objects. No new Cloudflare secret or R2 bucket is necessary.

No data is returned by public catalog endpoints. The creator records contain contact information and must not be exposed outside owner routes.

## Validation and deployment

- Run `node --test backend/tests/*.test.mjs` and check GitHub Android APK workflow.
- Independently verify deployed `/health/ready` is healthy, Creator Studio client is served, anonymous profile listing returns HTTP 401, anonymous private dashboard redirects, and cross-origin writes return HTTP 403.
- Cloudflare deployed via **script-content-only** update, preserving all previous eight modules and existing bindings: `ADMIN_TOKEN`, `DB`, `MEDIA`, `IMAGES`. The new modules are `creators.js`, `creator-page.js` and `creator-client.js`.
- Verify new deployment is serving at 100% traffic through Cloudflare Worker deployments API. This is an owner-side web rollout, so Android 1.5.0 / Build 24 and its in-app update manifest are unchanged.

## Explicitly out of scope / next steps

- Public self-service creator sign-up with verified email, bot mitigation and independent credential/session management.
- Secure encrypted identity-document processing, retention policy and verified email notifications.
- Per-movie signed exclusive contracts, legal clearance and creator-to-title ownership mapping (Phase 2).
- Premiere scheduling, analytics, payouts and monetization.

Publishing this Phase 1 registry does not claim any external creator has been verified; all new profiles start pending.

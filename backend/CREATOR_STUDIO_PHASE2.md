# AddisReel Creator Studio 2.0 — Phase 2: Exclusive film rights

**Scope:** private, owner-reviewed worldwide exclusive first-premiere rights for a verified creator and an existing drama series. This is a Cloudflare **Content Studio** upgrade; Android 1.5.0 / Build 24 and its in-app update manifest are unchanged.

## URLs

- Owner Studio: https://addisreel-catalog.eddieaman475.workers.dev/admin
- Verified creator profiles: https://addisreel-catalog.eddieaman475.workers.dev/admin/creators
- **Exclusive rights:** https://addisreel-catalog.eddieaman475.workers.dev/admin/rights

## Workflow

1. Create and **verify** a filmmaker or production studio in Phase 1. Enter a film in **draft** in Content Studio.
2. Open **Exclusive Rights**, select a verified creator and an existing film, and record a **unique signed agreement reference**, execution date, worldwide territory and permitted streaming start/end dates. Explicitly attest that the film is original and unreleased and that the rights holder authorized the grant.
3. The claim starts **pending**. Upload the **executed agreement as a PDF of no more than 5 MB**. It is SHA-256 hashed and stored under a private `private/creator-rights/` R2 key; the document is not publicly addressable through playback or poster endpoints. Owner session is required to download or replace it.
4. A human reviewer independently examines the executed agreement and verifies copyright chain of title, ownership/authority, production contracts, clearances, exclusivity, dates, and worldwide streaming scope. Record a specific review note (20+ characters), and check the two independent verification controls.
5. The agreement can be **approved**, **rejected** or **revoked**, with review chronology stored in `title_rights_events`. A revoked agreement cannot be restored; a fresh agreement is required. Approval rejects another overlapping approved exclusive claim for the same series.
6. **New or previously unpublished titles** require an approved current agreement, a currently **verified** creator, an uploaded signed PDF, worldwide scope and the original-unreleased declaration to become publicly published and playable. Start date must have arrived; expiration, revocation or withdrawn creator verification makes new-title catalog/playback routes fail their rights gate.
7. Series publishing, episode publishing, catalog lists, episode list, playback resolution, video/segment gateway, and poster gateway all apply the dynamic rights predicate.

## Existing films and deployment safety

Migration `003_exclusive_rights.sql` creates `title_rights`, `title_rights_events`, `grandfathered_rights`, and a **one-time** `rights_migration_meta` snapshot marker. Only series already marked licensed and published when Phase 2 rolled out are grandfathered. Snapshot is frozen: rerunning migrations does **not** silently exempt newly published series. The two grandfathered titles remain on their previous license-flag workflow until explicitly migrated and legally reviewed later.

No new live series or episodes are automatically published. Publishing rights is not proof of legal ownership: contract execution and underlying copyrights must be independently verified by qualified people. **Territory selection is deliberately worldwide only** until geographic playback restrictions are built; a territorial license restricted to Ethiopia cannot be used to expose a public worldwide stream.

## Security and privacy

- Existing HttpOnly owner session + exact-origin CSRF checks required for admin mutations; no public creator/rights API, no public contract download endpoint.
- 5 MB PDF limit, PDF content-type and magic signature validation; private R2 object; owner-only download as attachment, `no-store`, content hash recorded in private D1 and audit.
- Do not store national ID numbers, passports, financial credentials or other sensitive identity information in notes. Contract PDFs may still contain personal or commercial information: limit access, establish retention/deletion policies and review applicable privacy obligations before onboarding outside creators.
- Existing media streams **are not DRM protected**. Revocation blocks future Worker-authorized playback requests, but material previously downloaded/cached may persist and public media caching can cause brief stale access. Media segment DRM, entitlement, and territory geofencing are later work.
- Public catalog responses never include contract identifiers, contact details, PDF hashes, internal notes or review chronology.

## API

Owner only:
- `GET/POST /v1/admin/rights` — register and list private title rights
- `PUT /v1/admin/rights/:id/document` — private signed-PDF upload
- `GET /v1/admin/rights/:id/document` — owner-only signed-PDF attachment download
- `PATCH /v1/admin/rights/:id/review` — approve, reject or revoke
- `GET /v1/admin/rights/:id/events` — private audit history

## Verification

Run `node --test backend/tests/*.test.mjs`, Android CI, and a live smoke job verifying:
- /health/ready with `exclusive-rights-registry`
- the rights client is served and authenticated dashboard redirects anonymous users
- rights APIs return HTTP 401 unauthenticated, HTTP 403 for cross-origin writes
- /v1/catalog continues serving correctly and its ETag conditional GET returns HTTP 304
- production D1 contains exactly the previously grandfathered series (two at initial rollout), and no fabricated signed agreements
- Cloudflare Worker retains all eight original modules, three Phase 1 modules, plus three Phase 2 modules, and preserves `ADMIN_TOKEN`, `DB`, `MEDIA`, `IMAGES` bindings.

**No new user accounts or fake signed agreements are created in production.** Phase 3 premiere scheduling and future paid distribution are separate milestones.

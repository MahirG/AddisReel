# AddisReel 1.7.0 — Build 26
## Phase 3 exclusive premieres + interactive Premium page

### What changes in Android

The previously static **Premium** tab is a three-section premium hub preserving the existing app's design tokens, accessibility defaults and dark/light modes:

- **Premieres (default):** cinematic hero, original-film browse CTA, live eligible premiere countdowns fetched from the live public `GET /v1/premieres` API, timezone-aware release labels, episode-readiness information, optional expandable details and locally saved premiere IDs. Saving a premiere **does not** set a push notification or server-side subscription.
- **Studio:** secure in-app entry points for Content Studio, verified Filmmakers, signed Exclusive Rights, and Phase 3 Premiere Calendar. Uses the existing non-exported `CreatorStudioActivity` WebView, Cloudflare owner session, HTTPS origin allowlist and Android document picker. No secret is packaged in the Android APK. New allowlisted internal route `/admin/premieres`; the native Studio menu includes it.
- **Settings:** official in-app update checker/installer and appearance picker, preserving the original signing-certificate and version-check protections. The cinematic hero contains a prominent shortcut to this section.
- **Membership:** expandable information-only card. Premium payment processing is **not** enabled. No fake purchase button or subscription terms.

English, Amharic and Afaan Oromo resource strings are provided. The tab strip scrolls horizontally on narrow phones and enlarged fonts; content and Studio controls have minimum 48dp interaction targets.

### Owner premiere workflow

1. Confirm the filmmaker/studio is **verified**, and review a signed worldwide exclusive first-premiere agreement in Exclusive Rights. A contract covering the planned premiere date must be approved.
2. Create the movie as a **draft** and an episode as **draft** in Content Studio. Upload the media. Do not publish unrelated material or grant unsigned rights.
3. Open **Creator Studio → Premiere Calendar**. Schedule a premiere in the device's local timezone; the server stores strict `YYYY-MM-DDTHH:MM:SSZ` UTC and displays EAT and the local time in the calendar.
4. Optionally schedule individual episodes **at or after** the title's premiere. Studio records scheduling, rescheduling, pause, resume and cancellation changes with a time-stamped audit event.
5. Once the future schedule exists and is rights-cleared, mark the title and its ready episode(s) Published + Licensed. The public API automatically gates catalog listing, episodes, posters, playback resolution and streaming-media requests until the scheduled time. No cron needed: SQLite UTC clock predicates apply to every request.
6. The public `/premieres` page and the Android Premium countdown reveal only titles already Published + Licensed, with approved rights and a media-ready first episode. The public `GET /v1/premieres` response contains only title, genre, description, UTC premiere time, ready episode count and a transient countdown; it never contains creator contact details or agreement records.
7. Pausing or canceling a scheduled future premiere prevents it being accessed publicly until explicitly resumed or scheduled again. A creator losing verified status or a contract expiring also blocks new-title publication. Already published legacy titles retain their previous behavior.

**Operational rules:** An owner **cannot retroactively convert a currently released, published film into an upcoming premiere**. It must be a new draft title. Scheduled time alone does not publish the title or its episode; both still require explicit owner Published + Licensed status and finished media.

### Deployment

- D1 migration `backend/migrations/004_premiere_scheduling.sql` creates `premiere_schedules`, `episode_release_schedules`, and `premiere_schedule_events` without altering existing series or episodes.
- Worker additions: `premieres.js`, `premiere-page.js`, `premiere-client.js`, `premiere-public-page.js`, `premiere-public-client.js`; original Worker modules, owner auth and bindings retained.
- Live production web routes: `/admin/premieres` (owner only), `/premieres` (public), `/v1/premieres` (public).
- Android package `et.addisreel.app` version **1.7.0**, code **26**. Existing version 1.6.0/25 update system and signature remain unchanged. Only after successful CI and independent signed-build checks should the guarded release marker `.release/1.7.0-publish.json` authorize publication.

### Validation and known limits

- Node Worker tests cover strict UTC, future-only scheduling, non-public owner routes, CSRF, approval and title rights, audit logs, countdown client script integrity and preservation of unscheduled published titles.
- Android unit tests cover UTC parsing, timezone independence, future countdowns, expired countdown behavior and malformed inputs.
- GitHub Action `premiere-live-smoke.yml` checks live server readiness, public clock, catalog continuity, owner auth and origin checks. Build 26 release pipeline independently reruns its backend and signed APK checks before cloud promotion.
- No manual credentialed test has been performed with an actual filmmaking contract; do not claim legal review is automated. A real-device check of all sections, enhanced font sizes and the PDF upload flow is still recommended.
- Premium bookmarks are **local only**. There are no push reminders, paywall, checkout, entitlement claims, DRM or territorial geofencing in this milestone.
- Player URLs/media may be cached briefly by clients/CDNs. Publication is clock-gated on the backend; already downloaded media cannot be revoked retroactively.

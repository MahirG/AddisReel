# AddisReel short Studio URL

- **Short URL:** https://ar.eddieaman475.workers.dev
- **Original service:** https://addisreel-catalog.eddieaman475.workers.dev/admin
- **Cloudflare Worker name:** `ar`
- **Deployment:** Upload `backend/short-url.js` as an ES module Worker, with service binding
  `CATALOG` targeting `addisreel-catalog` in `production`; enable `workers.dev` for `ar`.
- All paths besides `/` and `/admin/` forward to the original catalog Worker through Cloudflare's
  internal service binding. The origin remains the short URL; there is no secret duplication.
- The original API address remains unchanged, so the Android app and Windows processor are unaffected.
- Admin sessions are scoped to the hostname, so **sign in again** when switching to the short URL.
- This is a temporary no-domain address. A branded domain can be added later without affecting the original endpoint.

Do **not** change the account-wide workers.dev subdomain: it is shared by AddisReel updates and Alem.

// Short, same-origin AddisReel Studio URL.
// Cloudflare binding: CATALOG -> addisreel-catalog (production).
// Does not duplicate admin secrets, D1 databases, R2 buckets or their permissions.
export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    if ((url.pathname === "/" || url.pathname === "/admin/") &&
        (request.method === "GET" || request.method === "HEAD")) {
      return Response.redirect(url.origin + "/admin" + url.search, 302);
    }
    if (!env.CATALOG || typeof env.CATALOG.fetch !== "function") {
      return new Response("AddisReel Studio is temporarily unavailable.", {
        status: 503, headers: { "Cache-Control": "no-store" }
      });
    }
    // Preserve original Host, Origin, Cookie and request body. This ensures
    // HttpOnly __Host sessions and same-origin security work on the short URL.
    return env.CATALOG.fetch(request);
  }
};

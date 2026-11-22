// AddisReel update gateway. Bind a private R2 bucket as RELEASES.
// R2 objects: releases/latest.json and releases/<version>/AddisReel.apk
// Never expose R2 write credentials to Android clients.
export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    if (request.method !== "GET" && request.method !== "HEAD") return new Response("Method not allowed", { status: 405 });
    const headers = { "X-Content-Type-Options": "nosniff", "Cache-Control": "no-store" };
    if (url.pathname === "/v1/latest") {
      const object = await env.RELEASES.get("releases/latest.json");
      if (!object) return Response.json({ error: "No published update" }, { status: 404, headers });
      const manifest = await object.json();
      if (!/^\\d+\\.\\d+\\.\\d+$/.test(manifest.version) ||
          !/^\\d+$/.test(String(manifest.versionCode)) ||
          !/^[a-f0-9]{64}$/i.test(manifest.sha256) ||
          !/^releases\\/[a-zA-Z0-9._-]+\\/AddisReel\\.apk$/.test(manifest.objectKey)) {
        return Response.json({ error: "Invalid release metadata" }, { status: 503, headers });
      }
      return Response.json({
        version: manifest.version,
        versionCode: Number(manifest.versionCode),
        notes: String(manifest.notes || "").slice(0, 1000),
        sha256: manifest.sha256.toLowerCase(),
        apkUrl: `${url.origin}/v1/apk/${encodeURIComponent(manifest.version)}`
      }, { headers });
    }
    const match = url.pathname.match(/^\\/v1\\/apk\\/(\\d+\\.\\d+\\.\\d+)$/);
    if (match) {
      const key = `releases/${match[1]}/AddisReel.apk`;
      const object = await env.RELEASES.get(key);
      if (!object) return new Response("Not found", { status: 404, headers });
      return new Response(request.method === "HEAD" ? null : object.body, {
        headers: { "Content-Type": "application/vnd.android.package-archive",
          "Content-Length": String(object.size), "Cache-Control": "public, max-age=3600",
          "X-Content-Type-Options": "nosniff" }
      });
    }
    return new Response("Not found", { status: 404, headers });
  }
};

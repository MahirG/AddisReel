import test from "node:test";
import assert from "node:assert/strict";
import worker from "../worker.js";

const root = "https://addisreel-catalog.example.test";
const request = (path, options) => new Request(root + path, options);
const emptyDB = {
  prepare(sql) {
    return {
      async all() { return { results: [] }; },
      bind() { return { async first() { return null; }, async all() { return { results: [] }; } }; }
    };
  }
};

test("health endpoint is reachable without exposing infrastructure secrets", async () => {
  const res = await worker.fetch(request("/health"), { DB: emptyDB });
  assert.equal(res.status, 200);
  assert.equal((await res.json()).status, "ok");
});

test("public catalog is empty until licensed content has been published", async () => {
  const res = await worker.fetch(request("/v1/catalog"), { DB: emptyDB });
  assert.equal(res.status, 200);
  assert.deepEqual((await res.json()).series, []);
  assert.equal(res.headers.get("access-control-allow-origin"), "*");
});

test("administrative mutations require a logged-in owner session", async () => {
  const res = await worker.fetch(request("/v1/admin/series", {
    method: "POST", headers: { "Content-Type": "application/json", Origin: root },
    body: JSON.stringify({ id: "test", title: "Test" })
  }), { DB: emptyDB });
  assert.equal(res.status, 401);
  assert.equal((await res.json()).error, "Sign in to AddisReel Studio");
});

test("bad admin bearer token cannot read draft catalogs", async () => {
  const res = await worker.fetch(request("/v1/admin/series", {
    headers: { Authorization: "Bearer wrong" }
  }), { DB: emptyDB, ADMIN_TOKEN: "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa" });
  assert.equal(res.status, 401);
});

test("episodes and media paths deny unknown or unpublished episodes", async () => {
  const env = { DB: emptyDB, MEDIA: { async get() { throw Error("Must not read unpublished R2"); } } };
  const play = await worker.fetch(request("/v1/play/private-show/1"), env);
  const media = await worker.fetch(request("/v1/media/private-show/1/master.m3u8"), env);
  assert.equal(play.status, 404);
  assert.equal(media.status, 404);
});

test("admin interface serves a non-cacheable, framed-denied page", async () => {
  const response = await worker.fetch(request("/admin"), { DB: emptyDB });
  assert.equal(response.status, 200);
  assert.equal(response.headers.get("x-frame-options"), "DENY");
  assert.equal(response.headers.get("cache-control"), "no-store");
  assert.match(await response.text(), /CONTENT STUDIO/);
});

test("Content Studio serves Smart Paste controls and same-origin parser", async () => {
  const page = await worker.fetch(request("/admin"), { DB: emptyDB });
  const html = await page.text();
  assert.match(html, /Paste &amp; auto-fill/);
  assert.match(html, /saveImported/);
  assert.match(html, /\/admin\/importer\.js/);
  assert.match(page.headers.get("content-security-policy"), /script-src 'self'/);
  const response = await worker.fetch(request("/admin/importer.js"), { DB: emptyDB });
  assert.equal(response.status, 200);
  assert.match(response.headers.get("content-type"), /javascript/);
  assert.match(await response.text(), /parseMovieFields/);
});

test("Admin PWA assets are installable and never expose account data", async () => {
  const env={DB:emptyDB};
  const page=await worker.fetch(request("/admin"),env);
  const html=await page.text();
  assert.match(html, /manifest.webmanifest/);
  assert.match(html, /setupForm/);
  assert.match(html, /loginForm/);
  assert.match(html, /logoutButton/);
  assert.doesNotMatch(page.headers.get("content-security-policy"), /script-src[^;]*unsafe-inline/);
  const manifest=await worker.fetch(request("/admin/manifest.webmanifest"),env);
  const json=await manifest.json();
  assert.equal(json.display,"standalone");
  assert.equal(json.start_url,"/admin");
  const sw=await worker.fetch(request("/admin/sw.js"),env);
  assert.equal(sw.status,200);
  assert.match(sw.headers.get("content-type"), /javascript/);
  assert.doesNotMatch(await sw.text(), /cache.put\(.*\/v1\/auth/);
  const javascript=await worker.fetch(request("/admin/client.js"),env);
  assert.match(await javascript.text(), /\/v1\/auth\/session/);
  for (const px of [192,512]){
    const image=await worker.fetch(request("/admin/icon-"+px+".png"),env);
    assert.equal(image.status,200);
    assert.equal(image.headers.get("content-type"),"image/png");
    const buf=new Uint8Array(await image.arrayBuffer());
    assert.deepEqual(Array.from(buf.slice(0,8)),[137,80,78,71,13,10,26,10]);
    const size=new DataView(buf.buffer).getUint32(16);
    assert.equal(size,px);
  }
});

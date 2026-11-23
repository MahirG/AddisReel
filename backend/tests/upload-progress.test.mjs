import test from "node:test";
import assert from "node:assert/strict";
import vm from "node:vm";
import { clientScript } from "../client.js";
import { studioHtml } from "../studio.js";

function harness() {
  const elements = new Map();
  const requests = [];
  let ready;
  function el(id) {
    if (!elements.has(id)) {
      const handlers = new Map();
      elements.set(id, {
        id, value: "", textContent: "", hidden: false, disabled: false,
        files: [], classList: { toggle() {} },
        setAttribute() {}, getAttribute() { return null; },
        addEventListener(type, callback) { handlers.set(type, callback); },
        trigger(type) {
          const fn = handlers.get(type);
          assert.ok(fn, `Missing ${type} handler on ${id}`);
          return fn({ preventDefault() {} });
        }
      });
    }
    return elements.get(id);
  }
  class MockXHR {
    constructor() {
      this.upload = {};
      this.headers = new Map();
      this.status = 200;
      this.responseText = "{}";
      requests.push(this);
    }
    open(method, path) { this.method = method; this.path = path; }
    setRequestHeader(k, v) { this.headers.set(k, v); }
    send(blob) { this.blob = blob; }
    progress(loaded) {
      this.upload.onprogress({ lengthComputable: true, loaded, total: this.blob.size });
    }
    complete(status = 200, responseText = "{}") {
      this.status = status;
      this.responseText = responseText;
      this.onload();
    }
    abort() { this.onabort(); }
  }
  const context = {
    document: {
      readyState: "loading",
      getElementById: el,
      querySelectorAll() { return []; },
      addEventListener(type, listener) { if (type === "DOMContentLoaded") ready = listener; }
    },
    window: { addEventListener() {} },
    navigator: {},
    XMLHttpRequest: MockXHR,
    Blob,
    console,
    fetch: async () => ({
      ok: true, status: 200,
      json: async () => ({ authenticated: false, setupRequired: false })
    })
  };
  vm.runInNewContext(clientScript, context, { timeout: 1500 });
  return { el, requests, ready: async () => { assert.ok(ready); await ready(); } };
}

test("Studio script parses and exposes progress + cancel controls", () => {
  assert.doesNotThrow(() => new vm.Script(clientScript));
  for (const id of ["transferProgress", "transferState", "cancelUpload"]) {
    assert.match(studioHtml, new RegExp('id="' + id + '"'));
  }
  assert.match(studioHtml, /Keep Studio open/);
});

test("MP4 upload shows real bytes without declaring completion before HTTP 200", async () => {
  const t = harness();
  await t.ready();
  t.el("hlsSeries").value = "series-a";
  t.el("hlsEpisode").value = "1";
  const blob = new Blob([new Uint8Array(100)], { type: "video/mp4" });
  blob.name = "episode.mp4";
  t.el("mp4File").files = [blob];

  const task = t.el("uploadMp4").trigger("click");
  assert.equal(t.requests.length, 1);
  assert.equal(t.requests[0].method, "PUT");
  assert.equal(t.requests[0].path, "/v1/admin/hls/series-a/1/source.mp4");
  t.requests[0].progress(50);
  assert.equal(t.el("transferProgress").value, 50);
  assert.match(t.el("transferState").textContent, /50%/);
  t.requests[0].progress(100);
  assert.equal(t.el("transferProgress").value, 99, "Not complete until server confirms");
  t.requests[0].complete();
  await task;
  assert.equal(t.el("transferProgress").value, 100);
  assert.match(t.el("mp4State").textContent, /uploaded/);
  assert.equal(t.el("cancelUpload").hidden, true);
  assert.equal(t.el("uploadMp4").disabled, false);
});

test("cancel aborts active request and keeps controls reusable", async () => {
  const t = harness();
  await t.ready();
  t.el("hlsSeries").value = "series-a";
  t.el("hlsEpisode").value = "1";
  const file = new Blob([new Uint8Array(64)], { type: "video/mp4" });
  file.name = "take.mp4";
  t.el("mp4File").files = [file];
  const task = t.el("uploadMp4").trigger("click");
  t.el("cancelUpload").trigger("click");
  await task;
  assert.match(t.el("mediaNotice").textContent, /cancelled/);
  assert.equal(t.el("uploadMp4").disabled, false);
  assert.equal(t.el("cancelUpload").hidden, true);
});

test("HLS batch validates master playlist before uploading", async () => {
  const t = harness();
  await t.ready();
  t.el("hlsSeries").value = "series-a";
  t.el("hlsEpisode").value = "1";
  const file = new Blob([new Uint8Array(25)], { type: "video/mp2t" });
  file.name = "seg-0.ts";
  file.webkitRelativePath = "prepared/v0/seg-0.ts";
  t.el("hlsFiles").files = [file];
  await t.el("uploadHls").trigger("click");
  assert.equal(t.requests.length, 0);
  assert.match(t.el("mediaNotice").textContent, /master.m3u8/);
});

test("HLS batch progress is based on total bytes, not file count", async () => {
  const t = harness();
  await t.ready();
  t.el("hlsSeries").value = "series-a";
  t.el("hlsEpisode").value = "1";
  const manifest = new Blob([new Uint8Array(25)], { type: "application/vnd.apple.mpegurl" });
  manifest.name = "master.m3u8";
  manifest.webkitRelativePath = "folder/master.m3u8";
  const segment = new Blob([new Uint8Array(75)], { type: "video/mp2t" });
  segment.name = "0.ts";
  segment.webkitRelativePath = "folder/v0/0.ts";
  t.el("hlsFiles").files = [manifest, segment];
  const task = t.el("uploadHls").trigger("click");
  assert.equal(t.requests.length, 1);
  t.requests[0].progress(12);
  assert.equal(t.el("transferProgress").value, 12);
  t.requests[0].complete();
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(t.requests.length, 2);
  t.requests[1].progress(25);
  assert.equal(t.el("transferProgress").value, 50);
  t.requests[1].complete();
  await task;
  assert.equal(t.el("transferProgress").value, 100);
  assert.match(t.el("uploadState").textContent, /Uploaded 2 streaming files/);
});

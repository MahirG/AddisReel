import test from "node:test";
import assert from "node:assert/strict";
import worker from "../worker.js";

const origin = "https://addisreel-catalog.example.test";
const original = "posters/series-1-unique.jpg";
const variant = "posters/series-1-unique-320.webp";
const originalBytes = new Uint8Array([255,216,255,217]);
const variantBytes = new Uint8Array([82,73,70,70,1,2,3,4]);
const calls = [];
const env = {
  DB: {
    prepare() {
      return { bind() { return { async first() { return { poster_key: original }; } }; } };
    }
  },
  MEDIA: {
    async get(key) {
      calls.push(key);
      if (key !== original && key !== variant) return null;
      const bytes = key === variant ? variantBytes : originalBytes;
      return {
        body: bytes,
        size: bytes.byteLength,
        httpEtag: key === variant ? '"variant"' : '"original"',
        httpMetadata: { contentType: key === variant ? "image/webp" : "image/jpeg" }
      };
    }
  }
};

test("poster endpoint selects a bounded WebP size and exposes timing/transfer metadata", async () => {
  calls.length = 0;
  const response = await worker.fetch(new Request(origin + "/v1/posters/series-1?w=320"), env);
  assert.equal(response.status, 200);
  assert.equal(response.headers.get("content-type"), "image/webp");
  assert.equal(response.headers.get("x-poster-variant"), "webp-320");
  assert.equal(response.headers.get("content-length"), String(variantBytes.length));
  assert.match(response.headers.get("server-timing"), /^r2;dur=/);
  assert.deepEqual(calls, [variant]);
});

test("unknown poster widths fail before reading storage", async () => {
  calls.length = 0;
  const response = await worker.fetch(new Request(origin + "/v1/posters/series-1?w=99999"), env);
  assert.equal(response.status, 400);
  assert.deepEqual(calls, []);
});

test("missing variants fall back to the original and support conditional 304", async () => {
  calls.length = 0;
  const response = await worker.fetch(new Request(origin + "/v1/posters/series-1?w=640"), env);
  assert.equal(response.status, 200);
  assert.equal(response.headers.get("content-type"), "image/jpeg");
  assert.equal(response.headers.get("x-poster-variant"), "original");
  assert.deepEqual(calls, ["posters/series-1-unique-640.webp", original]);
  const conditional = await worker.fetch(new Request(origin + "/v1/posters/series-1?w=640", {
    headers: { "If-None-Match": '"original"' }
  }), env);
  assert.equal(conditional.status, 304);
});

test("legacy poster is converted to WebP once and then served from R2", async () => {
  const objects = new Map([[original, originalBytes]]);
  let conversions = 0;
  const migratingEnv = {
    DB: env.DB,
    MEDIA: {
      async get(key) {
        const bytes = objects.get(key);
        if (!bytes) return null;
        return {
          body: bytes,
          size: bytes.length,
          httpEtag: key === original ? '"original"' : '"generated"',
          httpMetadata: { contentType: key === original ? "image/jpeg" : "image/webp" }
        };
      },
      async put(key, contents) {
        objects.set(key, new Uint8Array(contents));
      }
    },
    IMAGES: {
      input() {
        return {
          transform({ width }) {
            assert.equal(width, 320);
            return this;
          },
          output({ format }) {
            assert.equal(format, "image/webp");
            conversions++;
            return {
              response() {
                return new Response(variantBytes, {
                  status: 200,
                  headers: { "Content-Type": "image/webp" }
                });
              }
            };
          }
        };
      }
    }
  };
  const requestUrl = origin + "/v1/posters/series-1?w=320";
  const first = await worker.fetch(new Request(requestUrl), migratingEnv);
  assert.equal(first.status, 200);
  assert.equal(first.headers.get("x-poster-variant"), "webp-320");
  assert.equal(first.headers.get("content-length"), String(variantBytes.length));
  assert.deepEqual(objects.get(variant), variantBytes);
  const second = await worker.fetch(new Request(requestUrl), migratingEnv);
  assert.equal(second.status, 200);
  assert.equal(second.headers.get("x-poster-variant"), "webp-320");
  assert.equal(conversions, 1);
});

test("failed legacy optimization safely falls back to original", async () => {
  const fallbackEnv = {
    ...env,
    IMAGES: { input() { throw new Error("Images transformations temporarily unavailable"); } },
    MEDIA: { async get(key) {
      if (key === original) return {
        body: originalBytes, size: originalBytes.length, httpEtag: '"original"',
        httpMetadata: { contentType: "image/jpeg" }
      };
      return null;
    } }
  };
  const response = await worker.fetch(new Request(origin + "/v1/posters/series-1?w=320"), fallbackEnv);
  assert.equal(response.status, 200);
  assert.equal(response.headers.get("x-poster-variant"), "original");
});

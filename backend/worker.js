import { studioHtml } from "./studio.js";
import { clientScript } from "./client.js";
import { manifest, serviceWorker } from "./pwa.js";
import { icon192Base64, icon512Base64 } from "./icons.js";
import { requireAdmin, handleAuth } from "./auth.js";
import { newSalt, deriveProof } from "./password-client.js";
import { parseMovieFields } from "./importer.js";
import { handleCreatorAdmin } from "./creators.js";
import { creatorHtml } from "./creator-page.js";
import { creatorClient } from "./creator-client.js";

const publicHeaders = { "Access-Control-Allow-Origin": "*", "X-Content-Type-Options": "nosniff" };
const JSON_TYPE = "application/json; charset=utf-8";
function reply(value, status = 200, publicApi = true) {
  return new Response(JSON.stringify(value), {
    status, headers: { "Content-Type": JSON_TYPE, "Cache-Control": "no-store",
      ...(publicApi ? publicHeaders : { "X-Content-Type-Options": "nosniff" }) }
  });
}
function fail(message, status = 400, publicApi = true) {
  return reply({ error: message }, status, publicApi);
}
function idOK(id) { return typeof id === "string" && /^[a-z0-9][a-z0-9-]{0,63}$/.test(id); }
function fileOK(path) {
  return typeof path === "string" && path.length < 256 &&
    /^[A-Za-z0-9_./-]+$/.test(path) &&
    !path.split("/").some(p => !p || p === "." || p === "..") &&
    /\.(m3u8|ts|m4s|mp4)$/.test(path);
}
function extType(path) {
  if (path.endsWith(".m3u8")) return "application/vnd.apple.mpegurl";
  if (path.endsWith(".ts")) return "video/mp2t";
  if (path.endsWith(".m4s")) return "video/iso.segment";
  if (path.endsWith(".mp4")) return "video/mp4";
  return "application/octet-stream";
}
async function inputJSON(request) {
  if (Number(request.headers.get("Content-Length") || 0) > 32768) throw Error("Request too large");
  const value = await request.json();
  if (!value || typeof value !== "object" || Array.isArray(value)) throw Error("Invalid JSON object");
  return value;
}
// Check only owner-supplied file identities. R2 metadata remains private.
function uploadSha256(value) { return typeof value === "string" && /^[a-f0-9]{64}$/.test(value); }
async function digestUpload(bytes) {
  const digest = await crypto.subtle.digest("SHA-256", bytes);
  return Array.from(new Uint8Array(digest), b => b.toString(16).padStart(2, "0")).join("");
}
function textField(v, max, required = true) {
  return typeof v === "string" && v.length <= max && (!required || v.length > 0);
}
async function publicSeries(env) {
  const rows = await env.DB.prepare(
    "SELECT s.id,s.title,s.genre,s.description,s.sort_order, " +
    "(SELECT COUNT(*) FROM episodes e WHERE e.series_id=s.id AND e.status='published' AND e.licensed=1 AND e.media_prefix IS NOT NULL) AS episodes " +
    "FROM series s WHERE s.status='published' AND s.licensed=1 " +
    "AND EXISTS(SELECT 1 FROM episodes e WHERE e.series_id=s.id AND e.status='published' AND e.licensed=1 AND e.media_prefix IS NOT NULL) " +
    "ORDER BY s.sort_order ASC, s.created_at DESC"
  ).all();
  return rows.results;
}
// Public catalog metadata can be conditionally reused for fast startup. Playback
// and poster access still check live license/publication rules on every request.
async function publicCatalogResponse(request, env) {
  const body = JSON.stringify({series:await publicSeries(env)});
  const digest = new Uint8Array(await crypto.subtle.digest("SHA-256", new TextEncoder().encode(body)));
  const etag = '"' + Array.from(digest, b => b.toString(16).padStart(2,"0")).join("") + '"';
  const headers = {
    ...publicHeaders, "Content-Type":JSON_TYPE,
    "Cache-Control":"public, max-age=30, must-revalidate",
    "ETag":etag, "Vary":"Accept-Encoding"
  };
  if (request.headers.get("If-None-Match") === etag)
    return new Response(null,{status:304,headers});
  return new Response(body,{status:200,headers});
}
async function playableEpisode(env, id, n) {
  return env.DB.prepare(
    "SELECT e.series_id,e.number,e.title,e.media_prefix,e.duration_seconds FROM episodes e " +
    "JOIN series s ON s.id=e.series_id WHERE s.id=? AND e.number=? AND " +
    "s.status='published' AND s.licensed=1 AND e.status='published' AND e.licensed=1 AND e.media_prefix IS NOT NULL"
  ).bind(id,n).first();
}
async function serveR2(env, key, path, request) {
  const opts = {};
  const range = request.headers.get("Range");
  if (range) opts.range = request.headers;
  const object = await env.MEDIA.get(key, opts);
  if (!object) return fail("Media unavailable",404);
  const headers = new Headers(publicHeaders);
  headers.set("Content-Type", extType(path));
  headers.set("Cache-Control", path.endsWith(".m3u8") ? "public, max-age=30" : "public, max-age=120");
  headers.set("Accept-Ranges","bytes");
  headers.set("ETag",object.httpEtag);
  if (!range && request.headers.get("If-None-Match") === object.httpEtag)
    return new Response(null,{status:304,headers});
  if (object.range && range) {
    const offset = object.range.offset || 0;
    const len = object.range.length || object.size;
    headers.set("Content-Range","bytes " + offset + "-" + (offset+len-1) + "/" + object.size);
    return new Response(object.body,{status:206,headers});
  }
  return new Response(object.body,{headers});
}
// Verify the complete adaptive HLS graph before exposing a processed episode.
async function verifyHlsPackage(env, prefix) {
  const manifest = await env.MEDIA.get(prefix + "/master.m3u8");
  if (!manifest) return "HLS master playlist missing";
  if (manifest.size > 1024 * 1024) return "HLS master playlist too large";
  const master = await manifest.text();
  if (!master.startsWith("#EXTM3U")) return "Invalid HLS master header";
  const children = master.split(/\r?\n/).map(v => v.trim()).filter(v => v && !v.startsWith("#"));
  if (!children.length || children.length > 8) return "Invalid HLS rendition count";
  for (const path of children) {
    if (!fileOK(path) || !path.endsWith(".m3u8")) return "Unsafe HLS rendition path";
    const playlist = await env.MEDIA.get(prefix + "/" + path);
    if (!playlist || playlist.size > 1024 * 1024) return "HLS rendition missing or too large";
    const text = await playlist.text();
    if (!text.startsWith("#EXTM3U") || !text.includes("#EXT-X-ENDLIST")) return "Incomplete HLS rendition";
    const segments = text.split(/\r?\n/).map(v => v.trim()).filter(v => v && !v.startsWith("#"));
    if (!segments.length || segments.length > 20000) return "Invalid HLS segment count";
    const folder = path.includes("/") ? path.slice(0, path.lastIndexOf("/") + 1) : "";
    for (const segment of segments) {
      const local = folder + segment;
      if (!fileOK(segment) || !fileOK(local) || local.endsWith(".m3u8")) return "Unsafe HLS segment path";
      if (!(await env.MEDIA.head(prefix + "/" + local))) return "Missing HLS segment";
    }
  }
  return null;
}
export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    const path = url.pathname;
    try {
      if (request.method === "GET" && path === "/health")
        return reply({status:"ok",service:"addisreel-catalog",phase:6,features:["multipart-mp4","processor-hls","studio-ops","processor-job-recovery","catalog-etag"]});
      // Liveness alone cannot prove the D1 job migration was applied.
      // Readiness is deliberately sanitized: no media keys, owner data or secrets.
      if (request.method === "GET" && path === "/health/ready") {
        if (!env.DB || !env.MEDIA) return reply({status:"degraded",service:"addisreel-catalog"},503);
        try {
          await env.DB.prepare("SELECT id FROM video_processing_jobs LIMIT 1").first();
          await env.DB.prepare("SELECT id FROM series LIMIT 1").first();
        } catch {
          return reply({status:"degraded",service:"addisreel-catalog"},503);
        }
        return reply({status:"ok",service:"addisreel-catalog",phase:6,checks:["catalog-db","processing-jobs","media-binding"]});
      }
      if (path.startsWith("/v1/auth/")) return handleAuth(request,env,url);
      if (request.method === "GET" && path === "/admin/password-client.js")
        return new Response("window.addisreelPassword={newSalt:"+newSalt.toString()+",deriveProof:"+deriveProof.toString()+"};\n",{
          headers:{"Content-Type":"application/javascript; charset=utf-8",
            "Cache-Control":"no-store","X-Content-Type-Options":"nosniff"}
        });
      if (request.method === "GET" && path === "/admin/creators.js")
        return new Response(creatorClient,{headers:{"Content-Type":"application/javascript; charset=utf-8","Cache-Control":"no-store","X-Content-Type-Options":"nosniff"}});
      if (request.method === "GET" && path === "/admin/creators") {
        const access=await requireAdmin(request,env,url);
        if(access.error) return Response.redirect(url.origin+"/admin",302);
        return new Response(creatorHtml,{headers:{"Content-Type":"text/html; charset=utf-8","Cache-Control":"no-store","X-Content-Type-Options":"nosniff","X-Frame-Options":"DENY","Referrer-Policy":"no-referrer","Content-Security-Policy":"default-src 'none'; style-src 'unsafe-inline'; script-src 'self'; connect-src 'self'; img-src 'self' data:; form-action 'self'; object-src 'none'; frame-ancestors 'none'; base-uri 'none'"}});
      }
      if (request.method === "GET" && path === "/admin/client.js")
        return new Response(clientScript,{headers:{"Content-Type":"application/javascript; charset=utf-8",
          "Cache-Control":"no-store","X-Content-Type-Options":"nosniff"}});
      if (request.method === "GET" && path === "/admin/manifest.webmanifest")
        return new Response(manifest,{headers:{"Content-Type":"application/manifest+json; charset=utf-8",
          "Cache-Control":"public, max-age=300","X-Content-Type-Options":"nosniff"}});
      if (request.method === "GET" && path === "/admin/sw.js")
        return new Response(serviceWorker,{headers:{"Content-Type":"application/javascript; charset=utf-8",
          "Cache-Control":"no-store","Service-Worker-Allowed":"/admin",
          "X-Content-Type-Options":"nosniff"}});
      if (request.method === "GET" && (path === "/admin/icon-192.png" || path === "/admin/icon-512.png")) {
        const encoded = path.endsWith("192.png") ? icon192Base64 : icon512Base64;
        return new Response(Uint8Array.from(atob(encoded), c => c.charCodeAt(0)),{
          headers:{"Content-Type":"image/png","Cache-Control":"public, max-age=86400","X-Content-Type-Options":"nosniff"}
        });
      }
      if (request.method === "GET" && path === "/admin/importer.js")
        return new Response("window.parseMovieFields = " + parseMovieFields.toString() + ";\n", {
          headers: {
            "Content-Type": "application/javascript; charset=utf-8",
            "Cache-Control": "no-store",
            "X-Content-Type-Options": "nosniff"
          }
        });
      if (request.method === "GET" && path === "/admin")
        return new Response(studioHtml,{headers:{
          "Content-Type":"text/html; charset=utf-8","Cache-Control":"no-store",
          "X-Content-Type-Options":"nosniff","X-Frame-Options":"DENY",
          "Referrer-Policy":"no-referrer",
          "Content-Security-Policy":"default-src 'none'; style-src 'unsafe-inline'; script-src 'self'; connect-src 'self'; img-src 'self' data:; manifest-src 'self'; worker-src 'self'; form-action 'self'; object-src 'none'; frame-ancestors 'none'; base-uri 'none'"
        }});
      if (request.method === "OPTIONS" && path.startsWith("/v1/") && !path.startsWith("/v1/admin/"))
        return new Response(null,{status:204,headers:{...publicHeaders,"Access-Control-Allow-Methods":"GET, OPTIONS"}});
      if (request.method === "GET" && path === "/v1/catalog")
        return publicCatalogResponse(request,env);
      const episodesMatch = /^\/v1\/series\/([a-z0-9-]+)\/episodes$/.exec(path);
      if (request.method === "GET" && episodesMatch) {
        const id = episodesMatch[1];
        const series = await env.DB.prepare("SELECT id FROM series WHERE id=? AND status='published' AND licensed=1").bind(id).first();
        if (!series) return fail("Series unavailable",404);
        const rows = await env.DB.prepare(
          "SELECT number,title,duration_seconds FROM episodes WHERE series_id=? AND status='published' AND licensed=1 AND media_prefix IS NOT NULL ORDER BY number"
        ).bind(id).all();
        return reply({seriesId:id,episodes:rows.results});
      }
      const play = /^\/v1\/play\/([a-z0-9-]+)\/([1-9][0-9]*)$/.exec(path);
      if (request.method === "GET" && play) {
        const e = await playableEpisode(env,play[1],Number(play[2]));
        if (!e) return fail("Episode unavailable",404);
        const base = url.origin + "/v1/media/" + e.series_id + "/" + e.number + "/";
        const hls = await env.MEDIA.head(e.media_prefix + "/master.m3u8");
        const mp4 = hls ? null : await env.MEDIA.head(e.media_prefix + "/source.mp4");
        if (!hls && !mp4) return fail("Episode media is not ready",404);
        return reply({seriesId:e.series_id,episode:e.number,title:e.title,
          playbackUrl:base + (hls ? "master.m3u8" : "source.mp4"),
          format:hls ? "hls" : "mp4"});
      }
      const media = /^\/v1\/media\/([a-z0-9-]+)\/([1-9][0-9]*)\/(.+)$/.exec(path);
      if ((request.method === "GET" || request.method === "HEAD") && media) {
        const e=await playableEpisode(env,media[1],Number(media[2]));
        let file;
        try { file=decodeURIComponent(media[3]); } catch { return fail("Invalid media filename"); }
        if (!e || !fileOK(file)) return fail("Media unavailable",404);
        if (request.method === "HEAD") {
          const object = await env.MEDIA.head(e.media_prefix + "/" + file);
          if (!object) return fail("Media unavailable",404);
          return new Response(null,{headers:{
            ...publicHeaders,"Content-Type":extType(file),"Content-Length":String(object.size),
            "Cache-Control":file.endsWith(".m3u8") ? "public, max-age=30" : "public, max-age=120",
            "Accept-Ranges":"bytes","ETag":object.httpEtag
          }});
        }
        return serveR2(env,e.media_prefix + "/" + file,file,request);
      }
      const poster = /^\/v1\/posters\/([a-z0-9-]+)$/.exec(path);
      if (request.method === "GET" && poster) {
        const s=await env.DB.prepare("SELECT poster_key FROM series WHERE id=? AND status='published' AND licensed=1")
          .bind(poster[1]).first();
        if (!s || !s.poster_key) return fail("Poster unavailable",404);
        // Only predefined variants are accepted; missing variants fall back to the original.
        const width = new URL(request.url).searchParams.get("w");
        if (width !== null && !["320","640","960"].includes(width)) return fail("Unsupported poster width",400);
        const variantKey = width ? s.poster_key.replace(/\.[^.]+$/, "-" + width + ".webp") : null;
        const started = performance.now();
        let variant = variantKey ? await env.MEDIA.get(variantKey) : null;
        // Migrate older published posters on demand. New Studio uploads already
        // supply the three WebP variants, so the Images binding is rarely used.
        // Only published/licensed posters and bounded widths reach this branch.
        if (!variant && variantKey && env.IMAGES) {
          try {
            const source = await env.MEDIA.get(s.poster_key);
            if (source) {
              const resized = (await env.IMAGES.input(source.body)
                .transform({ width: Number(width) })
                .output({ format: "image/webp", quality: 78 })).response();
              if (resized.ok && resized.headers.get("Content-Type")?.startsWith("image/webp")) {
                const optimized = await resized.arrayBuffer();
                if (optimized.byteLength > 0 && optimized.byteLength <= 1024 * 1024) {
                  await env.MEDIA.put(variantKey, optimized, {
                    httpMetadata: { contentType: "image/webp" }
                  });
                  variant = await env.MEDIA.get(variantKey);
                }
              }
            }
          } catch (error) {
            console.warn("Legacy poster optimization unavailable; serving original", error);
          }
        }
        const object = variant || await env.MEDIA.get(s.poster_key);
        const storageMs = Math.max(0,performance.now()-started).toFixed(1);
        if (!object) return fail("Poster unavailable",404);
        const etag = object.httpEtag;
        const headers = new Headers({...publicHeaders,
          "Content-Type": variant ? "image/webp" : (object.httpMetadata?.contentType || "image/jpeg"),
          "Cache-Control":"public, max-age=3600, stale-while-revalidate=86400",
          "ETag":etag, "X-Poster-Variant":variant ? "webp-" + width : "original",
          "Content-Length":String(object.size), "Server-Timing":"r2;dur=" + storageMs,
          "Timing-Allow-Origin":"*"});
        if (request.headers.get("If-None-Match") === etag) return new Response(null,{status:304,headers});
        return new Response(object.body,{headers});
      }
      // Separate machine-to-machine access. Never accept an owner browser cookie here.
      if (path.startsWith("/v1/processor/")) {
        const secret = env.PROCESSOR_TOKEN;
        const bearer = request.headers.get("Authorization") || "";
        if (typeof secret !== "string" || secret.length < 32 ||
            bearer !== "Bearer " + secret) return fail("Unauthorized processor",401,false);
        if (request.method === "POST" && path === "/v1/processor/claim") {
          // Atomic D1 update prevents two processors from claiming the same job.
          const claimed = await env.DB.prepare(
            "UPDATE video_processing_jobs SET status='processing',progress=0,updated_at=datetime('now') " +
            "WHERE id=(SELECT id FROM video_processing_jobs WHERE status='queued' ORDER BY created_at,id LIMIT 1) " +
            "AND status='queued' RETURNING id,series_id,episode_number,source_key,status,progress"
          ).first();
          return reply({job:claimed || null},200,false);
        }
        // Workers cannot execute FFmpeg; the external runner downloads and
        // uploads through short, authenticated, same-worker streams.
        const source = /^\/v1\/processor\/jobs\/([a-f0-9-]{36})\/source$/.exec(path);
        if (request.method === "GET" && source) {
          const job = await env.DB.prepare(
            "SELECT source_key FROM video_processing_jobs WHERE id=? AND status='processing'"
          ).bind(source[1]).first();
          if (!job) return fail("Job is not processing",409,false);
          const object = await env.MEDIA.get(job.source_key);
          if (!object) return fail("Source MP4 missing",404,false);
          return new Response(object.body,{headers:{
            "Content-Type":"video/mp4","Content-Length":String(object.size),
            "Cache-Control":"no-store","X-Content-Type-Options":"nosniff"
          }});
        }
        const output = /^\/v1\/processor\/jobs\/([a-f0-9-]{36})\/files\/(.+)$/.exec(path);
        if (request.method === "PUT" && output) {
          let file;
          try { file = decodeURIComponent(output[2]); }
          catch { return fail("Invalid output path",400,false); }
          if (!fileOK(file) || file === "source.mp4")
            return fail("Invalid processed HLS path",400,false);
          const expected = request.headers.get("X-Upload-SHA256");
          if (!uploadSha256(expected)) return fail("SHA-256 required",400,false);
          const job = await env.DB.prepare(
            "SELECT source_key FROM video_processing_jobs WHERE id=? AND status='processing'"
          ).bind(output[1]).first();
          if (!job) return fail("Job is not processing",409,false);
          if (Number(request.headers.get("Content-Length") || 0) > 16*1024*1024)
            return fail("HLS file too large",413,false);
          const bytes = await request.arrayBuffer();
          if (!bytes.byteLength || bytes.byteLength > 16*1024*1024)
            return fail("HLS file must be 1-16MB",413,false);
          if ((await digestUpload(bytes)) !== expected)
            return fail("Upload checksum mismatch",422,false);
          const prefix = job.source_key.slice(0,-"/source.mp4".length);
          await env.MEDIA.put(prefix + "/" + file, bytes, {
            httpMetadata:{contentType:extType(file)},customMetadata:{sha256:expected}
          });
          return reply({uploaded:true,bytes:bytes.byteLength,sha256:expected},200,false);
        }
        const update = /^\/v1\/processor\/jobs\/([a-f0-9-]{36})$/.exec(path);
        if (request.method === "PATCH" && update) {
          const d = await inputJSON(request);
          if (!["processing","ready","failed"].includes(d.status) ||
              !Number.isInteger(d.progress) || d.progress<0 || d.progress>100 ||
              (d.status==="ready" && d.progress!==100) ||
              (d.status==="failed" && (typeof d.error !== "string" || d.error.length>300)))
            return fail("Invalid job state",400,false);
          if (d.status === "ready") {
            const job = await env.DB.prepare(
              "SELECT source_key FROM video_processing_jobs WHERE id=? AND status='processing'"
            ).bind(update[1]).first();
            if (!job) return fail("Job is not processing",409,false);
            const prefix = job.source_key.slice(0,-"/source.mp4".length);
            const invalid = await verifyHlsPackage(env,prefix);
            if (invalid) return fail("Cannot mark ready: " + invalid,409,false);
          }
          const changed = await env.DB.prepare(
            "UPDATE video_processing_jobs SET status=?,progress=?,error_message=?,updated_at=datetime('now') " +
            "WHERE id=? AND status='processing'"
          ).bind(d.status,d.progress,d.status==="failed"?d.error:null,update[1]).run();
          if (!changed.meta?.changes) return fail("Job is not processing",409,false);
          return reply({updated:true},200,false);
        }
        return fail("Processor route not found",404,false);
      }
      if (path.startsWith("/v1/admin/")) {
        const access=await requireAdmin(request,env,url);
        if(access.error) return access.error;
        if (path==="/v1/admin/creators" || path.startsWith("/v1/admin/creators/"))
          return handleCreatorAdmin(request,env,url);
        // R2 multipart is for draft MP4 sources only. Each authenticated request
        // transports at most 8 MiB, respecting the Worker request-size limit.
        const multipart = /^\/v1\/admin\/multipart\/([a-z0-9-]+)\/([1-9][0-9]*)$/.exec(path);
        if (multipart) {
          const episode = await env.DB.prepare(
            "SELECT media_prefix,status FROM episodes WHERE series_id=? AND number=?"
          ).bind(multipart[1],Number(multipart[2])).first();
          if (!episode || episode.status !== "draft" || !episode.media_prefix)
            return fail("Create a draft episode before multipart upload",409,false);
          const key = episode.media_prefix + "/source.mp4";
          const action = url.searchParams.get("action");
          const uploadId = url.searchParams.get("uploadId");
          const maxSize = 512*1024*1024, partSize = 8*1024*1024;
          if (request.method === "POST" && action === "create") {
            const d = await inputJSON(request);
            if (!Number.isSafeInteger(d.size) || d.size<=16*1024*1024 || d.size>maxSize)
              return fail("MP4 must be larger than 16 MB and at most 512 MB",413,false);
            const session = await env.MEDIA.createMultipartUpload(key, {
              httpMetadata:{contentType:"video/mp4"}
            });
            return reply({uploadId:session.uploadId,partSize,parts:Math.ceil(d.size/partSize)},201,false);
          }
          if (!uploadId || uploadId.length<8 || uploadId.length>200 ||
              !/^[A-Za-z0-9_=-]+$/.test(uploadId))
            return fail("Invalid multipart upload ID",400,false);
          const session = env.MEDIA.resumeMultipartUpload(key,uploadId);
          if (request.method === "PUT" && action === "part") {
            const rawPart = url.searchParams.get("part"), rawSize = url.searchParams.get("size");
            const part = Number(rawPart), size = Number(rawSize);
            if (!/^[1-9][0-9]*$/.test(rawPart || "") ||
                !/^[1-9][0-9]*$/.test(rawSize || "") ||
                !Number.isSafeInteger(size) || size<=16*1024*1024 || size>maxSize ||
                part<1 || part>Math.ceil(size/partSize))
              return fail("Invalid multipart part",400,false);
            const expectedSize = part===Math.ceil(size/partSize) ?
              size-(part-1)*partSize : partSize;
            if (Number(request.headers.get("Content-Length") || 0)>partSize)
              return fail("Multipart part exceeds 8 MB",413,false);
            const digest = request.headers.get("X-Upload-SHA256");
            if (!uploadSha256(digest)) return fail("Part SHA-256 required",400,false);
            const bytes = await request.arrayBuffer();
            if (bytes.byteLength!==expectedSize) return fail("Unexpected multipart part size",422,false);
            if ((await digestUpload(bytes))!==digest) return fail("Multipart checksum mismatch",422,false);
            try {
              const stored = await session.uploadPart(part,bytes);
              return reply({partNumber:stored.partNumber,etag:stored.etag,bytes:bytes.byteLength},200,false);
            } catch(error) {
              return fail("Could not save multipart part; retry",409,false);
            }
          }
          if (request.method === "POST" && action === "complete") {
            const d = await inputJSON(request);
            const count = Math.ceil(d.size/partSize);
            if (d.uploadId!==uploadId || !Number.isSafeInteger(d.size) ||
                d.size<=16*1024*1024 || d.size>maxSize || !Array.isArray(d.parts) ||
                d.parts.length!==count ||
                !d.parts.every((v,i)=>v && v.partNumber===i+1 &&
                  typeof v.etag==="string" && v.etag.length>4 && v.etag.length<256))
              return fail("Incomplete multipart manifest",400,false);
            try {
              const object = await session.complete(d.parts);
              if (object.size!==d.size) {
                await env.MEDIA.delete(key);
                return fail("Completed MP4 size mismatch",422,false);
              }
              return reply({uploaded:true,bytes:object.size,key},200,false);
            } catch(error) {
              return fail("Could not complete MP4; retry or restart",409,false);
            }
          }
          if (request.method === "DELETE" && action === "abort") {
            await session.abort();
            return reply({aborted:true},200,false);
          }
          return fail("Multipart route not found",404,false);
        }
        if (request.method === "GET" && path === "/v1/admin/processing-jobs") {
          const rows = await env.DB.prepare(
            "SELECT id,series_id,episode_number,status,progress,error_message,created_at,updated_at, " +
            "CASE WHEN status='processing' AND updated_at < datetime('now','-120 minutes') THEN 1 ELSE 0 END AS stalled " +
            "FROM video_processing_jobs ORDER BY created_at DESC LIMIT 100"
          ).all();
          return reply({jobs:rows.results},200,false);
        }
        const retryJob = /^\/v1\/admin\/processing-jobs\/([a-f0-9-]{36})\/retry$/.exec(path);
        if (request.method === "POST" && retryJob) {
          const job = await env.DB.prepare(
            "SELECT j.source_key,j.status,e.status AS episode_status FROM video_processing_jobs j " +
            "JOIN episodes e ON e.series_id=j.series_id AND e.number=j.episode_number WHERE j.id=?"
          ).bind(retryJob[1]).first();
          if (!job) return fail("Processing job not found",404,false);
          if (job.episode_status !== "draft")
            return fail("Only draft episodes may be processed",409,false);
          if (!await env.MEDIA.head(job.source_key))
            return fail("Original MP4 no longer exists",409,false);
          // A 120-minute inactive lease is eligible for recovery. Active work
          // cannot be interrupted or re-queued by another owner session.
          const updated = await env.DB.prepare(
            "UPDATE video_processing_jobs SET status='queued',progress=0,error_message=NULL, " +
            "updated_at=datetime('now') WHERE id=? AND " +
            "(status='failed' OR (status='processing' AND updated_at < datetime('now','-120 minutes')))"
          ).bind(retryJob[1]).run();
          if (!updated.meta?.changes)
            return fail("Only failed or stalled processing jobs can be retried",409,false);
          return reply({jobId:retryJob[1],status:"queued",requeued:true},200,false);
        }
        if (request.method === "POST" && path === "/v1/admin/processing-jobs") {
          const d = await inputJSON(request);
          if (!idOK(d.seriesId) || !Number.isInteger(d.episode) || d.episode < 1)
            return fail("Invalid episode",400,false);
          const e = await env.DB.prepare(
            "SELECT media_prefix,status FROM episodes WHERE series_id=? AND number=?"
          ).bind(d.seriesId,d.episode).first();
          if (!e || e.status !== "draft") return fail("Create a draft episode first",409,false);
          const source = e.media_prefix + "/source.mp4";
          if (!await env.MEDIA.head(source)) return fail("Upload source.mp4 first",409,false);
          const jobId = crypto.randomUUID();
          // One active conversion per episode; an atomic INSERT avoids
          // duplicate work if the owner taps Queue twice on a slow network.
          const inserted = await env.DB.prepare(
            "INSERT INTO video_processing_jobs(id,series_id,episode_number,source_key,status) " +
            "SELECT ?,?,?,?,'queued' WHERE NOT EXISTS (" +
            "SELECT 1 FROM video_processing_jobs WHERE series_id=? AND episode_number=? " +
            "AND status IN ('queued','processing')) RETURNING id"
          ).bind(jobId,d.seriesId,d.episode,source,d.seriesId,d.episode).first();
          if (!inserted) {
            const existing = await env.DB.prepare(
              "SELECT id,status FROM video_processing_jobs WHERE series_id=? AND episode_number=? " +
              "AND status IN ('queued','processing') ORDER BY created_at DESC LIMIT 1"
            ).bind(d.seriesId,d.episode).first();
            return reply({jobId:existing?.id || null,status:existing?.status || "queued",
              message:"This episode already has an active processing job"},200,false);
          }
          return reply({jobId,status:"queued",message:"Job registered; waiting for an external FFmpeg processor"},202,false);
        }
        if (request.method === "GET" && path === "/v1/admin/series") {
          const s=await env.DB.prepare("SELECT * FROM series ORDER BY sort_order,created_at DESC").all();
          const e=await env.DB.prepare("SELECT * FROM episodes ORDER BY series_id,number").all();
          return reply({series:s.results,episodes:e.results},200,false);
        }
        if (request.method === "POST" && path === "/v1/admin/series") {
          const d=await inputJSON(request);
          if (!idOK(d.id) || !textField(d.title,150) || !textField(d.genre,80) ||
              !textField(d.description,2000,false) || !["draft","published"].includes(d.status) ||
              !Number.isInteger(d.sortOrder || 0) || Math.abs(d.sortOrder || 0) > 100000)
            return fail("Invalid series fields",400,false);
          await env.DB.prepare(
            "INSERT INTO series(id,title,genre,description,status,licensed,sort_order) VALUES(?,?,?,?,?,?,?) " +
            "ON CONFLICT(id) DO UPDATE SET title=excluded.title,genre=excluded.genre,description=excluded.description," +
            "status=excluded.status,licensed=excluded.licensed,sort_order=excluded.sort_order,updated_at=datetime('now')"
          ).bind(d.id,d.title,d.genre,d.description||"",d.status,d.licensed===true?1:0,d.sortOrder||0).run();
          return reply({saved:true,id:d.id},200,false);
        }
        if (request.method === "POST" && path === "/v1/admin/episodes") {
          const d=await inputJSON(request);
          if (!idOK(d.seriesId) || !Number.isInteger(d.number) || d.number < 1 || d.number > 10000 ||
              !textField(d.title,150) || !["draft","published"].includes(d.status) ||
              !Number.isInteger(d.durationSeconds || 0) || (d.durationSeconds || 0) < 0)
            return fail("Invalid episode fields",400,false);
          const parent=await env.DB.prepare("SELECT id FROM series WHERE id=?").bind(d.seriesId).first();
          if (!parent) return fail("Series not found",404,false);
          const prefix="series/" + d.seriesId + "/episodes/" + d.number;
          if (d.status === "published" && d.licensed === true) {
            const manifest = await env.MEDIA.get(prefix + "/master.m3u8");
            const mp4 = manifest ? null : await env.MEDIA.head(prefix + "/source.mp4");
            if (!manifest && !mp4)
              return fail("Upload master.m3u8 or source.mp4 before publishing this episode",409,false);
            if (manifest) {
              // Do not publish partial HLS uploads: check each referenced child
              // playlist and its local segment files exist in R2.
              const master = await manifest.text();
              const playlists = master.split(/\\r?\\n/).map(v=>v.trim())
                .filter(v=>v && !v.startsWith("#"));
              if (!playlists.length || playlists.length > 8)
                return fail("Invalid HLS master playlist",409,false);
              for (const playlist of playlists) {
                if (!fileOK(playlist) || !playlist.endsWith(".m3u8"))
                  return fail("Invalid HLS rendition playlist",409,false);
                const child = await env.MEDIA.get(prefix + "/" + playlist);
                if (!child) return fail("Missing HLS rendition: " + playlist,409,false);
                const segments = (await child.text()).split(/\\r?\\n/)
                  .map(v=>v.trim()).filter(v=>v && !v.startsWith("#"));
                if (!segments.length || segments.length > 20000)
                  return fail("Invalid HLS segments list",409,false);
                const base = playlist.includes("/") ? playlist.slice(0,playlist.lastIndexOf("/")+1) : "";
                // Check the first and final segment at minimum; a background
                // integrity scan is still required for full verification.
                for (const segment of [segments[0], segments[segments.length-1]]) {
                  const key = base + segment;
                  if (!fileOK(key) || !await env.MEDIA.head(prefix + "/" + key))
                    return fail("Missing HLS media segment",409,false);
                }
              }
            }
          }
          await env.DB.prepare(
            "INSERT INTO episodes(series_id,number,title,media_prefix,duration_seconds,status,licensed) VALUES(?,?,?,?,?,?,?) " +
            "ON CONFLICT(series_id,number) DO UPDATE SET title=excluded.title,media_prefix=excluded.media_prefix," +
            "duration_seconds=excluded.duration_seconds,status=excluded.status,licensed=excluded.licensed,updated_at=datetime('now')"
          ).bind(d.seriesId,d.number,d.title,prefix,d.durationSeconds||0,d.status,d.licensed===true?1:0).run();
          return reply({saved:true,mediaPrefix:prefix},200,false);
        }
        const upPoster=/^\/v1\/admin\/posters\/([a-z0-9-]+)$/.exec(path);
        if (request.method === "PUT" && upPoster) {
          const series=await env.DB.prepare("SELECT id FROM series WHERE id=?").bind(upPoster[1]).first();
          if (!series) return fail("Unknown series",404,false);
          const types={"image/jpeg":".jpg","image/png":".png","image/webp":".webp"};
          const mime=request.headers.get("Content-Type")?.split(";")[0];
          if (!types[mime]) return fail("Unsupported poster type",415,false);
          const bytes=await request.arrayBuffer();
          if (!bytes.byteLength || bytes.byteLength > 5*1024*1024) return fail("Poster must be 1-5MB",413,false);
          // A unique original key ensures old optimized variants cannot be reused
        // after a new poster upload; clients can still request the original URL.
        const key="posters/" + upPoster[1] + "-" + crypto.randomUUID() + types[mime];
          await env.MEDIA.put(key,bytes,{httpMetadata:{contentType:mime}});
          await env.DB.prepare("UPDATE series SET poster_key=?,updated_at=datetime('now') WHERE id=?").bind(key,upPoster[1]).run();
          return reply({uploaded:true,key},200,false);
        }
        // Variants share the current original poster key, preventing stale variants
        // after a replacement upload. Each is validated before storage.
        const upVariant=/^\/v1\/admin\/posters\/([a-z0-9-]+)\/variants\/(320|640|960)$/.exec(path);
        if (request.method === "PUT" && upVariant) {
          if (request.headers.get("Content-Type")?.split(";")[0] !== "image/webp") return fail("WebP required",415,false);
          const series=await env.DB.prepare("SELECT poster_key FROM series WHERE id=?").bind(upVariant[1]).first();
          if (!series?.poster_key) return fail("Upload original poster first",409,false);
          const bytes=await request.arrayBuffer();
          if (!bytes.byteLength || bytes.byteLength > 1024*1024) return fail("Poster variant exceeds 1MB",413,false);
          const key=series.poster_key.replace(/\.[^.]+$/, "-" + upVariant[2] + ".webp");
          await env.MEDIA.put(key,bytes,{httpMetadata:{contentType:"image/webp"}});
          return reply({uploaded:true,width:Number(upVariant[2]),bytes:bytes.byteLength},200,false);
        }
        // Server-confirmed file-level resumption. A client can skip only a file
        // whose byte length AND verified SHA-256 match its local selected copy.
        // Older objects with no hash metadata are intentionally re-uploaded.
        const fileStatus=/^\/v1\/admin\/hls\/([a-z0-9-]+)\/([1-9][0-9]*)\/(.+)\/status$/.exec(path);
        if (request.method === "GET" && fileStatus) {
          let file;
          try { file=decodeURIComponent(fileStatus[3]); } catch { return fail("Invalid filename",400,false); }
          const checksum=url.searchParams.get("sha256"), size=Number(url.searchParams.get("size"));
          if (!fileOK(file) || !uploadSha256(checksum) ||
              !Number.isSafeInteger(size) || size<1 || size>16*1024*1024 ||
              !/^([1-9][0-9]*)$/.test(url.searchParams.get("size") || ""))
            return fail("Invalid upload verification",400,false);
          const e=await env.DB.prepare("SELECT media_prefix FROM episodes WHERE series_id=? AND number=?")
            .bind(fileStatus[1],Number(fileStatus[2])).first();
          if (!e) return fail("Create episode before media upload",404,false);
          const object=await env.MEDIA.head(e.media_prefix+"/"+file);
          const uploaded=!!object && object.size===size && object.customMetadata?.sha256===checksum;
          return reply({uploaded,bytes:uploaded?size:0},200,false);
        }
        const upHls=/^\/v1\/admin\/hls\/([a-z0-9-]+)\/([1-9][0-9]*)\/(.+)$/.exec(path);
        if (request.method === "PUT" && upHls) {
          let file;
          try { file=decodeURIComponent(upHls[3]); } catch { return fail("Invalid filename",400,false); }
          if (!fileOK(file)) return fail("Invalid HLS filename",400,false);
          const expected=request.headers.get("X-Upload-SHA256");
          if (expected !== null && !uploadSha256(expected))
            return fail("Invalid SHA-256 header",400,false);
          const e=await env.DB.prepare("SELECT media_prefix FROM episodes WHERE series_id=? AND number=?")
            .bind(upHls[1],Number(upHls[2])).first();
          if (!e) return fail("Create episode before media upload",404,false);
          const bytes=await request.arrayBuffer();
          if (!bytes.byteLength || bytes.byteLength > 16*1024*1024) return fail("HLS file must be 1-16MB",413,false);
          let checksum;
          if (expected !== null) {
            checksum=await digestUpload(bytes);
            if (checksum !== expected) return fail("Upload checksum mismatch",422,false);
          }
          await env.MEDIA.put(e.media_prefix+"/"+file,bytes,{
            httpMetadata:{contentType:extType(file)},
            ...(checksum ? {customMetadata:{sha256:checksum}} : {})
          });
          return reply({uploaded:true,key:e.media_prefix+"/"+file,bytes:bytes.byteLength,
            ...(checksum ? {sha256:checksum} : {})},200,false);
        }
        return fail("Admin route not found",404,false);
      }
      return fail("Not found",404);
    } catch (error) {
      console.error("AddisReel catalog request failed",error);
      return fail("Service temporarily unavailable",500,path.startsWith("/v1/admin/")?false:true);
    }
  }
};

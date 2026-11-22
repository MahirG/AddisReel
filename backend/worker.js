import { studioHtml } from "./studio.js";
import { clientScript } from "./client.js";
import { wizardScript } from "./wizard.js";
import { handleMultipartUpload } from "./multipart.js";
import { queueOptimization, optimizationStatus, handleAdminOptimization, handleProcessor } from "./processor-api.js";
import { manifest, serviceWorker } from "./pwa.js";
import { icon192Base64, icon512Base64 } from "./icons.js";
import { requireAdmin, handleAuth } from "./auth.js";
import { newSalt, deriveProof } from "./password-client.js";
import { parseMovieFields } from "./importer.js";

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
  if (object.range && range) {
    const offset = object.range.offset || 0;
    const len = object.range.length || object.size;
    headers.set("Content-Range","bytes " + offset + "-" + (offset+len-1) + "/" + object.size);
    return new Response(object.body,{status:206,headers});
  }
  return new Response(object.body,{headers});
}
export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    const path = url.pathname;
    try {
      if (request.method === "GET" && path === "/health")
        return reply({status:"ok",service:"addisreel-catalog",phase:3});
      if (path.startsWith("/v1/auth/")) return handleAuth(request,env,url);
      if (path.startsWith("/v1/processor/")) return handleProcessor(request,env,url);
      if (request.method === "GET" && path === "/admin/password-client.js")
        return new Response("window.addisreelPassword={newSalt:"+newSalt.toString()+",deriveProof:"+deriveProof.toString()+"};\n",{
          headers:{"Content-Type":"application/javascript; charset=utf-8",
            "Cache-Control":"no-store","X-Content-Type-Options":"nosniff"}
        });
      if (request.method === "GET" && path === "/admin/client.js")
        return new Response(clientScript,{headers:{"Content-Type":"application/javascript; charset=utf-8",
          "Cache-Control":"no-store","X-Content-Type-Options":"nosniff"}});
      if (request.method === "GET" && path === "/admin/wizard.js")
        return new Response(wizardScript,{headers:{"Content-Type":"application/javascript; charset=utf-8",
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
          "Content-Security-Policy":"default-src 'none'; style-src 'unsafe-inline'; script-src 'self'; connect-src 'self'; img-src 'self' data: blob:; media-src 'self' blob:; manifest-src 'self'; worker-src 'self'; form-action 'self'; object-src 'none'; frame-ancestors 'none'; base-uri 'none'"
        }});
      if (request.method === "OPTIONS" && path.startsWith("/v1/") && !path.startsWith("/v1/admin/"))
        return new Response(null,{status:204,headers:{...publicHeaders,"Access-Control-Allow-Methods":"GET, OPTIONS"}});
      if (request.method === "GET" && path === "/v1/catalog")
        return reply({series:await publicSeries(env)});
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
        const prefix = "/v1/media/" + e.series_id + "/" + e.number + "/";
        // Existing HLS stays supported. New simple MP4 uploads play directly on Android.
        const optimized = await optimizationStatus(env,e.series_id,e.number);
        const hasHls = await env.MEDIA.head(e.media_prefix + "/master.m3u8");
        const hasOptimized = !hasHls && optimized?.status==="ready" && !!await env.MEDIA.head(e.media_prefix+"/optimized.mp4");
        const hasMp4 = (hasOptimized||hasHls) ? false : await env.MEDIA.head(e.media_prefix + "/video.mp4");
        if (!hasOptimized && !hasHls && !hasMp4) return fail("Video not ready",404);
        const format = hasHls ? "hls" : "mp4";
        const file = hasOptimized ? "optimized.mp4" : hasHls ? "master.m3u8" : "video.mp4";
        return reply({seriesId:e.series_id,episode:e.number,title:e.title,
          playbackUrl:url.origin + prefix + file,format,optimized:hasOptimized});
      }
      const media = /^\/v1\/media\/([a-z0-9-]+)\/([1-9][0-9]*)\/(.+)$/.exec(path);
      if (request.method === "GET" && media) {
        const e=await playableEpisode(env,media[1],Number(media[2]));
        let file;
        try { file=decodeURIComponent(media[3]); } catch { return fail("Invalid media filename"); }
        if (!e || !fileOK(file)) return fail("Media unavailable",404);
        if(file==="optimized.mp4") {
          const job=await optimizationStatus(env,media[1],Number(media[2]));
          if(job?.status!=="ready")return fail("Optimized video not verified",404);
        }
        return serveR2(env,e.media_prefix + "/" + file,file,request);
      }
      const poster = /^\/v1\/posters\/([a-z0-9-]+)$/.exec(path);
      if (request.method === "GET" && poster) {
        const s=await env.DB.prepare("SELECT poster_key FROM series WHERE id=? AND status='published' AND licensed=1")
          .bind(poster[1]).first();
        if (!s || !s.poster_key) return fail("Poster unavailable",404);
        const object=await env.MEDIA.get(s.poster_key);
        if (!object) return fail("Poster unavailable",404);
        return new Response(object.body,{headers:{...publicHeaders,"Content-Type":object.httpMetadata?.contentType || "image/jpeg",
          "Cache-Control":"public, max-age=300","X-Content-Type-Options":"nosniff"}});
      }
      if (path.startsWith("/v1/admin/")) {
        const access=await requireAdmin(request,env,url);
        if(access.error) return access.error;
        if(path.startsWith("/v1/admin/optimization/"))
          return handleAdminOptimization(request,env,url);
        const multipartPath=/^\/v1\/admin\/uploads\/([a-z0-9-]+)\/([1-9][0-9]*)$/.exec(path);
        if(multipartPath) return handleMultipartUpload(request,env,url,multipartPath[1],Number(multipartPath[2]));
        // Admin artwork and draft video previews are private even when the series is unpublished.
        const privatePoster = /^\/v1\/admin\/posters\/([a-z0-9-]+)$/.exec(path);
        if (request.method === "GET" && privatePoster) {
          const row = await env.DB.prepare("SELECT poster_key FROM series WHERE id=?").bind(privatePoster[1]).first();
          if (!row?.poster_key) return fail("Cover not uploaded",404,false);
          const object = await env.MEDIA.get(row.poster_key);
          if (!object) return fail("Cover not found in storage",404,false);
          return new Response(object.body,{headers:{"Content-Type":object.httpMetadata?.contentType || "image/jpeg",
            "Cache-Control":"private, no-store","X-Content-Type-Options":"nosniff"}});
        }
        const preview = /^\/v1\/admin\/preview\/([a-z0-9-]+)\/([1-9][0-9]*)$/.exec(path);
        if (request.method === "GET" && preview) {
          const e=await env.DB.prepare("SELECT media_prefix FROM episodes WHERE series_id=? AND number=?")
            .bind(preview[1],Number(preview[2])).first();
          if (!e) return fail("Episode not found",404,false);
          const key=e.media_prefix+"/video.mp4";
          const item=await env.MEDIA.head(key);
          if (!item) return fail("No MP4 uploaded yet",404,false);
          const response=await serveR2(env,key,"video.mp4",request);
          response.headers.set("Cache-Control","private, no-store");
          response.headers.delete("Access-Control-Allow-Origin");
          return response;
        }
        const mediaStatus = /^\/v1\/admin\/media-status\/([a-z0-9-]+)\/([1-9][0-9]*)$/.exec(path);
        if (request.method === "GET" && mediaStatus) {
          const e=await env.DB.prepare("SELECT media_prefix FROM episodes WHERE series_id=? AND number=?")
            .bind(mediaStatus[1],Number(mediaStatus[2])).first();
          if (!e) return fail("Episode not found",404,false);
          const mp4=await env.MEDIA.head(e.media_prefix+"/video.mp4");
          const hls=await env.MEDIA.head(e.media_prefix+"/master.m3u8");
          const job=await optimizationStatus(env,mediaStatus[1],Number(mediaStatus[2]));
          const optimized = job?.status==="ready" && !!await env.MEDIA.head(e.media_prefix+"/optimized.mp4");
          return reply({ready:!!(mp4||hls),format:optimized?"mp4":hls?"hls":mp4?"mp4":null,
            sizeBytes:mp4?.size??null,optimizationStatus:job?.status||null,
            optimizedBytes:job?.optimized_bytes??null},200,false);
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
          if (d.status === "published" && d.licensed === true &&
              !await env.MEDIA.head(prefix + "/master.m3u8") &&
              !await env.MEDIA.head(prefix + "/video.mp4"))
            return fail("Upload a playable video before publishing this episode",409,false);
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
          const key="posters/" + upPoster[1] + types[mime];
          await env.MEDIA.put(key,bytes,{httpMetadata:{contentType:mime}});
          await env.DB.prepare("UPDATE series SET poster_key=?,updated_at=datetime('now') WHERE id=?").bind(key,upPoster[1]).run();
          return reply({uploaded:true,key},200,false);
        }
        const directMp4 = /^\/v1\/admin\/mp4\/([a-z0-9-]+)\/([1-9][0-9]*)$/.exec(path);
        if (request.method === "PUT" && directMp4) {
          const mime=request.headers.get("Content-Type")?.split(";")[0]?.toLowerCase();
          if (mime!=="video/mp4" && mime!=="application/octet-stream")
            return fail("Please choose an MP4 video",415,false);
          // Keep below the standard Workers request-body limit. Larger files require
          // direct-to-storage resumable uploads or Cloudflare Stream (not enabled yet).
          const size=Number(request.headers.get("Content-Length") || request.headers.get("X-Upload-Size"));
          if (!Number.isSafeInteger(size) || size<1 || size>90*1024*1024)
            return fail("Choose an MP4 under 90 MB. Large-video processing is not configured yet.",413,false);
          const e=await env.DB.prepare("SELECT media_prefix,status,licensed FROM episodes WHERE series_id=? AND number=?")
            .bind(directMp4[1],Number(directMp4[2])).first();
          if (!e) return fail("Save your episode details before uploading video",404,false);
          if(e.status==="published" && Number(e.licensed)===1)
            return fail("Unpublish the episode before replacing its live video",409,false);
          const key=e.media_prefix+"/video.mp4";
          const object=await env.MEDIA.put(key,request.body,{httpMetadata:{contentType:"video/mp4"}});
          if(object.size>90*1024*1024) {await env.MEDIA.delete(key);return fail("Video exceeds the 90 MB upload limit",413,false);}
          await queueOptimization(env,directMp4[1],Number(directMp4[2]),object.size);
          return reply({uploaded:true,bytes:object.size,format:"mp4",optimization:"queued"},200,false);
        }
        const upHls=/^\/v1\/admin\/hls\/([a-z0-9-]+)\/([1-9][0-9]*)\/(.+)$/.exec(path);
        if (request.method === "PUT" && upHls) {
          let file;
          try { file=decodeURIComponent(upHls[3]); } catch { return fail("Invalid filename",400,false); }
          if (!fileOK(file)) return fail("Invalid HLS filename",400,false);
          const e=await env.DB.prepare("SELECT media_prefix,status,licensed FROM episodes WHERE series_id=? AND number=?")
            .bind(upHls[1],Number(upHls[2])).first();
          if (!e) return fail("Create episode before media upload",404,false);
          if(e.status==="published" && Number(e.licensed)===1)
            return fail("Unpublish the episode before replacing its live video",409,false);
          const bytes=await request.arrayBuffer();
          if (!bytes.byteLength || bytes.byteLength > 16*1024*1024) return fail("HLS file must be 1-16MB",413,false);
          await env.MEDIA.put(e.media_prefix+"/"+file,bytes,{httpMetadata:{contentType:extType(file)}});
          return reply({uploaded:true,key:e.media_prefix+"/"+file},200,false);
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

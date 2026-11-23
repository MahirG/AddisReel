import { studioHtml } from "./studio.js";
import { clientScript } from "./client.js";
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
      if (request.method === "GET" && path === "/admin/password-client.js")
        return new Response("window.addisreelPassword={newSalt:"+newSalt.toString()+",deriveProof:"+deriveProof.toString()+"};\n",{
          headers:{"Content-Type":"application/javascript; charset=utf-8",
            "Cache-Control":"no-store","X-Content-Type-Options":"nosniff"}
        });
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
        const base = url.origin + "/v1/media/" + e.series_id + "/" + e.number + "/";
        const hls = await env.MEDIA.head(e.media_prefix + "/master.m3u8");
        const mp4 = hls ? null : await env.MEDIA.head(e.media_prefix + "/source.mp4");
        if (!hls && !mp4) return fail("Episode media is not ready",404);
        return reply({seriesId:e.series_id,episode:e.number,title:e.title,
          playbackUrl:base + (hls ? "master.m3u8" : "source.mp4"),
          format:hls ? "hls" : "mp4"});
      }
      const media = /^\/v1\/media\/([a-z0-9-]+)\/([1-9][0-9]*)\/(.+)$/.exec(path);
      if (request.method === "GET" && media) {
        const e=await playableEpisode(env,media[1],Number(media[2]));
        let file;
        try { file=decodeURIComponent(media[3]); } catch { return fail("Invalid media filename"); }
        if (!e || !fileOK(file)) return fail("Media unavailable",404);
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
          "Cache-Control":"public, max-age=86400","X-Content-Type-Options":"nosniff"}});
      }
      if (path.startsWith("/v1/admin/")) {
        const access=await requireAdmin(request,env,url);
        if(access.error) return access.error;
        if (request.method === "GET" && path === "/v1/admin/processing-jobs") {
          const rows = await env.DB.prepare(
            "SELECT id,series_id,episode_number,status,progress,error_message,created_at,updated_at " +
            "FROM video_processing_jobs ORDER BY created_at DESC LIMIT 100"
          ).all();
          return reply({jobs:rows.results},200,false);
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
          await env.DB.prepare(
            "INSERT INTO video_processing_jobs(id,series_id,episode_number,source_key,status) " +
            "VALUES(?,?,?,?,'queued')"
          ).bind(jobId,d.seriesId,d.episode,source).run();
          return reply({jobId,status:"queued",message:"Job registered; external processor not connected"},202,false);
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
          const key="posters/" + upPoster[1] + types[mime];
          await env.MEDIA.put(key,bytes,{httpMetadata:{contentType:mime}});
          await env.DB.prepare("UPDATE series SET poster_key=?,updated_at=datetime('now') WHERE id=?").bind(key,upPoster[1]).run();
          return reply({uploaded:true,key},200,false);
        }
        const upHls=/^\/v1\/admin\/hls\/([a-z0-9-]+)\/([1-9][0-9]*)\/(.+)$/.exec(path);
        if (request.method === "PUT" && upHls) {
          let file;
          try { file=decodeURIComponent(upHls[3]); } catch { return fail("Invalid filename",400,false); }
          if (!fileOK(file)) return fail("Invalid HLS filename",400,false);
          const e=await env.DB.prepare("SELECT media_prefix FROM episodes WHERE series_id=? AND number=?")
            .bind(upHls[1],Number(upHls[2])).first();
          if (!e) return fail("Create episode before media upload",404,false);
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

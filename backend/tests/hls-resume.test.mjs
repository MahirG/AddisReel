import test from "node:test";
import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import worker from "../worker.js";

const root="https://addisreel-catalog.example.test";
const authCookie="__Host-addisreel_session="+ "a".repeat(48);
const prefix="series/test-show/episodes/1";
const bytes=new TextEncoder().encode("#EXTM3U\n#EXT-X-VERSION:3\n");
const sum=createHash("sha256").update(bytes).digest("hex");
const path="/v1/admin/hls/test-show/1/master.m3u8";
const url=path+"/status?size="+bytes.byteLength+"&sha256="+sum;

function fixtures() {
  const objects=new Map();
  const db={
    prepare(sql) {
      return {
        bind(...args) {
          return {
            async first() {
              if(sql.includes("FROM admin_sessions s JOIN"))return {id:1,email:"owner@example.test",expires_at:Date.now()/1000+600};
              if(sql.includes("FROM episodes WHERE series_id"))return {media_prefix:prefix};
              return null;
            }
          };
        }
      };
    }
  };
  const media={
    async head(key) { return objects.get(key)||null; },
    async put(key,source,options) {
      objects.set(key,{size:source.byteLength,customMetadata:options.customMetadata||{},body:source});
    }
  };
  const env={DB:db,MEDIA:media};
  function req(route,options={}) {
    return new Request(root+route,{
      ...options,headers:{
        Cookie:authCookie,
        ...(options.method&&options.method!=="GET"?{Origin:root}:{}),
        ...options.headers
      }
    });
  }
  return {env,objects,req};
}

test("authenticated HEAD-style verification rejects missing files and invalid hashes",async()=>{
  const {env,req}=fixtures();
  const missing=await worker.fetch(req(url),env);
  assert.equal(missing.status,200);
  assert.deepEqual(await missing.json(),{uploaded:false,bytes:0});
  for(const invalid of [
    path+"/status?size=-1&sha256="+sum,
    path+"/status?size=abc&sha256="+sum,
    path+"/status?size=25&sha256=short",
    "/v1/admin/hls/test-show/1/../master.m3u8/status?size=25&sha256="+sum
  ]) {
    const response=await worker.fetch(req(invalid),env);
    assert.ok(response.status>=400,invalid);
  }
});

test("SHA-256 verified HLS upload can be safely skipped on next session",async()=>{
  const {env,objects,req}=fixtures();
  const saved=await worker.fetch(req(path,{
    method:"PUT",headers:{"Content-Type":"application/vnd.apple.mpegurl","X-Upload-SHA256":sum},body:bytes
  }),env);
  assert.equal(saved.status,200);
  assert.deepEqual(await saved.json(),{uploaded:true,key:prefix+"/master.m3u8",bytes:bytes.byteLength,sha256:sum});
  const verified=await worker.fetch(req(url),env);
  assert.deepEqual(await verified.json(),{uploaded:true,bytes:bytes.byteLength});
  const mismatch=await worker.fetch(req(path+"/status?size="+bytes.byteLength+"&sha256="+"a".repeat(64)),env);
  assert.deepEqual(await mismatch.json(),{uploaded:false,bytes:0});
  const smaller=await worker.fetch(req(path+"/status?size=1&sha256="+sum),env);
  assert.deepEqual(await smaller.json(),{uploaded:false,bytes:0});
  assert.equal(objects.get(prefix+"/master.m3u8").customMetadata.sha256,sum);
});

test("checksum mismatch cannot replace an existing video file",async()=>{
  const {env,objects,req}=fixtures();
  const bad=await worker.fetch(req(path,{
    method:"PUT",headers:{"X-Upload-SHA256":"f".repeat(64)},body:bytes
  }),env);
  assert.equal(bad.status,422);
  assert.equal(objects.size,0);
  const wrongFormat=await worker.fetch(req(path,{
    method:"PUT",headers:{"X-Upload-SHA256":"not-a-sha256"},body:bytes
  }),env);
  assert.equal(wrongFormat.status,400);
  assert.equal(objects.size,0);
});

test("legacy HLS objects without SHA-256 metadata are not falsely marked resumed",async()=>{
  const {env,req}=fixtures();
  const old=await worker.fetch(req(path,{method:"PUT",body:bytes}),env);
  assert.equal(old.status,200);
  assert.equal((await old.json()).uploaded,true);
  const status=await worker.fetch(req(url),env);
  assert.deepEqual(await status.json(),{uploaded:false,bytes:0});
});

test("upload status stays protected from signed-out clients",async()=>{
  const {env}=fixtures();
  const res=await worker.fetch(new Request(root+url),env);
  assert.equal(res.status,401);
});

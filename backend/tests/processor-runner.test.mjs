import test from "node:test";
import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import worker from "../worker.js";

const root = "https://addisreel-catalog.example.test";
const jobId = "11111111-1111-4111-8111-111111111111";
const prefix = "series/demo/episodes/1";
const token = "test-processor-token-12345678901234567890";
const auth = {Authorization:"Bearer "+token};
const req = (path, options={}) => new Request(root+path,options);
const bytes = t => new TextEncoder().encode(t);

function fixture() {
  let status = "processing";
  const store = new Map([[prefix+"/source.mp4",bytes("original licensed media source") ]]);
  const db = {prepare(sql) {
    return {bind(...values) {
      return {
        async first() {
          if (sql.includes("SELECT source_key") && status==="processing")
            return {source_key:prefix+"/source.mp4"};
          return null;
        },
        async run() {if(sql.includes("UPDATE video_processing_jobs") && status==="processing") {
          status=values[0];return {meta:{changes:1}};
        }return {meta:{changes:0}};}
      };
    }};
  }};
  const media = {
    async get(key) { const value=store.get(key);return value?{
      size:value.byteLength, body:value, async text(){return new TextDecoder().decode(value);}
    }:null; },
    async head(key) {const value=store.get(key);return value?{size:value.byteLength}:null;},
    async put(key, value) {store.set(key,new Uint8Array(value));}
  };
  return {env:{DB:db,MEDIA:media,PROCESSOR_TOKEN:token},store,getStatus:()=>status};
}

test("processor source access is private and restricted to active jobs",async()=>{
  const {env}=fixture();
  assert.equal((await worker.fetch(req("/v1/processor/jobs/"+jobId+"/source"),env)).status,401);
  const result=await worker.fetch(req("/v1/processor/jobs/"+jobId+"/source",{headers:auth}),env);
  assert.equal(result.status,200);
  assert.match(await result.text(),/original licensed/);
  assert.equal(result.headers.get("cache-control"),"no-store");
});

test("processor output rejects invalid checksums before writing to private R2",async()=>{
  const {env,store}=fixture();
  const path="/v1/processor/jobs/"+jobId+"/files/v480/index.m3u8";
  const result=await worker.fetch(req(path,{method:"PUT",headers:{...auth,"X-Upload-SHA256":"0".repeat(64)},body:"#EXTM3U"}),env);
  assert.equal(result.status,422);
  assert.equal(store.has(prefix+"/v480/index.m3u8"),false);
});

test("processor output enforces content checksum, path safety and source preservation",async()=>{
  const {env,store}=fixture(),body="#EXTM3U\n#EXT-X-ENDLIST\n";
  const sha=createHash("sha256").update(body).digest("hex");
  const path="/v1/processor/jobs/"+jobId+"/files/v480/index.m3u8";
  const uploaded=await worker.fetch(req(path,{method:"PUT",headers:{...auth,"X-Upload-SHA256":sha},body}),env);
  assert.equal(uploaded.status,200);
  assert.equal((await uploaded.json()).sha256,sha);
  assert.equal(store.has(prefix+"/v480/index.m3u8"),true);
  const malicious=await worker.fetch(req("/v1/processor/jobs/"+jobId+"/files/source.mp4",{
    method:"PUT",headers:{...auth,"X-Upload-SHA256":sha},body}),env);
  assert.equal(malicious.status,400);
});

test("ready state requires complete HLS master, variants and segments",async()=>{
  const {env,store,getStatus}=fixture();
  const patch=()=>worker.fetch(req("/v1/processor/jobs/"+jobId,{
    method:"PATCH",headers:{...auth,"Content-Type":"application/json"},
    body:JSON.stringify({status:"ready",progress:100})}),env);
  assert.equal((await patch()).status,409);
  store.set(prefix+"/master.m3u8",bytes("#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=700000\nv480/index.m3u8\n"));
  assert.equal((await patch()).status,409);
  store.set(prefix+"/v480/index.m3u8",bytes("#EXTM3U\n#EXTINF:4,\nsegment_00001.ts\n#EXT-X-ENDLIST\n"));
  assert.equal((await patch()).status,409);
  store.set(prefix+"/v480/segment_00001.ts",bytes("media-segment"));
  assert.equal((await patch()).status,200);
  assert.equal(getStatus(),"ready");
});

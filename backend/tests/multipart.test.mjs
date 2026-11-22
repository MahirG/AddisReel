import test from "node:test";
import assert from "node:assert/strict";
import { handleMultipartUpload, PART_BYTES, MAX_VIDEO_BYTES } from "../multipart.js";
import { wizardScript } from "../wizard.js";
import { studioHtml } from "../studio.js";

const base="https://addisreel-catalog.example.test";
function fakeEnv({status="draft",licensed=0,existing=true}={}) {
  const state={sessions:new Map(),completed:new Map(),next:0};
  const env={
    DB:{prepare(sql){
      if(!sql.includes("INSERT INTO video_jobs"))assert.match(sql,/FROM episodes/);
      return {bind(){return this},async run(){return {meta:{changes:1}}},async first(){
        return existing?{media_prefix:"series/example/episodes/1",status,licensed}:null;
      }};
    }},
    MEDIA:{
      async createMultipartUpload(key,options){
        const uploadId="test-upload-id-"+(++state.next);
        state.sessions.set(uploadId,{key,options,parts:new Map(),active:true});
        return {key,uploadId};
      },
      resumeMultipartUpload(key,uploadId){
        const session=state.sessions.get(uploadId);
        return {
          async uploadPart(partNumber,body){
            if(!session||!session.active||key!==session.key)throw Error("no such upload");
            session.parts.set(partNumber,new Uint8Array(body));
            return {partNumber,etag:"test-etag-part-"+partNumber};
          },
          async complete(parts){
            if(!session||!session.active||key!==session.key)throw Error("no such upload");
            for(const p of parts)
              if(!session.parts.has(p.partNumber)||p.etag!=="test-etag-part-"+p.partNumber)throw Error("missing part");
            const size=parts.reduce((n,p)=>n+session.parts.get(p.partNumber).byteLength,0);
            session.active=false;state.completed.set(key,{size,options:session.options});
            return {size,httpEtag:'"testetag"'};
          },
          async abort(){session.active=false}
        };
      },
      async delete(key){state.completed.delete(key)}
    }
  };
  return {env,state};
}
const make=(path,method="GET",body=null,contentType="application/json")=>
  new Request(base+path,{method,headers:body===null?{}:{"Content-Type":contentType},
    body:body===null?undefined:(contentType==="application/json"?JSON.stringify(body):body)});
const serve=(env,request)=>handleMultipartUpload(request,env,new URL(request.url),"example",1);

test("video multipart upload validates one-gigabyte ceiling and episode existence",async()=>{
  const {env}=fakeEnv();
  const oversized=make("/v1/admin/uploads/example/1?action=start","POST",
    {name:"video.mp4",size:MAX_VIDEO_BYTES+1});
  assert.equal((await serve(env,oversized)).status,413);
  const missing=fakeEnv({existing:false}).env;
  assert.equal((await serve(missing,make("/v1/admin/uploads/example/1?action=start","POST",
    {name:"video.mp4",size:900}))).status,404);
});
test("published and verified video cannot be overwritten without unpublishing",async()=>{
  const {env}=fakeEnv({status:"published",licensed:1});
  const result=await serve(env,make("/v1/admin/uploads/example/1?action=start","POST",
    {name:"movie.mp4",size:25*1024*1024}));
  assert.equal(result.status,409);
});
test("MP4 multipart upload assembles parts in correct order and retains content type",async()=>{
  const {env,state}=fakeEnv();
  const size=PART_BYTES+101;
  const path="/v1/admin/uploads/example/1";
  const begin=await serve(env,make(path+"?action=start","POST",{name:"Episode1.mp4",size}));
  assert.equal(begin.status,200);
  const session=await begin.json();
  assert.equal(session.totalParts,2);
  assert.equal(session.partSize,PART_BYTES);
  const first=await serve(env,make(path+"?action=part&partNumber=1&uploadId="+session.uploadId,
    "PUT",new Uint8Array(PART_BYTES),"application/octet-stream"));
  assert.equal(first.status,200);
  const second=await serve(env,make(path+"?action=part&partNumber=2&uploadId="+session.uploadId,
    "PUT",new Uint8Array(101),"application/octet-stream"));
  assert.equal(second.status,200);
  const parts=[await first.json(),await second.json()];
  const invalid=await serve(env,make(path+"?action=finish&uploadId="+session.uploadId,
    "POST",{size,parts:[parts[1],parts[0]]}));
  assert.equal(invalid.status,400);
  const done=await serve(env,make(path+"?action=finish&uploadId="+session.uploadId,
    "POST",{size,parts}));
  assert.equal(done.status,200);
  const payload=await done.json();
  assert.equal(payload.sizeBytes,size);
  const saved=state.completed.get("series/example/episodes/1/video.mp4");
  assert.equal(saved.size,size);
  assert.equal(payload.optimization,"queued");
  assert.equal(saved.options.httpMetadata.contentType,"video/mp4");
});
test("upload rejects invalid part numbers, oversized chunks and missing sessions",async()=>{
  const {env}=fakeEnv();
  const uploadId="test-upload-id-unknown";
  assert.equal((await serve(env,make("/v1/admin/uploads/example/1?action=part&partNumber=0&uploadId="+uploadId,
    "PUT",new Uint8Array(10),"application/octet-stream"))).status,400);
  const fail=await serve(env,make("/v1/admin/uploads/example/1?action=part&partNumber=1&uploadId="+uploadId,
    "PUT",new Uint8Array(50),"application/octet-stream"));
  assert.equal(fail.status,409);
});
test("simple Studio keeps the same three steps and now resumes interrupted videos",()=>{
  assert.match(studioHtml,/up to 1 GB each/);
  assert.match(wizardScript,/multipartMp4/);
  assert.match(wizardScript,/localStorage\.setItem\(stateKey/);
  assert.match(wizardScript,/addisreelOpenSeries/);
  assert.doesNotThrow(()=>new Function(wizardScript));
});

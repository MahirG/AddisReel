import test from "node:test";
import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import worker from "../worker.js";

const root="https://addisreel-catalog.example.test";
const cookie="__Host-addisreel_session="+"a".repeat(48);
const route="/v1/admin/multipart/demo/1";
const sourceKey="series/demo/episodes/1/source.mp4";
const partSize=8*1024*1024;

function fixture() {
  const uploads=new Map(),objects=new Map();
  let serial=0,episodeStatus="draft";
  const db={prepare(sql){return {bind(){return {async first(){
    if(sql.includes("FROM admin_sessions s JOIN"))return {id:1,email:"owner@example.test",expires_at:Date.now()/1000+60};
    if(sql.includes("FROM episodes WHERE series_id"))return {media_prefix:"series/demo/episodes/1",status:episodeStatus};
    return null;
  }}}}}};
  const media={
    async createMultipartUpload(key) {
      const uploadId="upload-session-"+(++serial),upload={key,parts:new Map()};
      uploads.set(uploadId,upload);
      return {uploadId,key};
    },
    resumeMultipartUpload(key,id) {
      const item=uploads.get(id);
      if(!item||item.key!==key)throw Error("No such upload");
      return {
        async uploadPart(partNumber,data) {
          item.parts.set(partNumber,new Uint8Array(data));
          return {partNumber,etag:'etag-test-'+partNumber};
        },
        async complete(parts) {
          const all=parts.map(p=>item.parts.get(p.partNumber));
          if(all.some(x=>!x))throw Error("Incomplete parts");
          const size=all.reduce((n,b)=>n+b.byteLength,0);
          objects.set(key,{size});
          uploads.delete(id);
          return {size};
        },
        async abort() {uploads.delete(id);}
      };
    },
    async delete(key){objects.delete(key);}
  };
  function request(path,opts={}) {
    return new Request(root+path,{...opts,headers:{
      Cookie:cookie,...(opts.method&&opts.method!=="GET"?{Origin:root}:{}),...opts.headers
    }});
  }
  return {env:{DB:db,MEDIA:media},request,objects,uploads,setStatus:v=>episodeStatus=v};
}

test("multipart MP4 sessions require owner authentication and draft episodes",async()=>{
  const f=fixture();
  const unsigned=await worker.fetch(new Request(root+route+"?action=create",{
    method:"POST",headers:{Origin:root,"Content-Type":"application/json"},body:JSON.stringify({size:20*1024*1024})
  }),f.env);
  assert.equal(unsigned.status,401);
  f.setStatus("published");
  const published=await worker.fetch(f.request(route+"?action=create",{
    method:"POST",headers:{"Content-Type":"application/json"},body:JSON.stringify({size:20*1024*1024})
  }),f.env);
  assert.equal(published.status,409);
});

test("multipart upload verifies every part and completes an exact-size MP4",async()=>{
  const f=fixture(),size=20*1024*1024;
  const created=await worker.fetch(f.request(route+"?action=create",{
    method:"POST",headers:{"Content-Type":"application/json"},body:JSON.stringify({size})
  }),f.env);
  assert.equal(created.status,201);
  const session=await created.json();
  assert.equal(session.partSize,partSize);
  assert.equal(session.parts,3);
  const saved=[];
  for(let part=1;part<=3;part++) {
    const length=part===3?4*1024*1024:partSize;
    const data=new Uint8Array(length).fill(part);
    const checksum=createHash("sha256").update(data).digest("hex");
    const endpoint=route+"?action=part&uploadId="+session.uploadId+"&part="+part+"&size="+size;
    const response=await worker.fetch(f.request(endpoint,{
      method:"PUT",headers:{"X-Upload-SHA256":checksum},body:data
    }),f.env);
    assert.equal(response.status,200);
    saved.push(await response.json());
  }
  const complete=await worker.fetch(f.request(route+"?action=complete&uploadId="+session.uploadId,{
    method:"POST",headers:{"Content-Type":"application/json"},
    body:JSON.stringify({uploadId:session.uploadId,size,parts:saved})
  }),f.env);
  assert.equal(complete.status,200);
  assert.equal((await complete.json()).bytes,size);
  assert.equal(f.objects.get(sourceKey).size,size);
});

test("multipart upload rejects wrong part length and altered checksums",async()=>{
  const f=fixture(),size=20*1024*1024;
  const opened=await worker.fetch(f.request(route+"?action=create",{
    method:"POST",headers:{"Content-Type":"application/json"},body:JSON.stringify({size})
  }),f.env);
  const {uploadId}=await opened.json();
  const short=new Uint8Array(100),sha=createHash("sha256").update(short).digest("hex");
  const url=route+"?action=part&uploadId="+uploadId+"&part=1&size="+size;
  assert.equal((await worker.fetch(f.request(url,{
    method:"PUT",headers:{"X-Upload-SHA256":sha},body:short
  }),f.env)).status,422);
  assert.equal((await worker.fetch(f.request(url,{
    method:"PUT",headers:{"X-Upload-SHA256":"a".repeat(64)},body:new Uint8Array(partSize)
  }),f.env)).status,422);
  assert.equal(f.objects.has(sourceKey),false);
});

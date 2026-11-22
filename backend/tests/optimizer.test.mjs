import test from "node:test";
import assert from "node:assert/strict";
import { webcrypto } from "node:crypto";
import { queueOptimization, optimizationStatus, handleAdminOptimization, handleProcessor } from "../processor-api.js";
import worker from "../worker.js";
if(!globalThis.crypto)globalThis.crypto=webcrypto;
const url="https://example.test";
function mock(){
  const log=[];
  const state={secret:null,lastSeen:null};
  const DB={prepare(sql){
    let values=[];
    return {
      bind(...args){values=args;return this;},
      async run(){
        log.push({sql,values});
        if(sql.includes("INSERT INTO processor_access"))state.secret=values[0];
        return {meta:{changes:1}};
      },
      async first(){
        if(sql.includes("SELECT token_hash"))return state.secret?{token_hash:state.secret}:null;
        if(sql.includes("SELECT last_seen_at"))return {last_seen_at:null};
        if(sql.includes("SELECT status,optimized_bytes"))return {status:"ready",optimized_bytes:400};
        if(sql.includes("JOIN episodes"))return null;
        return null;
      },
      async all(){return {results:[]}}
    };
  }};
  return {DB,log,state,MEDIA:{async head(){return {size:400}}}};
}
test("queueOptimization saves only identifiers and size",async()=>{
  const env=mock();
  await queueOptimization(env,"example",1,1048576);
  assert.equal(env.log.length,1);
  assert.match(env.log[0].sql,/INSERT INTO video_jobs/);
  assert.deepEqual(env.log[0].values,["example",1,1048576]);
  await assert.rejects(queueOptimization(env,"../bad",1,200));
});
test("optimizationStatus preserves completed rendition status",async()=>{
  const row=await optimizationStatus(mock(),"example",1);
  assert.equal(row.status,"ready");
});
test("scoped processor pairing shows credential only once and rejects anonymous processors",async()=>{
  const env=mock();
  const anonymous=await handleProcessor(new Request(url+"/v1/processor/claim",{method:"POST"}),env,
    new URL(url+"/v1/processor/claim"));
  assert.equal(anonymous.status,401);
  const paired=await handleAdminOptimization(new Request(url+"/v1/admin/optimization/pair",{method:"POST"}),
    env,new URL(url+"/v1/admin/optimization/pair"));
  assert.equal(paired.status,200);
  const credential=(await paired.json()).token;
  assert.match(credential,/^[A-Za-z0-9_-]{40,}$/);
  assert.notEqual(credential,env.state.secret);
  const request=new Request(url+"/v1/processor/claim",{method:"POST",headers:{Authorization:"Bearer "+credential}});
  const claimed=await handleProcessor(request,env,new URL(request.url));
  assert.equal(claimed.status,200);
  assert.equal((await claimed.json()).job,null);
  const result=await handleAdminOptimization(new Request(url+"/v1/admin/optimization/jobs"),
    env,new URL(url+"/v1/admin/optimization/jobs"));
  const payload=await result.json();
  assert.equal(JSON.stringify(payload).includes(credential),false);
});
test("public playback prefers verified optimized rendition, with originals preserved",async()=>{
  const env=mock();
  env.MEDIA.head=async key=>key.endsWith("/optimized.mp4")?{size:400}:null;
  env.DB.prepare=(sql)=>{
    return {bind(){return this},async first(){
      if(sql.includes("video_jobs"))return {status:"ready",optimized_bytes:400};
      return {series_id:"example",number:1,title:"Pilot",media_prefix:"series/example/episodes/1"};
    }};
  };
  const request=new Request(url+"/v1/play/example/1");
  const res=await worker.fetch(request,env);
  assert.equal(res.status,200);
  const data=await res.json();
  assert.equal(data.optimized,true);
  assert.match(data.playbackUrl,/optimized\.mp4$/);
});

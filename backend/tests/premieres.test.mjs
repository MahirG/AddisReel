import test from "node:test";
import assert from "node:assert/strict";
import {webcrypto} from "node:crypto";
import worker from "../worker.js";
import {
 validUtc,validateScheduleRequest,premiereGate,episodeGate,
 handlePremiereAdmin,publicPremieres,canStageScheduledRelease
} from "../premieres.js";
import {premiereHtml} from "../premiere-page.js";
import {premiereClient} from "../premiere-client.js";
import {publicPremiereHtml} from "../premiere-public-page.js";
import {publicPremiereClient} from "../premiere-public-client.js";
if(!globalThis.crypto)globalThis.crypto=webcrypto;
const origin="https://addisreel-catalog.example.test";
const future=hours=>new Date(Date.now()+hours*3600_000).toISOString().replace(/\.\d{3}Z$/,"Z");
const req=(path,method="GET",body)=>new Request(origin+path,{method,
 ...(body?{headers:{"Content-Type":"application/json","Origin":origin},body:JSON.stringify(body)}:{})});
function d1(){
 const state={series:new Map([["film-1",{id:"film-1",title:"New Ethiopian Film",status:"draft",licensed:0}]]),
   eps:new Map([["film-1:1",{status:"draft",licensed:0}]]),
   premieres:new Map(),episodes:new Map(),events:[],authorized:true};
 return {state,env:{DB:{
  prepare(sql){
   let args=[];return {
    bind(...values){args=values;return this},
    async first(){
     if(sql.startsWith("SELECT id,title,status,licensed FROM series WHERE id=?"))return state.series.get(args[0])||null;
     if(sql.includes("SELECT state,premiere_at AS release_at FROM premiere_schedules"))return state.premieres.get(args[0])||null;
     if(sql.includes("SELECT state,release_at FROM episode_release_schedules"))return state.episodes.get(args[0]+":"+args[1])||null;
     if(sql.includes("SELECT status,licensed FROM episodes"))return state.eps.get(args[0]+":"+args[1])||null;
     if(sql.includes("SELECT premiere_at,state FROM premiere_schedules"))return state.premieres.get(args[0])||null;
     if(sql.includes(" AS allowed FROM series s WHERE s.id=?"))return {allowed:state.authorized?1:0};
     throw Error("Unrecognized first query: "+sql);
    },
    async all(){
     if(sql.includes("FROM premiere_schedules p JOIN series s"))return {results:[...state.premieres.entries()].map(([id,x])=>({series_id:id,...x}))};
     if(sql.includes("FROM episode_release_schedules er JOIN episodes e"))return {results:[...state.episodes.entries()].map(([id,x])=>({series_id:id.split(":")[0],episode_number:1,...x}))};
     if(sql.includes("FROM premiere_schedule_events"))return {results:state.events.filter(x=>x.series_id===args[0])};
     if(sql.includes("FROM premiere_schedules p JOIN series s ON s.id=p.series_id"))return {results:[]};
     throw Error("Unrecognized all query: "+sql);
    },
    async run(){
     if(sql.startsWith("INSERT INTO premiere_schedules")){
       state.premieres.set(args[0],{release_at:args[1],premiere_at:args[1],state:args[2]});
     } else if(sql.startsWith("UPDATE premiere_schedules")){
       state.premieres.set(args[2],{release_at:args[1],premiere_at:args[1],state:args[0]});
     } else if(sql.startsWith("INSERT INTO episode_release_schedules")){
       state.episodes.set(args[0]+":"+args[1],{release_at:args[2],state:args[3]});
     } else if(sql.startsWith("UPDATE episode_release_schedules")){
       state.episodes.set(args[2]+":"+args[3],{release_at:args[1],state:args[0]});
     } else if(sql.startsWith("INSERT INTO premiere_schedule_events")){
       state.events.push({series_id:args[1],note:args[args.length-1]});
     } else throw Error("Unrecognized run query: "+sql);
     return {meta:{changes:1}};
    }
   };
  },
  async batch(statements){for(const x of statements)await x.run();return []}
 }}};
}
const run=(request,env)=>handlePremiereAdmin(request,env,new URL(request.url));
test("UTC times reject impossible dates, offsets and malformed timestamps",()=>{
 assert.equal(validUtc("2027-01-02T19:30:00Z"),true);
 for(const s of ["2027-02-30T19:30:00Z","2027-13-04T19:30:00Z","2027-01-02T19:30Z",
  "2027-01-02T19:30:00+03:00","2027-01-02 19:30:00Z","2030-01-01T12:99:00Z"])assert.equal(validUtc(s),false,s);
 assert.ok(validateScheduleRequest({action:"schedule",releaseAt:future(4)}).value);
 assert.match(validateScheduleRequest({action:"schedule",releaseAt:future(-2)}).error,/five minutes/i);
 assert.match(validateScheduleRequest({action:"pause",note:"Urgent takedown"}).error,/before/i);
});
test("schedule status transitions block invalid or unexplained changes",()=>{
 const running={state:"scheduled",release_at:future(20)};
 assert.match(validateScheduleRequest({action:"pause",note:"short"},running).error,/10 characters/);
 assert.equal(validateScheduleRequest({action:"pause",note:"Production delay"},running).value.action,"pause");
 assert.match(validateScheduleRequest({action:"resume"},running).error,/paused/);
 assert.equal(validateScheduleRequest({action:"resume"},{state:"paused",release_at:future(20)}).value.action,"resume");
 assert.match(validateScheduleRequest({action:"schedule",releaseAt:future(20)},{state:"scheduled",release_at:future(-2)}).error,/already live/);
});
test("public SQL release gates are fail-closed for paused/future/canceled plans",()=>{
 const p=premiereGate("s"),e=episodeGate("e");
 assert.match(p,/NOT EXISTS/);
 assert.match(p,/state='scheduled'/);
 assert.match(p,/datetime\('now'\)/);
 assert.match(e,/episode_number=e.number/);
 assert.match(e,/state='scheduled'/);
 assert.throws(()=>premiereGate("s;drop"),/alias/);
 assert.throws(()=>episodeGate("e;drop"),/alias/);
});
test("owner schedules draft film and episode, then pauses and resumes with chronology",async()=>{
 const {state,env}=d1();
 const film="/v1/admin/premieres/film-1";
 const first=future(24),second=future(36);
 let response=await run(req(film,"PUT",{action:"schedule",releaseAt:first}),env);
 assert.equal(response.status,200);assert.equal(state.premieres.get("film-1").state,"scheduled");
 response=await run(req(film+"/episodes/1","PUT",{action:"schedule",releaseAt:future(4)}),env);
 assert.equal(response.status,409);assert.match((await response.json()).error,/precede/);
 response=await run(req(film+"/episodes/1","PUT",{action:"schedule",releaseAt:second}),env);
 assert.equal(response.status,200);assert.equal(state.episodes.get("film-1:1").state,"scheduled");
 response=await run(req(film,"PUT",{action:"pause",note:"Crew needs final approvals"}),env);
 assert.equal(response.status,200);assert.equal(state.premieres.get("film-1").state,"paused");
 response=await run(req(film,"PUT",{action:"resume"}),env);
 assert.equal(response.status,200);assert.equal(state.premieres.get("film-1").state,"scheduled");
 response=await run(req(film,"PUT",{action:"cancel",note:"Exclusive agreement withdrawn"}),env);
 assert.equal(response.status,200);assert.equal(state.premieres.get("film-1").state,"canceled");
 const audit=await run(req(film+"/events"),env);
 assert.equal(audit.status,200);
 assert.ok((await audit.json()).events.length>=4);
});
test("scheduling requires verified rights at the proposed release date",async()=>{
 const {state,env}=d1();state.authorized=false;
 const response=await run(req("/v1/admin/premieres/film-1","PUT",{action:"schedule",releaseAt:future(36)}),env);
 assert.equal(response.status,409);
 assert.equal(state.premieres.size,0);
 assert.equal(await canStageScheduledRelease(env,"film-1"),false);
});
test("legacy published film cannot be pulled from streaming by a new future schedule",async()=>{
 const {state,env}=d1();state.series.get("film-1").status="published";state.series.get("film-1").licensed=1;
 const response=await run(req("/v1/admin/premieres/film-1","PUT",{action:"schedule",releaseAt:future(36)}),env);
 assert.equal(response.status,409);
});
test("public premieres only return explicitly cleared, publishable metadata and clock",async()=>{
 const {env}=d1();
 const r=await publicPremieres(env);
 assert.equal(r.status,200);
 const x=await r.json();assert.ok(x.serverNow);assert.deepEqual(x.premieres,[]);
 assert.equal(r.headers.get("cache-control"),"no-store");
});
test("private admin scheduling routes require owner session and strict request origin",async()=>{
 const env={DB:{prepare(){throw Error("Unauthorized access queried database")}}};
 for(const path of ["/v1/admin/premieres","/v1/admin/premieres/film-1"]){
   const response=await worker.fetch(req(path),env);
   assert.equal(response.status,401,path);
 }
 const page=await worker.fetch(req("/admin/premieres"),env);
 assert.equal(page.status,302);
 assert.equal(page.headers.get("location"),origin+"/admin");
 const forged=await worker.fetch(new Request(origin+"/v1/admin/premieres/film-1",{method:"PUT",headers:{Origin:"https://evil.example.test","Content-Type":"application/json"},body:"{}"}),env);
 assert.equal(forged.status,403);
});
test("premiere calendar and public countdown client code parse and have accessible controls",async()=>{
 assert.doesNotThrow(()=>new Function(premiereClient));
 assert.doesNotThrow(()=>new Function(publicPremiereClient));
 for(const name of ["premiereForm","seriesSelect","episodeForm","episodeNumber","reviewAction","publicCountdown"])
   assert.ok(premiereHtml.includes('id="'+name+'"'),name);
 assert.match(premiereHtml,/aria-live="polite"/);
 assert.match(publicPremiereHtml,/role="status"/);
 const r=await worker.fetch(req("/premieres"),{});
 assert.equal(r.status,200);
 assert.match(await r.text(),/Exclusive Premieres/i);
 const script=await worker.fetch(req("/admin/premieres.js"),{});
 assert.equal(script.status,200);
 assert.match(await script.text(),/episodeSeries/);
});

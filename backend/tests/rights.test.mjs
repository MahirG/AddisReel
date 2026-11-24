import test from "node:test";
import assert from "node:assert/strict";
import {webcrypto} from "node:crypto";
import worker from "../worker.js";
import {validateClaim,validateDecision,rightsPredicate,handleRightsAdmin,hasValidRights} from "../rights.js";
import {rightsHtml} from "../rights-page.js";
import {rightsClient} from "../rights-client.js";
if(!globalThis.crypto)globalThis.crypto=webcrypto;
const root="https://addisreel-catalog.example.test";
const creatorId="f6a3fb08-77ab-4cf9-acbd-2fbc69948aae";
const claim={
  seriesId:"new-ethiopian-drama",creatorId,agreementRef:"AR-EXCL-26-001",
  rightsType:"exclusive_first_premiere",territory:"worldwide",signedOn:"2026-10-01",
  startDate:"2026-11-01",endDate:"2028-12-31",originalUnreleased:true,ownerAttestation:true
};
const request=(path,method="GET",body)=>new Request(root+path,{method,
  ...(body?{headers:{"Content-Type":"application/json","Origin":root},body:JSON.stringify(body)}:{})});
function fixture(){
 const state={rights:new Map(),events:[],objects:new Map(),creatorStatus:"verified"};
 const DB={
  prepare(sql){
   let args=[];return {
    bind(...vals){args=vals;return this},
    async first(){
     if(sql.includes("SELECT id,title FROM series WHERE id=?"))return args[0]===claim.seriesId?{id:claim.seriesId,title:"The Forgotten Heir"}:null;
     if(sql.includes("SELECT id,status FROM creator_profiles WHERE id=?"))return args[0]===creatorId?{id:creatorId,status:state.creatorStatus}:null;
     if(sql.includes("SELECT id FROM title_rights WHERE agreement_ref=?"))
       return [...state.rights.values()].find(x=>x.agreement_ref===args[0])||null;
     if(sql.includes("FROM title_rights r JOIN creator_profiles c")&&sql.includes("WHERE r.id=?")){
       const r=state.rights.get(args[0]);return r?{...r,display_name:"Example Studio",creator_status:state.creatorStatus,current_series_title:"The Forgotten Heir"}:null;
     }
     if(sql.includes("SELECT id FROM title_rights WHERE series_id=?"))return null;
     if(sql.includes(" AS allowed FROM series s WHERE s.id=?"))return {allowed:state.allowed??0};
     throw Error("Unexpected first SQL: "+sql);
    },
    async all(){
     if(sql.includes("FROM title_rights r JOIN creator_profiles c")&&sql.includes("ORDER BY"))
       return {results:[...state.rights.values()]};
     if(sql.includes("FROM grandfathered_rights"))return {results:[]};
     if(sql.includes("FROM title_rights_events"))return {results:state.events.filter(x=>x.rights_id===args[0])};
     throw Error("Unexpected list SQL: "+sql);
    },
    async run(){
     if(sql.startsWith("INSERT INTO title_rights(")){
       const [id,series_id,creator_id,agreement_ref,title_name,rights_type,territory,start_date,end_date,signed_on]=args;
       state.rights.set(id,{id,series_id,creator_id,agreement_ref,title_name,rights_type,territory,start_date,end_date,signed_on,original_unreleased:1,
         status:"pending",evidence_key:null,evidence_sha256:null,evidence_bytes:null});
     }else if(sql.startsWith("INSERT INTO title_rights_events(")){
       if(sql.includes("'submitted'"))state.events.push({rights_id:args[1],event_type:"submitted",to_status:"pending",note:args[2]});
       else if(sql.includes("'document_uploaded'"))state.events.push({rights_id:args[1],event_type:"document_uploaded",to_status:"pending",note:args[2]});
       else state.events.push({rights_id:args[1],event_type:"reviewed",from_status:args[2],to_status:args[3],note:args[4]});
     }else if(sql.startsWith("UPDATE title_rights SET evidence_key")){
       Object.assign(state.rights.get(args[3]),{evidence_key:args[0],evidence_sha256:args[1],evidence_bytes:args[2]});
     }else if(sql.startsWith("UPDATE title_rights SET status=")){
       Object.assign(state.rights.get(args[4]),{status:args[0],checked_chain_of_title:args[1],checked_exclusivity:args[2],review_note:args[3]});
     }else throw Error("Unexpected run SQL: "+sql);
     return {meta:{changes:1}};
    }
   };
  },
  async batch(stmts){for(const stmt of stmts)await stmt.run();return []}
 };
 const MEDIA={
   async put(key,bytes){state.objects.set(key,bytes)},
   async get(key){const bytes=state.objects.get(key);return bytes?{body:new Uint8Array(bytes)}:null},
   async delete(key){state.objects.delete(key)}
 };
 return {state,env:{DB,MEDIA}};
}
async function handler(req,env){return handleRightsAdmin(req,env,new URL(req.url))}
test("exclusive claim requires real dates, verified territory, consent and signed reference",()=>{
 assert.ok(validateClaim(claim).value);
 assert.ok(validateClaim({...claim,territory:"ethiopia"}).error);
 assert.ok(validateClaim({...claim,originalUnreleased:false}).error);
 assert.ok(validateClaim({...claim,ownerAttestation:false}).error);
 assert.ok(validateClaim({...claim,startDate:"2026-02-30"}).error);
 assert.ok(validateClaim({...claim,endDate:"2025-01-01"}).error);
 assert.ok(validateClaim({...claim,signedOn:"2099-01-01"}).error);
 assert.ok(validateClaim({...claim,agreementRef:"../x"}).error);
});
test("publishing predicate gates new titles by live, verified, worldwide exclusive rights",()=>{
 const sql=rightsPredicate("s");
 for(const clause of ["grandfathered_rights","title_rights","creator.status='verified'","rights.status='approved'","rights.start_date<=date('now')","rights.end_date>=date('now')","evidence_key IS NOT NULL"]){
   assert.ok(sql.includes(clause),clause);
 }
 assert.throws(()=>rightsPredicate("s;DROP TABLE series"),/alias/);
});
test("review requires real private evidence and two independent legal checks",()=>{
 const pending={status:"pending",evidence_key:null,evidence_sha256:null};
 assert.ok(validateDecision({status:"approved",note:"Rights evidence checked, but no PDF provided",checkedChainOfTitle:true,checkedExclusivity:true},pending).error);
 const evidence={...pending,evidence_key:"private/contract.pdf",evidence_sha256:"a".repeat(64)};
 assert.ok(validateDecision({status:"approved",note:"Exclusive rights confirmed, but no chain of title",checkedChainOfTitle:false,checkedExclusivity:true},evidence).error);
 assert.equal(validateDecision({status:"approved",note:"Signed contract and full chain of title independently examined",checkedChainOfTitle:true,checkedExclusivity:true},evidence).value.status,"approved");
 assert.ok(validateDecision({status:"approved",note:"Attempt to restore an explicitly revoked license",checkedChainOfTitle:true,checkedExclusivity:true},{...evidence,status:"revoked"}).error);
});
test("owner completes upload, approval, private download, revocation and audit events",async()=>{
 const {state,env}=fixture();
 let res=await handler(request("/v1/admin/rights","POST",claim),env);
 assert.equal(res.status,201);const {id}=await res.json();
 res=await handler(request("/v1/admin/rights","POST",claim),env);assert.equal(res.status,409);
 const reviewPath="/v1/admin/rights/"+id+"/review",pdfPath="/v1/admin/rights/"+id+"/document";
 const decision={status:"approved",note:"Studio owner verified signed exclusive first premiere grant and all production rights",checkedChainOfTitle:true,checkedExclusivity:true};
 res=await handler(request(reviewPath,"PATCH",decision),env);assert.equal(res.status,400);
 res=await handler(new Request(root+pdfPath,{method:"PUT",headers:{"Content-Type":"application/pdf"},body:"NOT A PDF"}),env);assert.equal(res.status,415);
 const pdf=new Blob(["%PDF-1.7\n1 0 obj\n<< /Type /Catalog >>\nendobj\n%%EOF"],{type:"application/pdf"});
 res=await handler(new Request(root+pdfPath,{method:"PUT",body:pdf}),env);assert.equal(res.status,200);
 const uploaded=await res.json();assert.match(uploaded.sha256,/^[a-f0-9]{64}$/);
 assert.ok(state.rights.get(id).evidence_key.startsWith("private/creator-rights/"));
 res=await handler(request(reviewPath,"PATCH",decision),env);assert.equal(res.status,200);
 assert.equal(state.rights.get(id).status,"approved");
 res=await handler(new Request(root+pdfPath),env);assert.equal(res.status,200);
 assert.equal(res.headers.get("cache-control"),"no-store, private");
 assert.match(res.headers.get("content-disposition"),/attachment/);
 res=await handler(new Request(root+"/v1/admin/rights/"+id+"/events"),env);
 assert.equal((await res.json()).events.length,3);
 res=await handler(request(reviewPath,"PATCH",{status:"revoked",note:"Agreement terminated in writing; revoke distribution immediately",checkedChainOfTitle:true,checkedExclusivity:true}),env);
 assert.equal(res.status,200);assert.equal(state.rights.get(id).status,"revoked");
 res=await handler(request(reviewPath,"PATCH",decision),env);assert.equal(res.status,400);
});
test("unverified creator is not permitted to create rights claim",async()=>{
 const {env,state}=fixture();state.creatorStatus="pending";
 const res=await handler(request("/v1/admin/rights","POST",claim),env);
 assert.equal(res.status,409);
});
test("rights approval predicate is checked through D1 for each new title",async()=>{
 const {env,state}=fixture();
 assert.equal(await hasValidRights(env,claim.seriesId),false);
 state.allowed=1;
 assert.equal(await hasValidRights(env,claim.seriesId),true);
});
test("anonymous rights data remains private and cross-origin writes fail",async()=>{
 const env={DB:{prepare(){throw Error("Anonymous access must never query rights")}}};
 const list=await worker.fetch(request("/v1/admin/rights"),env);assert.equal(list.status,401);
 assert.equal(list.headers.get("access-control-allow-origin"),null);
 const dashboard=await worker.fetch(request("/admin/rights"),env);
 assert.equal(dashboard.status,302);assert.equal(dashboard.headers.get("location"),root+"/admin");
 const evil=await worker.fetch(new Request(root+"/v1/admin/rights",{method:"POST",headers:{Origin:"https://evil.example","Content-Type":"application/json"},body:"{}"}),env);
 assert.equal(evil.status,403);
});
test("premium rights dashboard and script compile without HTML-injection interpolation",async()=>{
 assert.doesNotThrow(()=>new Function(rightsClient));
 for(const field of ["claimForm","agreementRef","contractPdf","reviewForm","chainCheck","exclusiveCheck","rightsEvents"])
   assert.ok(rightsHtml.includes('id="'+field+'"'),field);
 assert.match(rightsHtml,/aria-live="polite"/);
 const script=await worker.fetch(request("/admin/rights.js"),{});
 assert.equal(script.status,200);assert.match(await script.text(),/rightsEvents/);
});

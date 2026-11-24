import test from "node:test";
import assert from "node:assert/strict";
import { webcrypto } from "node:crypto";
import worker from "../worker.js";
import { validateCreator, validateReview, handleCreatorAdmin } from "../creators.js";
if(!globalThis.crypto)globalThis.crypto=webcrypto;
const root="https://addisreel-catalog.example.test";
const sample={
 kind:"filmmaker",displayName:"Mulu A.",legalName:"Mulu Alemu",
 email:"Mulu@Example.test",phone:"",region:"Addis Ababa",
 biography:"Independent film director",portfolioUrl:"https://example.test/film",consent:true
};
function fakeDB() {
 const creators=new Map(),events=[];
 return {
  creators,events,
  DB:{
   prepare(sql) {
    let args=[];
    return {
     bind(...v){args=v;return this},
     async first(){
      if(sql.includes("FROM creator_profiles WHERE email="))
       return [...creators.values()].find(x=>x.email===args[0])||null;
      if(sql.includes("FROM creator_profiles WHERE id="))return creators.get(args[0])||null;
      throw Error("Unexpected first: "+sql);
     },
     async all(){
      if(sql.includes("FROM creator_profiles")) {
       const rows=[...creators.values()].filter(x=>!sql.includes("WHERE status=")||x.status===args[0]);
       return {results:rows};
      }
      if(sql.includes("FROM creator_review_events"))
       return {results:events.filter(x=>x.creator_id===args[0])};
      throw Error("Unexpected all: "+sql);
     },
     async run(){
      if(sql.startsWith("INSERT INTO creator_profiles")) {
       creators.set(args[0],{
         id:args[0],kind:args[1],display_name:args[2],legal_name:args[3],
         email:args[4],phone:args[5],region:args[6],portfolio_url:args[7],
         biography:args[8],status:"pending",identity_confirmed:0,authority_confirmed:0
       });return {meta:{changes:1}};
      }
      if(sql.startsWith("INSERT INTO creator_review_events")) {
       events.push(sql.includes("'submitted'")
         ? {creator_id:args[1],event_type:"submitted",next_status:"pending",note:args[2]}
         : {creator_id:args[1],event_type:"reviewed",previous_status:args[2],next_status:args[3],note:args[4]});
       return {meta:{changes:1}};
      }
      if(sql.startsWith("UPDATE creator_profiles")) {
       const row=creators.get(args[4]);
       Object.assign(row,{status:args[0],identity_confirmed:args[1],authority_confirmed:args[2],review_note:args[3]});
       return {meta:{changes:1}};
      }
      throw Error("Unexpected run: "+sql);
     }
    };
   },
   async batch(stmts){for(const stmt of stmts)await stmt.run();return []}
  }
 };
}
const req=(path,method="GET",body)=>new Request(root+path,{method,
 ...(body?{body:JSON.stringify(body),headers:{"Content-Type":"application/json","Origin":root}}:{})});
test("creator registration enforces consent, identity fields and HTTPS URLs",()=>{
 assert.ok(validateCreator(sample).value);
 assert.equal(validateCreator({...sample,consent:false}).error.includes("permission"),true);
 assert.equal(validateCreator({...sample,portfolioUrl:"javascript:alert(1)"}).error.includes("HTTPS"),true);
 assert.equal(validateCreator({...sample,email:"bad"}).error.includes("email"),true);
 assert.equal(validateCreator({...sample,kind:"admin"}).error.includes("Choose"),true);
});
test("verification never bypasses independent human checks",()=>{
 const profile={status:"pending"};
 assert.ok(validateReview({status:"verified",note:"The creator provided a filmography but not identity",identityConfirmed:false,authorityConfirmed:true},profile).error);
 assert.ok(validateReview({status:"verified",note:"No",identityConfirmed:true,authorityConfirmed:true},profile).error);
 assert.equal(validateReview({status:"verified",note:"Identity and business authority were independently confirmed by studio owner",identityConfirmed:true,authorityConfirmed:true},profile).value.status,"verified");
 assert.ok(validateReview({status:"pending",note:"",identityConfirmed:false,authorityConfirmed:false},{status:"verified"}).error);
});
test("owner can register, review and audit a creator without publishing a film",async()=>{
 const env=fakeDB();
 const created=await handleCreatorAdmin(req("/v1/admin/creators","POST",sample),env.DB,new URL(root));
 assert.equal(created.status,201);
 const id=(await created.json()).id;
 const dup=await handleCreatorAdmin(req("/v1/admin/creators","POST",sample),env.DB,new URL(root));
 assert.equal(dup.status,409);
 const notVerified=await handleCreatorAdmin(req("/v1/admin/creators/"+id+"/review","PATCH",{status:"verified",identityConfirmed:false,authorityConfirmed:true,note:"Still reviewing identity and business ownership records"}),env.DB,new URL(root));
 assert.equal(notVerified.status,400);
 const approved=await handleCreatorAdmin(req("/v1/admin/creators/"+id+"/review","PATCH",{status:"verified",identityConfirmed:true,authorityConfirmed:true,note:"Owner independently checked filmmaker identity and production authority"}),env.DB,new URL(root));
 assert.equal(approved.status,200);
 const listed=await handleCreatorAdmin(req("/v1/admin/creators"),env.DB,new URL(root));
 assert.equal((await listed.json()).creators[0].status,"verified");
 const audit=await handleCreatorAdmin(req("/v1/admin/creators/"+id+"/events"),env.DB,new URL(root));
 assert.equal((await audit.json()).events.length,2);
});
test("creator records and dashboard remain private without an owner session",async()=>{
 const env={DB:{prepare(){throw Error("No database lookup allowed for anonymous user")}}};
 const list=await worker.fetch(req("/v1/admin/creators"),env);
 assert.equal(list.status,401);
 assert.equal(list.headers.get("access-control-allow-origin"),null);
 const dashboard=await worker.fetch(req("/admin/creators"),env);
 assert.equal(dashboard.status,302);
 assert.equal(dashboard.headers.get("location"),root+"/admin");
 const change=await worker.fetch(new Request(root+"/v1/admin/creators",{method:"POST",headers:{Origin:"https://evil.example.test","Content-Type":"application/json"},body:JSON.stringify(sample)}),env);
 assert.equal(change.status,403);
});

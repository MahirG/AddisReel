// Owner-only creator onboarding. Caller MUST pass requireAdmin before routing here.
// These profiles are private; verification is a human review, not a rights license.
const j=(data,status=200)=>new Response(JSON.stringify(data),{
 status,headers:{"Content-Type":"application/json; charset=utf-8","Cache-Control":"no-store",
 "X-Content-Type-Options":"nosniff","Referrer-Policy":"no-referrer"}
});
const err=(message,status=400)=>j({error:message},status);
const validText=(s,min,max)=>typeof s==="string"&&s.trim().length>=min&&s.trim().length<=max;
const normalize=s=>s.trim().replace(/\s+/g," ");
const validEmail=s=>typeof s==="string"&&s.length<=254&&/^[^\s@<>]+@[^\s@<>]+\.[^\s@<>]+$/.test(s);
const validHttps=s=>!s||(typeof s==="string"&&s.length<=500&&/^https:\/\/[a-z0-9.-]+(?::[0-9]{1,5})?(?:[/?#][^\s]*)?$/i.test(s));
async function jsonInput(request) {
 const raw=await request.text();
 if(raw.length>12000)throw new Error("Request exceeds 12 KB");
 const value=JSON.parse(raw);
 if(!value||typeof value!=="object"||Array.isArray(value))throw new Error("Expected JSON object");
 return value;
}
export function validateCreator(input) {
 if(!input||typeof input!=="object"||Array.isArray(input))return {error:"Invalid creator profile"};
 if(!["filmmaker","studio"].includes(input.kind))return {error:"Choose filmmaker or production studio"};
 if(!validText(input.displayName,2,100)||!validText(input.legalName,2,140))
   return {error:"Provide a display name and legal name"};
 if(!validEmail(input.email))return {error:"Provide a valid contact email"};
 if(!validText(input.region??"",0,100)||!validText(input.phone??"",0,40)||
    !validText(input.biography??"",0,1200))return {error:"Profile details are too long"};
 if(!validHttps(input.portfolioUrl??""))return {error:"Portfolio must be an HTTPS URL"};
 if(input.consent!==true)return {error:"Confirm permission to store the creator contact details"};
 return {value:{
   kind:input.kind,displayName:normalize(input.displayName),legalName:normalize(input.legalName),
   email:input.email.trim().toLowerCase(),phone:(input.phone??"").trim(),
   region:normalize(input.region??""),portfolioUrl:(input.portfolioUrl??"").trim(),
   biography:(input.biography??"").trim()
 }};
}
export function validateReview(input,profile) {
 if(!input||typeof input!=="object"||Array.isArray(input))return {error:"Invalid review"};
 if(!["pending","needs_info","verified","rejected"].includes(input.status))
   return {error:"Invalid review status"};
 if(!validText(input.note??"",0,1000))return {error:"Review note is too long"};
 const identity=input.identityConfirmed===true,authority=input.authorityConfirmed===true;
 const note=(input.note??"").trim();
 if(input.status==="verified" && (!identity||!authority||note.length<20))
   return {error:"Verification requires confirmed identity, confirmed production authority and a review note (20+ characters)"};
 if(profile.status==="verified" && input.status!=="verified" && note.length<10)
   return {error:"Explain why the creator's verified status was revoked"};
 return {value:{status:input.status,identityConfirmed:identity?1:0,
   authorityConfirmed:authority?1:0,note}};
}
const identifier=p=>typeof p==="string"&&/^[a-f0-9-]{36}$/.test(p);
export async function handleCreatorAdmin(request,env,url) {
 const path=url.pathname;
 try {
   if(path==="/v1/admin/creators" && request.method==="GET") {
     const status=url.searchParams.get("status");
     if(status && !["pending","needs_info","verified","rejected"].includes(status))
       return err("Invalid filter");
     const sql="SELECT id,kind,display_name,legal_name,email,phone,region,portfolio_url,biography,status,identity_confirmed,authority_confirmed,review_note,reviewed_at,created_at,updated_at FROM creator_profiles "+
       (status?"WHERE status=? ":"")+"ORDER BY created_at DESC LIMIT 100";
     const p=env.DB.prepare(sql);
     const result=await (status?p.bind(status):p).all();
     return j({creators:result.results,limit:100});
   }
   if(path==="/v1/admin/creators" && request.method==="POST") {
     const input=await jsonInput(request);
     const parsed=validateCreator(input);
     if(parsed.error)return err(parsed.error);
     const v=parsed.value;
     if(await env.DB.prepare("SELECT id FROM creator_profiles WHERE email=?").bind(v.email).first())
       return err("This contact email already has a creator profile",409);
     const id=crypto.randomUUID(),auditId=crypto.randomUUID();
     await env.DB.batch([
       env.DB.prepare("INSERT INTO creator_profiles(id,kind,display_name,legal_name,email,phone,region,portfolio_url,biography) VALUES (?,?,?,?,?,?,?,?,?)")
         .bind(id,v.kind,v.displayName,v.legalName,v.email,v.phone,v.region,v.portfolioUrl,v.biography),
       env.DB.prepare("INSERT INTO creator_review_events(id,creator_id,event_type,next_status,note) VALUES (?,?,'submitted','pending',?)")
         .bind(auditId,id,"Owner initiated filmmaker onboarding")
     ]);
     return j({id,status:"pending",created:true},201);
   }
   const match=/^\/v1\/admin\/creators\/([a-f0-9-]{36})\/review$/.exec(path);
   if(match && request.method==="PATCH") {
     if(!identifier(match[1]))return err("Invalid creator ID");
     const profile=await env.DB.prepare("SELECT id,status FROM creator_profiles WHERE id=?").bind(match[1]).first();
     if(!profile)return err("Creator not found",404);
     const parsed=validateReview(await jsonInput(request),profile);
     if(parsed.error)return err(parsed.error);
     const v=parsed.value;
     await env.DB.batch([
       env.DB.prepare("UPDATE creator_profiles SET status=?,identity_confirmed=?,authority_confirmed=?,review_note=?,reviewed_at=datetime('now'),updated_at=datetime('now') WHERE id=?")
         .bind(v.status,v.identityConfirmed,v.authorityConfirmed,v.note,match[1]),
       env.DB.prepare("INSERT INTO creator_review_events(id,creator_id,event_type,previous_status,next_status,note) VALUES (?,?,'reviewed',?,?,?)")
         .bind(crypto.randomUUID(),match[1],profile.status,v.status,v.note)
     ]);
     return j({id:match[1],status:v.status,reviewed:true});
   }
   const audit=/^\/v1\/admin\/creators\/([a-f0-9-]{36})\/events$/.exec(path);
   if(audit&&request.method==="GET"){
     if(!identifier(audit[1]))return err("Invalid creator ID");
     const rows=await env.DB.prepare("SELECT event_type,previous_status,next_status,note,created_at FROM creator_review_events WHERE creator_id=? ORDER BY created_at DESC LIMIT 50").bind(audit[1]).all();
     return j({events:rows.results});
   }
   return err("Creator route not found",404);
 } catch(error) {
   if(error instanceof SyntaxError)return err("Malformed JSON");
   if(error?.message==="Request exceeds 12 KB"||error?.message==="Expected JSON object")return err(error.message);
   if(String(error?.message||"").includes("UNIQUE constraint"))return err("This contact email is already registered",409);
   console.error("Creator onboarding failed",error);
   return err("Creator onboarding is temporarily unavailable",503);
 }
}

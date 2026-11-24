// Creator Studio phase 2: private exclusive film-rights registry.
// Every caller must pass requireAdmin() before using handleRightsAdmin().
// An application/record is evidence of review, not a legal license by itself.
const MAX_PDF=5*1024*1024;
const json=(body,status=200)=>new Response(JSON.stringify(body),{status,headers:{"Content-Type":"application/json; charset=utf-8","Cache-Control":"no-store","X-Content-Type-Options":"nosniff","Referrer-Policy":"no-referrer"}});
const bad=(message,status=400)=>json({error:message},status);
const uuid=id=>typeof id==="string"&&/^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(id);
const slug=id=>typeof id==="string"&&/^[a-z0-9][a-z0-9-]{0,63}$/.test(id);
const today=()=>new Date().toISOString().slice(0,10);
function dateOK(s) {return typeof s==="string"&&/^\d{4}-\d{2}-\d{2}$/.test(s)&&!Number.isNaN(Date.parse(s+"T00:00:00Z"))&&new Date(s+"T00:00:00Z").toISOString().slice(0,10)===s;}
async function input(request){
  const raw=await request.text();
  if(raw.length>12000)throw new Error("Request too large");
  const x=JSON.parse(raw);
  if(!x||typeof x!=="object"||Array.isArray(x))throw new Error("Expected JSON object");
  return x;
}
// Preserve existing published, licensed titles as legacy rights-exempt until
// an explicit future migration. Every new/non-grandfathered title is gated.
export function rightsPredicate(alias="s") {
  if(!/^[a-z]+$/.test(alias))throw Error("Invalid SQL alias");
  return "(EXISTS(SELECT 1 FROM grandfathered_rights legacy WHERE legacy.series_id="+alias+".id) OR "+
    "EXISTS(SELECT 1 FROM title_rights rights JOIN creator_profiles creator ON creator.id=rights.creator_id "+
    "WHERE rights.series_id="+alias+".id AND rights.status='approved' AND creator.status='verified' "+
    "AND rights.original_unreleased=1 AND rights.territory='worldwide' AND rights.evidence_key IS NOT NULL "+
    "AND rights.start_date<=date('now') AND (rights.end_date IS NULL OR rights.end_date>=date('now'))))";
}
export async function hasValidRights(env,seriesId){
  const row=await env.DB.prepare("SELECT "+rightsPredicate("s")+" AS allowed FROM series s WHERE s.id=?").bind(seriesId).first();
  return row?.allowed===1 || row?.allowed===true;
}
export function validateClaim(d){
  if(!d || typeof d!=="object" || Array.isArray(d))return {error:"Invalid rights claim"};
  if(!slug(d.seriesId)||!uuid(d.creatorId))return {error:"Select a film and verified creator"};
  if(typeof d.agreementRef!=="string"||! /^[A-Za-z0-9][A-Za-z0-9._-]{4,79}$/.test(d.agreementRef))
    return {error:"Enter a unique agreement reference (5–80 letters, digits, dots, hyphens or underscores)"};
  if(d.rightsType!=="exclusive_first_premiere"||d.territory!=="worldwide")
    return {error:"Only exclusive first-premiere rights for worldwide streaming are supported"};
  if(!dateOK(d.signedOn)||d.signedOn>today())
    return {error:"Enter the genuine agreement execution date (not in the future)"};
  if(!dateOK(d.startDate)|| (d.endDate && !dateOK(d.endDate)) ||
    (d.endDate && d.endDate<d.startDate))return {error:"Enter valid distribution dates"};
  if(d.originalUnreleased!==true || d.ownerAttestation!==true)
    return {error:"Original/unreleased work and rights-holder authorization must be attested"};
  return {value:{seriesId:d.seriesId,creatorId:d.creatorId,agreementRef:d.agreementRef,
    rightsType:d.rightsType,territory:d.territory,startDate:d.startDate,
    endDate:d.endDate||null,signedOn:d.signedOn}};
}
export function validateDecision(d,right){
  if(!d || typeof d!=="object"||Array.isArray(d))return {error:"Invalid review"};
  if(!["approved","rejected","revoked"].includes(d.status))return {error:"Invalid review decision"};
  if(typeof d.note!=="string"||d.note.trim().length<20||d.note.trim().length>1400)
    return {error:"Record at least 20 characters of reviewer findings"};
  if(d.status==="approved"&&(!right.evidence_key||!right.evidence_sha256))
    return {error:"Upload the executed PDF agreement before approval"};
  if(d.status==="approved"&&!(d.checkedChainOfTitle===true&&d.checkedExclusivity===true))
    return {error:"Confirm both chain of title and exclusivity checks"};
  if(right.status==="revoked")return {error:"A revoked agreement cannot be restored; create a new agreement"};
  if(right.status==="approved"&&d.status!=="revoked")
    return {error:"An approved agreement may only be revoked"};
  if(right.status!=="approved"&&d.status==="revoked")
    return {error:"Only an approved agreement can be revoked"};
  return {value:{status:d.status,note:d.note.trim(),
    checkedChainOfTitle:d.checkedChainOfTitle===true?1:0,
    checkedExclusivity:d.checkedExclusivity===true?1:0}};
}
async function sha256(bytes){const hash=await crypto.subtle.digest("SHA-256",bytes);return [...new Uint8Array(hash)].map(b=>b.toString(16).padStart(2,"0")).join("");}
const seriesSQL="SELECT id,title FROM series WHERE id=?";
const rightsSQL="SELECT r.*,c.display_name,c.status AS creator_status,s.title AS current_series_title FROM title_rights r JOIN creator_profiles c ON c.id=r.creator_id JOIN series s ON s.id=r.series_id WHERE r.id=?";
export async function handleRightsAdmin(request,env,url) {
  const path=url.pathname,method=request.method;
  try{
    if(path==="/v1/admin/rights"&&method==="GET"){
      const rows=await env.DB.prepare(
        "SELECT r.id,r.series_id,r.creator_id,r.agreement_ref,r.title_name,r.rights_type,r.territory,"+
        "r.start_date,r.end_date,r.signed_on,r.original_unreleased,r.evidence_sha256,r.evidence_bytes,"+
        "r.status,r.checked_chain_of_title,r.checked_exclusivity,r.review_note,r.reviewed_at,"+
        "r.created_at,r.updated_at,c.display_name AS creator_name,c.status AS creator_status "+
        "FROM title_rights r JOIN creator_profiles c ON c.id=r.creator_id ORDER BY r.created_at DESC LIMIT 150"
      ).all();
      const legacy=await env.DB.prepare("SELECT series_id FROM grandfathered_rights ORDER BY series_id LIMIT 200").all();
      return json({rights:rows.results,legacySeries:legacy.results.map(x=>x.series_id),limit:150});
    }
    if(path==="/v1/admin/rights"&&method==="POST"){
      const result=validateClaim(await input(request));
      if(result.error)return bad(result.error);
      const d=result.value;
      const [series,creator,duplicate]=await Promise.all([
        env.DB.prepare(seriesSQL).bind(d.seriesId).first(),
        env.DB.prepare("SELECT id,status FROM creator_profiles WHERE id=?").bind(d.creatorId).first(),
        env.DB.prepare("SELECT id FROM title_rights WHERE agreement_ref=?").bind(d.agreementRef).first()
      ]);
      if(!series)return bad("Series not found",404);
      if(!creator||creator.status!=="verified")return bad("Verify the filmmaker or studio before registering film rights",409);
      if(duplicate)return bad("Agreement reference already exists",409);
      const id=crypto.randomUUID();
      await env.DB.batch([
        env.DB.prepare(
          "INSERT INTO title_rights(id,series_id,creator_id,agreement_ref,title_name,rights_type,territory,start_date,end_date,signed_on,original_unreleased) VALUES(?,?,?,?,?,?,?,?,?,?,1)"
        ).bind(id,d.seriesId,d.creatorId,d.agreementRef,series.title,d.rightsType,d.territory,d.startDate,d.endDate,d.signedOn),
        env.DB.prepare(
          "INSERT INTO title_rights_events(id,rights_id,event_type,to_status,note) VALUES (?,?,'submitted','pending',?)"
        ).bind(crypto.randomUUID(),id,"Owner entered exclusive first-premiere agreement reference")
      ]);
      return json({id,status:"pending",created:true},201);
    }
    const matched=/^\/v1\/admin\/rights\/([a-f0-9-]{36})(?:\/(document|review|events))?$/.exec(path);
    if(!matched||!uuid(matched[1]))return bad("Rights route not found",404);
    const id=matched[1],action=matched[2];
    const right=await env.DB.prepare(rightsSQL).bind(id).first();
    if(!right)return bad("Rights agreement not found",404);
    if(action==="events"&&method==="GET"){
      const rows=await env.DB.prepare("SELECT event_type,from_status,to_status,note,created_at FROM title_rights_events WHERE rights_id=? ORDER BY created_at DESC LIMIT 100").bind(id).all();
      return json({events:rows.results});
    }
    if(action==="document"&&method==="GET"){
      if(!right.evidence_key)return bad("Executed agreement not uploaded",404);
      const obj=await env.MEDIA.get(right.evidence_key);
      if(!obj)return bad("Agreement document unavailable",404);
      return new Response(obj.body,{headers:{"Content-Type":"application/pdf","Content-Disposition":'attachment; filename="addisreel-rights-'+id+'.pdf"',
        "X-Content-Type-Options":"nosniff","Cache-Control":"no-store, private","Referrer-Policy":"no-referrer"}});
    }
    if(action==="document"&&method==="PUT"){
      if(right.status!=="pending")return bad("Only pending agreements accept uploads",409);
      if(request.headers.get("Content-Type")?.split(";")[0]!=="application/pdf")return bad("Upload a PDF agreement",415);
      const size=Number(request.headers.get("Content-Length")||0);
      if(size>MAX_PDF)return bad("Agreement PDF exceeds 5 MB",413);
      const bytes=await request.arrayBuffer();
      if(bytes.byteLength<8||bytes.byteLength>MAX_PDF)return bad("Agreement PDF must be 8 bytes–5 MB",413);
      const header=new Uint8Array(bytes.slice(0,5));
      if(String.fromCharCode(...header)!=="%PDF-")return bad("Invalid PDF signature",415);
      const digest=await sha256(bytes);
      const key="private/creator-rights/"+id+"/"+digest+".pdf";
      await env.MEDIA.put(key,bytes,{httpMetadata:{contentType:"application/pdf"},customMetadata:{sha256:digest}});
      await env.DB.batch([
        env.DB.prepare(
          "UPDATE title_rights SET evidence_key=?,evidence_sha256=?,evidence_bytes=?,updated_at=datetime('now') WHERE id=? AND status='pending'"
        ).bind(key,digest,bytes.byteLength,id),
        env.DB.prepare("INSERT INTO title_rights_events(id,rights_id,event_type,from_status,to_status,note) VALUES (?,?,'document_uploaded','pending','pending',?)")
          .bind(crypto.randomUUID(),id,"Executed agreement PDF SHA-256: "+digest)
      ]);
      if(right.evidence_key && right.evidence_key!==key)await env.MEDIA.delete(right.evidence_key);
      return json({id,uploaded:true,sha256:digest,bytes:bytes.byteLength});
    }
    if(action==="review"&&method==="PATCH"){
      const result=validateDecision(await input(request),right);
      if(result.error)return bad(result.error);
      const d=result.value;
      if(d.status==="approved"){
        if(right.creator_status!=="verified")return bad("Creator verification is no longer active",409);
        const overlaps=await env.DB.prepare(
          "SELECT id FROM title_rights WHERE series_id=? AND id<>? AND status='approved' AND "+
          "COALESCE(end_date,'9999-12-31')>=? AND start_date<=COALESCE(?,'9999-12-31') LIMIT 1"
        ).bind(right.series_id,id,right.start_date,right.end_date).first();
        if(overlaps)return bad("A different approved exclusive agreement overlaps this title's term",409);
      }
      await env.DB.batch([
        env.DB.prepare(
          "UPDATE title_rights SET status=?,checked_chain_of_title=?,checked_exclusivity=?,review_note=?,reviewed_at=datetime('now'),updated_at=datetime('now') WHERE id=?"
        ).bind(d.status,d.checkedChainOfTitle,d.checkedExclusivity,d.note,id),
        env.DB.prepare(
          "INSERT INTO title_rights_events(id,rights_id,event_type,from_status,to_status,note) VALUES (?,?,'reviewed',?,?,?)"
        ).bind(crypto.randomUUID(),id,right.status,d.status,d.note)
      ]);
      return json({id,status:d.status,reviewed:true});
    }
    return bad("Rights route not found",404);
  }catch(e){
    if(e instanceof SyntaxError)return bad("Malformed JSON request");
    if(e?.message==="Request too large"||e?.message==="Expected JSON object")return bad(e.message);
    if(/UNIQUE constraint/.test(String(e?.message||"")))return bad("Agreement reference already exists",409);
    console.error("Private rights management failed",e);
    return bad("Rights management temporarily unavailable",503);
  }
}

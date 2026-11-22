// Owner-only video optimization queue and scoped Windows-processor API.
// Originals are never overwritten or deleted. Optimized playback is enabled
// only after the processor verifies the result and the R2 upload completes.
const PART = 8 * 1024 * 1024;
const MAX = 1024 * 1024 * 1024;
const TTL = 45 * 60;
const enc = new TextEncoder();
const json = (v,status=200)=>new Response(JSON.stringify(v),{status,headers:{
  "Content-Type":"application/json; charset=utf-8","Cache-Control":"private, no-store",
  "X-Content-Type-Options":"nosniff"}});
const bad=(message,status=400)=>json({error:message},status);
const idOK=x=>typeof x==="string" && /^[a-z0-9][a-z0-9-]{0,63}$/.test(x);
const numOK=x=>Number.isInteger(x)&&x>0&&x<=10000;
const tokenOK=x=>typeof x==="string"&&/^[A-Za-z0-9_-]{40,256}$/.test(x);
const clock=()=>Math.floor(Date.now()/1000);
const token=()=>btoa(String.fromCharCode(...crypto.getRandomValues(new Uint8Array(32)))).replaceAll("+","-").replaceAll("/","_").replace(/=+$/g,"");
const hash=async t=>Array.from(new Uint8Array(await crypto.subtle.digest("SHA-256",enc.encode(t))),x=>x.toString(16).padStart(2,"0")).join("");
function same(a,b){if(!a||!b||a.length!==b.length)return false;let diff=0;for(let i=0;i<a.length;i++)diff|=a.charCodeAt(i)^b.charCodeAt(i);return diff===0}

export async function queueOptimization(env,seriesId,number,size){
  if(!idOK(seriesId)||!numOK(number)||!Number.isSafeInteger(size)||size<1||size>MAX)throw Error("Invalid video optimization request");
  await env.DB.prepare("INSERT INTO video_jobs(series_id,episode_number,status,original_bytes,attempts,message) "+
    "VALUES (?,?,'queued',?,0,'Waiting for Windows encoder') "+
    "ON CONFLICT(series_id,episode_number) DO UPDATE SET status='queued',original_bytes=excluded.original_bytes, "+
    "optimized_bytes=NULL,attempts=0,message='Waiting for Windows encoder',lease_hash=NULL,lease_expires=NULL,updated_at=datetime('now')")
    .bind(seriesId,number,size).run();
}
export async function optimizationStatus(env,seriesId,number){
  return env.DB.prepare("SELECT status,optimized_bytes FROM video_jobs WHERE series_id=? AND episode_number=?").bind(seriesId,number).first();
}
export async function handleAdminOptimization(request,env,url){
  if(request.method==="GET" && url.pathname==="/v1/admin/optimization/jobs"){
    const rows=await env.DB.prepare("SELECT series_id,episode_number,status,original_bytes,optimized_bytes,attempts,message,updated_at FROM video_jobs ORDER BY updated_at DESC LIMIT 100").all();
    const agent=await env.DB.prepare("SELECT last_seen_at FROM processor_access WHERE id=1").first();
    return json({jobs:rows.results||[],processorConnected:!!agent,lastSeenAt:agent?.last_seen_at||null});
  }
  if(request.method==="POST" && url.pathname==="/v1/admin/optimization/pair"){
    const secret=token();
    await env.DB.prepare("INSERT INTO processor_access(id,token_hash,updated_at,last_seen_at) VALUES(1,?,datetime('now'),NULL) "+
      "ON CONFLICT(id) DO UPDATE SET token_hash=excluded.token_hash,updated_at=datetime('now'),last_seen_at=NULL").bind(await hash(secret)).run();
    return json({token:secret,warning:"Shown once. Keep on your Windows computer. Pairing again revokes the earlier key."});
  }
  if(request.method==="POST" && url.pathname==="/v1/admin/optimization/retry"){
    const d=await request.json().catch(()=>null);
    if(!idOK(d?.seriesId)||!numOK(d?.episodeNumber))return bad("Invalid episode");
    const ep=await env.DB.prepare("SELECT media_prefix FROM episodes WHERE series_id=? AND number=?").bind(d.seriesId,d.episodeNumber).first();
    if(!ep)return bad("Episode not found",404);
    const object=await env.MEDIA.head(ep.media_prefix+"/video.mp4");
    if(!object)return bad("Original MP4 is missing",409);
    await queueOptimization(env,d.seriesId,d.episodeNumber,object.size);
    return json({queued:true});
  }
  return bad("Unknown optimization action",404);
}
async function authProcessor(request,env){
  const secret=(request.headers.get("Authorization")||"").replace(/^Bearer\s+/i,"");
  if(!tokenOK(secret))return false;
  const row=await env.DB.prepare("SELECT token_hash FROM processor_access WHERE id=1").first();
  if(!same(await hash(secret),row?.token_hash))return false;
  await env.DB.prepare("UPDATE processor_access SET last_seen_at=datetime('now') WHERE id=1").run();
  return true;
}
async function claim(env){
  const rows=await env.DB.prepare("SELECT series_id,episode_number,original_bytes FROM video_jobs "+
    "WHERE status='queued' OR (status='processing' AND lease_expires<? AND attempts<5) ORDER BY updated_at LIMIT 5").bind(clock()).all();
  for(const j of rows.results||[]){
    const lease=token(),leaseHash=await hash(lease);
    const result=await env.DB.prepare("UPDATE video_jobs SET status='processing',lease_hash=?,lease_expires=?,attempts=attempts+1, "+
      "message='Encoding on Windows',updated_at=datetime('now') WHERE series_id=? AND episode_number=? "+
      "AND (status='queued' OR (status='processing' AND lease_expires<? AND attempts<5))")
      .bind(leaseHash,clock()+TTL,j.series_id,j.episode_number,clock()).run();
    if(result.meta?.changes===1)return {seriesId:j.series_id,episodeNumber:j.episode_number,originalBytes:j.original_bytes,lease,partBytes:PART};
  }
  return null;
}
async function jobWithLease(request,env,id,n){
  if(!idOK(id)||!numOK(n))return null;
  const lease=request.headers.get("X-Processor-Lease");
  if(!tokenOK(lease))return null;
  const row=await env.DB.prepare("SELECT j.status,j.lease_hash,j.lease_expires,j.original_bytes,e.media_prefix "+
    "FROM video_jobs j JOIN episodes e ON e.series_id=j.series_id AND e.number=j.episode_number "+
    "WHERE j.series_id=? AND j.episode_number=?").bind(id,n).first();
  if(!row||row.status!=="processing"||row.lease_expires<=clock()||!same(await hash(lease),row.lease_hash))return null;
  return row;
}
export async function handleProcessor(request,env,url){
  if(!await authProcessor(request,env))return bad("Processor authorization required",401);
  if(request.method==="POST"&&url.pathname==="/v1/processor/claim")return json({job:await claim(env)});
  const match=/^\/v1\/processor\/(source|output)\/([a-z0-9-]+)\/([1-9][0-9]*)$/.exec(url.pathname);
  if(!match)return bad("Processor route not found",404);
  const kind=match[1],id=match[2],n=Number(match[3]),job=await jobWithLease(request,env,id,n);
  if(!job)return bad("Processor lease expired; reclaim this job",403);
  const prefix=job.media_prefix;
  if(kind==="source"&&request.method==="GET"){
    const file=await env.MEDIA.get(prefix+"/video.mp4");
    if(!file)return bad("Original video not found",404);
    return new Response(file.body,{headers:{"Content-Type":"video/mp4","Content-Length":String(file.size),
      "Cache-Control":"private, no-store","X-Content-Type-Options":"nosniff"}});
  }
  if(kind!=="output")return bad("Unsupported method",405);
  const action=url.searchParams.get("action");
  if(request.method==="POST"&&action==="heartbeat"){
    await env.DB.prepare("UPDATE video_jobs SET lease_expires=? WHERE series_id=? AND episode_number=? AND lease_hash=?")
      .bind(clock()+TTL,id,n,job.lease_hash).run();
    return json({alive:true});
  }
  if(request.method==="POST"&&(action==="skip"||action==="failed")){
    const data=await request.json().catch(()=>({}));
    const status=action==="skip"?"original":"failed";
    const message=typeof data?.reason==="string"?data.reason.slice(0,180):(status==="original"?"Original retained":"Encoding failed");
    await env.DB.prepare("UPDATE video_jobs SET status=?,message=?,lease_hash=NULL,lease_expires=NULL,updated_at=datetime('now') "+
      "WHERE series_id=? AND episode_number=? AND lease_hash=?").bind(status,message,id,n,job.lease_hash).run();
    return json({saved:true,status});
  }
  if(request.method==="POST"&&action==="complete"){
    const data=await request.json().catch(()=>null);
    const file=await env.MEDIA.head(prefix+"/optimized.mp4");
    if(!file||!Number.isSafeInteger(data?.size)||data.size!==file.size||file.size<1||file.size>=job.original_bytes)
      return bad("Optimized video failed size verification",409);
    await env.DB.prepare("UPDATE video_jobs SET status='ready',optimized_bytes=?,message='Quality checked, optimized MP4 ready', "+
      "lease_hash=NULL,lease_expires=NULL,updated_at=datetime('now') WHERE series_id=? AND episode_number=? AND lease_hash=?")
      .bind(file.size,id,n,job.lease_hash).run();
    return json({saved:true,status:"ready",savedBytes:job.original_bytes-file.size});
  }
  const key=prefix+"/optimized.mp4";
  if(request.method==="POST"&&action==="start"){
    const d=await request.json().catch(()=>null);
    if(!Number.isSafeInteger(d?.size)||d.size<1||d.size>MAX||d.size>=job.original_bytes)return bad("Output must be a smaller MP4 under 1 GB",413);
    const upload=await env.MEDIA.createMultipartUpload(key,{httpMetadata:{contentType:"video/mp4"}});
    return json({uploadId:upload.uploadId,partSize:PART,totalParts:Math.ceil(d.size/PART)});
  }
  const uploadId=url.searchParams.get("uploadId");
  if(!tokenOK(uploadId))return bad("Invalid output upload");
  const upload=env.MEDIA.resumeMultipartUpload(key,uploadId);
  if(request.method==="PUT"&&action==="part"){
    const number=Number(url.searchParams.get("partNumber"));
    if(!Number.isInteger(number)||number<1||number>128)return bad("Invalid part number");
    const bytes=await request.arrayBuffer();
    if(bytes.byteLength<1||bytes.byteLength>PART)return bad("Video part exceeds 8 MB",413);
    const result=await upload.uploadPart(number,bytes);
    return json({partNumber:result.partNumber,etag:result.etag});
  }
  if(request.method==="POST"&&action==="finish"){
    const d=await request.json().catch(()=>null);
    if(!Number.isSafeInteger(d?.size)||d.size<1||d.size>MAX||d.size>=job.original_bytes)return bad("Invalid output size");
    const expected=Math.ceil(d.size/PART);
    if(!Array.isArray(d.parts)||d.parts.length!==expected||!d.parts.every((x,i)=>
       x&&x.partNumber===i+1&&typeof x.etag==="string"&&x.etag.length>=8&&x.etag.length<=180))return bad("Incomplete video upload");
    const result=await upload.complete(d.parts);
    if(result.size!==d.size){await env.MEDIA.delete(key);return bad("Output file size does not match",409)}
    return json({uploaded:true,size:result.size});
  }
  return bad("Unknown output action",404);
}

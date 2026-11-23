import { queueOptimization } from './processor-api.js';
// Authenticated large-file MP4 ingestion for the AddisReel Content Studio.
// This module must only be called AFTER the normal admin session check.
// R2 multipart uses modest 8 MiB requests, avoiding the 100 MB Worker body limit.
export const PART_BYTES = 8 * 1024 * 1024;
export const MAX_VIDEO_BYTES = 1024 * 1024 * 1024; // 1 GiB per short episode.
const MAX_PARTS = MAX_VIDEO_BYTES / PART_BYTES;

function respond(value,status=200) {
  return new Response(JSON.stringify(value),{status,headers:{"Content-Type":"application/json; charset=utf-8",
    "Cache-Control":"no-store","X-Content-Type-Options":"nosniff"}});
}
function fail(message,status=400) {return respond({error:message},status);}
function validId(value) {
  // Cloudflare R2 upload IDs are opaque: do not impose an undocumented minimum length.
  return typeof value==="string" && value.length>0 && value.length<=256 &&
    !/[\u0000-\u001f\u007f]/.test(value);
}
function partListGood(parts, expected) {
  return Array.isArray(parts) && parts.length===expected && parts.every((part,i)=>
    part && part.partNumber===i+1 && typeof part.etag==="string" &&
    part.etag.length>=8 && part.etag.length<=180 &&
    !/[\u0000-\u001f\u007f]/.test(part.etag));
}
export async function handleMultipartUpload(request,env,url,seriesId,number) {
  const row=await env.DB.prepare("SELECT media_prefix,status,licensed FROM episodes WHERE series_id=? AND number=?")
    .bind(seriesId,number).first();
  if(!row) return fail("Save the episode details before uploading its video.",404);
  if(row.status==="published" && Number(row.licensed)===1)
    return fail("Unpublish this episode before replacing its live video.",409);
  if(!row.media_prefix) return fail("Episode storage is not configured.",409);
  const key=row.media_prefix+"/video.mp4", action=url.searchParams.get("action");
  if(request.method==="POST" && action==="start") {
    let payload;
    try {payload=await request.json()}catch{return fail("Invalid video upload details.")}
    const size=payload?.size;
    if(!Number.isSafeInteger(size) || size<=0 || size>MAX_VIDEO_BYTES)
      return fail("Choose an MP4 no larger than 1 GB.",413);
    if(typeof payload.name!=="string" || !/\.mp4$/i.test(payload.name) ||
       payload.name.length>250) return fail("Choose an MP4 video.");
    const upload=await env.MEDIA.createMultipartUpload(key,{httpMetadata:{contentType:"video/mp4"}});
    return respond({uploadId:upload.uploadId,partSize:PART_BYTES,totalParts:Math.ceil(size/PART_BYTES),
      maxBytes:MAX_VIDEO_BYTES});
  }
  const uploadId=url.searchParams.get("uploadId");
  if(!validId(uploadId))return fail("Invalid upload session. Reopen Studio and tap Resume.",409);
  const upload=env.MEDIA.resumeMultipartUpload(key,uploadId);
  try {
    if(request.method==="PUT" && action==="part") {
      const numberValue=Number(url.searchParams.get("partNumber"));
      if(!Number.isSafeInteger(numberValue) || numberValue<1 || numberValue>MAX_PARTS)
        return fail("Invalid video part number.");
      if(!request.body) return fail("Video part is empty.");
      // A single part comfortably fits Workers' free-tier incoming request budget.
      const bytes=await request.arrayBuffer();
      if(bytes.byteLength<1 || bytes.byteLength>PART_BYTES)
        return fail("Video chunks must not exceed 8 MB.",413);
      const part=await upload.uploadPart(numberValue,bytes);
      return respond({partNumber:part.partNumber,etag:part.etag});
    }
    if(request.method==="POST" && action==="finish") {
      let data;
      try {data=await request.json()}catch{return fail("Invalid final upload details.")}
      if(!Number.isSafeInteger(data?.size) || data.size<=0 || data.size>MAX_VIDEO_BYTES)
        return fail("Invalid video size.");
      const total=Math.ceil(data.size/PART_BYTES);
      if(!partListGood(data.parts,total))return fail("Missing or invalid uploaded video parts.");
      const object=await upload.complete(data.parts);
      if(object.size!==data.size){
        await env.MEDIA.delete(key);
        return fail("Video size verification failed. Please reselect and upload again.",409);
      }
      await queueOptimization(env,seriesId,number,object.size);
      return respond({uploaded:true,sizeBytes:object.size,format:"mp4",parts:total,optimization:"queued"});
    }
    if(request.method==="DELETE" && action==="abort") {
      await upload.abort();
      return respond({cancelled:true});
    }
    return fail("Unsupported upload action.",404);
  } catch(error) {
    console.error("AddisReel multipart upload failed:",error);
    return fail("Upload interrupted or expired. Select the same MP4 and retry.",409);
  }
}

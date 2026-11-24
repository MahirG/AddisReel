// Additive Cloudflare D1 premiere scheduling. All admin mutations require
// requireAdmin() in worker.js. No cron jobs: publication gates use SQLite's UTC
// clock for EVERY catalog, episode-list and playback/media request.
import {hasValidRights, hasRightsAt, rightsPredicateAt} from "./rights.js";

const JSON_HEADERS={
  "Content-Type":"application/json; charset=utf-8",
  "Cache-Control":"no-store",
  "X-Content-Type-Options":"nosniff",
  "Referrer-Policy":"no-referrer"
};
const json=(data,status=200)=>new Response(JSON.stringify(data),{status,headers:JSON_HEADERS});
const fail=(error,status=400)=>json({error},status);
const slug=id=>typeof id==="string"&&/^[a-z0-9][a-z0-9-]{0,63}$/.test(id);
const episodeNo=n=>Number.isInteger(n)&&n>=1&&n<=10000;
const isState=s=>["scheduled","paused","canceled"].includes(s);
const now=()=>new Date().toISOString();
export function validUtc(value) {
  if(typeof value!=="string"||!/^\d{4}-\d{2}-\d{2}T(?:[01]\d|2[0-3]):[0-5]\d:[0-5]\dZ$/.test(value))return false;
  const unix=Date.parse(value);
  return Number.isFinite(unix)&&new Date(unix).toISOString().replace(".000Z","Z")===value;
}
export function validateScheduleRequest(body,current=null) {
  if(!body||typeof body!=="object"||Array.isArray(body))return {error:"Expected a JSON object"};
  const action=body.action;
  if(!["schedule","pause","resume","cancel"].includes(action))return {error:"Choose schedule, pause, resume or cancel"};
  const note=(body.note??"");
  if(typeof note!=="string"||note.length>500)return {error:"Review note is too long"};
  if((action==="pause"||action==="cancel") && note.trim().length<10)
    return {error:"Record at least 10 characters explaining a pause or cancellation"};
  if(action==="schedule"){
    if(!validUtc(body.releaseAt))return {error:"Use an exact UTC date and time, e.g. 2027-02-01T18:00:00Z"};
    const timestamp=Date.parse(body.releaseAt);
    if(timestamp<Date.now()+5*60*1000 || timestamp>Date.now()+2*366*24*60*60*1000)
      return {error:"Schedule at least five minutes ahead and within the next two years"};
    if(current?.state==="scheduled" && Date.parse(current.release_at)<=Date.now())
      return {error:"This premiere is already live; pause or cancel rather than moving it into the future"};
    return {value:{action,releaseAt:body.releaseAt,note:note.trim()}};
  }
  if(!current)return {error:"Create a schedule before changing its status"};
  if(action==="pause" && current.state!=="scheduled")return {error:"Only scheduled releases may be paused"};
  if(action==="resume" && current.state!=="paused")return {error:"Only paused releases may be resumed"};
  if(action==="cancel" && current.state==="canceled")return {error:"This schedule is already canceled"};
  return {value:{action,releaseAt:current.release_at,note:note.trim()}};
}
export function premiereGate(alias="s"){
  if(!/^[a-z]+$/.test(alias))throw Error("Invalid SQL alias");
  return "(NOT EXISTS(SELECT 1 FROM premiere_schedules ps WHERE ps.series_id="+alias+".id) OR "+
    "EXISTS(SELECT 1 FROM premiere_schedules ps WHERE ps.series_id="+alias+".id AND ps.state='scheduled' "+
    "AND datetime(ps.premiere_at)<=datetime('now')))";
}
export function episodeGate(alias="e"){
  if(!/^[a-z]+$/.test(alias))throw Error("Invalid SQL alias");
  return "(NOT EXISTS(SELECT 1 FROM episode_release_schedules es WHERE es.series_id="+alias+".series_id AND es.episode_number="+alias+".number) OR "+
    "EXISTS(SELECT 1 FROM episode_release_schedules es WHERE es.series_id="+alias+".series_id AND es.episode_number="+alias+".number "+
    "AND es.state='scheduled' AND datetime(es.release_at)<=datetime('now')))";
}
export async function canStageScheduledRelease(env,seriesId){
  if(await hasValidRights(env,seriesId))return true;
  const p=await env.DB.prepare("SELECT premiere_at,state FROM premiere_schedules WHERE series_id=?").bind(seriesId).first();
  if(!p||p.state!=="scheduled")return false;
  return hasRightsAt(env,seriesId,p.premiere_at);
}
async function bodyJSON(req) {
  const text=await req.text();
  if(text.length>4000)throw new Error("Request too large");
  const parsed=JSON.parse(text);
  if(!parsed||typeof parsed!=="object"||Array.isArray(parsed))throw new Error("Expected JSON object");
  return parsed;
}
// Expose future launch metadata only if the owner has marked title and at least
// one media-ready episode published/licensed and rights were approved for its date.
export async function publicPremieres(env) {
  const rows=await env.DB.prepare(
    "SELECT s.id,s.title,s.genre,s.description,p.premiere_at AS premiereAtUtc,"+
    "(SELECT COUNT(*) FROM episodes e WHERE e.series_id=s.id AND e.status='published' "+
    "AND e.licensed=1 AND e.media_prefix IS NOT NULL AND "+
    "(NOT EXISTS(SELECT 1 FROM episode_release_schedules es WHERE es.series_id=e.series_id AND es.episode_number=e.number) "+
    "OR EXISTS(SELECT 1 FROM episode_release_schedules es WHERE es.series_id=e.series_id AND es.episode_number=e.number AND es.state='scheduled' AND datetime(es.release_at)<=datetime(p.premiere_at)))) AS readyEpisodes "+
    "FROM premiere_schedules p JOIN series s ON s.id=p.series_id "+
    "WHERE p.state='scheduled' AND datetime(p.premiere_at)>datetime('now') "+
    "AND s.status='published' AND s.licensed=1 AND "+rightsPredicateAt("s","date(p.premiere_at)")+" "+
    "AND EXISTS(SELECT 1 FROM episodes e WHERE e.series_id=s.id AND e.status='published' AND e.licensed=1 AND e.media_prefix IS NOT NULL "+
    "AND (NOT EXISTS(SELECT 1 FROM episode_release_schedules es WHERE es.series_id=e.series_id AND es.episode_number=e.number) "+
    "OR EXISTS(SELECT 1 FROM episode_release_schedules es WHERE es.series_id=e.series_id AND es.episode_number=e.number AND es.state='scheduled' AND datetime(es.release_at)<=datetime(p.premiere_at)))) "+
    "ORDER BY p.premiere_at ASC LIMIT 24"
  ).all();
  const serverNow=now(),serverMs=Date.parse(serverNow);
  return json({serverNow,premieres:rows.results.map(x=>({...x,remainingSeconds:Math.max(0,Math.ceil((Date.parse(x.premiereAtUtc)-serverMs)/1000))}))});
}
export async function handlePremiereAdmin(request,env,url){
  const path=url.pathname,method=request.method;
  try {
    if(path==="/v1/admin/premieres"&&method==="GET"){
      const series=await env.DB.prepare(
        "SELECT p.series_id,p.premiere_at AS release_at,p.state,s.title,s.status AS series_status,s.licensed,"+
        "(SELECT COUNT(*) FROM episodes e WHERE e.series_id=p.series_id AND e.status='published' AND e.licensed=1 AND e.media_prefix IS NOT NULL) AS staged_episodes "+
        "FROM premiere_schedules p JOIN series s ON s.id=p.series_id ORDER BY p.premiere_at LIMIT 200"
      ).all();
      const episodes=await env.DB.prepare(
        "SELECT er.series_id,er.episode_number,er.release_at,er.state,e.title,e.status AS episode_status,e.licensed "+
        "FROM episode_release_schedules er JOIN episodes e ON e.series_id=er.series_id AND e.number=er.episode_number ORDER BY er.release_at LIMIT 300"
      ).all();
      return json({serverNow:now(),premieres:series.results,episodes:episodes.results,limits:{series:200,episodes:300}});
    }
    const m=/^\/v1\/admin\/premieres\/([a-z0-9-]{1,64})(?:\/episodes\/([1-9][0-9]*))?(?:\/events)?$/.exec(path);
    if(!m||!slug(m[1]))return fail("Schedule route not found",404);
    const seriesId=m[1],number=m[2]===undefined?null:Number(m[2]);
    if(number!==null&&!episodeNo(number))return fail("Invalid episode number");
    const events=path.endsWith("/events");
    if(events&&method==="GET"){
      const list=await env.DB.prepare(
        "SELECT episode_number,action,previous_state,next_state,previous_at,next_at,reviewer_note,created_at "+
        "FROM premiere_schedule_events WHERE series_id=? ORDER BY created_at DESC LIMIT 100"
      ).bind(seriesId).all();
      return json({events:list.results});
    }
    if(events||method!=="PUT")return fail("Schedule route not found",404);
    const owner=await env.DB.prepare("SELECT id,title,status,licensed FROM series WHERE id=?").bind(seriesId).first();
    if(!owner)return fail("Film not found",404);
    const existing=number===null
      ?await env.DB.prepare("SELECT state,premiere_at AS release_at FROM premiere_schedules WHERE series_id=?").bind(seriesId).first()
      :await env.DB.prepare("SELECT state,release_at FROM episode_release_schedules WHERE series_id=? AND episode_number=?").bind(seriesId,number).first();
    const parsed=validateScheduleRequest(await bodyJSON(request),existing);
    if(parsed.error)return fail(parsed.error,409);
    const {action,releaseAt,note}=parsed.value;
    if(action==="schedule"||action==="resume"){
      if(!await (async()=>{
        if(await hasValidRights(env,seriesId))return true;
        return hasRightsAt(env,seriesId,releaseAt);
      })())return fail("A verified worldwide exclusive agreement must cover the scheduled release date",409);
    }
    if(number===null) {
      // Prevent accidental takedown of previously released movies.
      if(!existing&&owner.status==="published"&&owner.licensed===1)
        return fail("Already published films cannot be rescheduled as first premieres",409);
      if(action==="schedule" && existing?.state==="scheduled" && Date.parse(existing.release_at)<=Date.now())
        return fail("Already premiered. Pause or cancel, but do not move viewers' release time",409);
    }else{
      const episode=await env.DB.prepare("SELECT status,licensed FROM episodes WHERE series_id=? AND number=?").bind(seriesId,number).first();
      if(!episode)return fail("Episode not found",404);
      const parent=await env.DB.prepare("SELECT premiere_at,state FROM premiere_schedules WHERE series_id=?").bind(seriesId).first();
      if(parent&&action==="schedule"&&releaseAt<parent.premiere_at)
        return fail("Episode release cannot precede its series premiere",409);
      if(!existing&&episode.status==="published"&&episode.licensed===1 &&
         owner.status==="published"&&owner.licensed===1 &&
         (!parent|| (parent.state==="scheduled"&&Date.parse(parent.premiere_at)<=Date.now())))
        return fail("An already released episode cannot be moved into a future schedule",409);
    }
    const nextState=action==="schedule"||action==="resume"?"scheduled":action==="pause"?"paused":"canceled";
    const eventAction=action==="schedule"&&existing?"reschedule":action;
    if(number===null){
      if(existing){
        await env.DB.batch([
          env.DB.prepare("UPDATE premiere_schedules SET state=?,premiere_at=?,updated_at=datetime('now') WHERE series_id=?")
            .bind(nextState,releaseAt,seriesId),
          env.DB.prepare("INSERT INTO premiere_schedule_events(id,series_id,episode_number,action,previous_state,next_state,previous_at,next_at,reviewer_note) VALUES (?,?,NULL,?,?,?,?,?,?)")
            .bind(crypto.randomUUID(),seriesId,eventAction,existing.state,nextState,existing.release_at,releaseAt,note)
        ]);
      }else{
        await env.DB.batch([
          env.DB.prepare("INSERT INTO premiere_schedules(series_id,premiere_at,state) VALUES (?,?,?)").bind(seriesId,releaseAt,nextState),
          env.DB.prepare("INSERT INTO premiere_schedule_events(id,series_id,episode_number,action,previous_state,next_state,previous_at,next_at,reviewer_note) VALUES (?,?,NULL,?,NULL,?,NULL,?,?)")
            .bind(crypto.randomUUID(),seriesId,eventAction,nextState,releaseAt,note)
        ]);
      }
    }else{
      if(existing){
        await env.DB.batch([
          env.DB.prepare("UPDATE episode_release_schedules SET state=?,release_at=?,updated_at=datetime('now') WHERE series_id=? AND episode_number=?")
            .bind(nextState,releaseAt,seriesId,number),
          env.DB.prepare("INSERT INTO premiere_schedule_events(id,series_id,episode_number,action,previous_state,next_state,previous_at,next_at,reviewer_note) VALUES (?,?,?,?,?,?,?,?,?)")
            .bind(crypto.randomUUID(),seriesId,number,eventAction,existing.state,nextState,existing.release_at,releaseAt,note)
        ]);
      }else{
        await env.DB.batch([
          env.DB.prepare("INSERT INTO episode_release_schedules(series_id,episode_number,release_at,state) VALUES (?,?,?,?)").bind(seriesId,number,releaseAt,nextState),
          env.DB.prepare("INSERT INTO premiere_schedule_events(id,series_id,episode_number,action,previous_state,next_state,previous_at,next_at,reviewer_note) VALUES (?,?,?,?,NULL,?,NULL,?,?)")
            .bind(crypto.randomUUID(),seriesId,number,eventAction,nextState,releaseAt,note)
        ]);
      }
    }
    return json({saved:true,seriesId,episode:number,state:nextState,releaseAtUtc:releaseAt,
      effective:"Scheduled visibility is evaluated against UTC on each request; title and episode must also be published, licensed and media-ready."});
  }catch(error){
    if(error instanceof SyntaxError)return fail("Malformed JSON");
    if(["Expected JSON object","Request too large"].includes(error?.message))return fail(error.message);
    console.error("Premiere scheduling failed",error);
    return fail("Scheduling temporarily unavailable",503);
  }
}

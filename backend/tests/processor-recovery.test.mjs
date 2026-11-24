import test from "node:test";
import assert from "node:assert/strict";
import worker from "../worker.js";

const root = "https://addisreel-catalog.example.test";
const cookie = "__Host-addisreel_session=" + "a".repeat(48);
const id = "11111111-1111-4111-8111-111111111111";
const sourceKey = "series/demo/episodes/1/source.mp4";
const retryPath = "/v1/admin/processing-jobs/" + id + "/retry";

function fixture({ initial = null, stalled = false, draft = true, sourcePresent = true } = {}) {
  const jobs = [];
  if (initial) jobs.push({ id, status: initial, source_key: sourceKey });
  const db = {
    prepare(sql) {
      return {
        bind(...params) {
          return {
            async first() {
              if (sql.includes("FROM admin_sessions s JOIN")) {
                return { id: 1, email: "owner@example.test", expires_at: Date.now() / 1000 + 600 };
              }
              if (sql.includes("JOIN episodes e ON") && sql.includes("j.id=?")) {
                const job = jobs.find(j => j.id === params[0]);
                return job ? {source_key: job.source_key, status: job.status, episode_status: draft ? "draft" : "published"} : null;
              }
              if (sql.includes("SELECT media_prefix,status FROM episodes")) {
                return {media_prefix:"series/demo/episodes/1",status:draft?"draft":"published"};
              }
              if (sql.includes("INSERT INTO video_processing_jobs")) {
                if (jobs.some(j => j.status === "queued" || j.status === "processing")) return null;
                const created={id:params[0],status:"queued",source_key:params[3]};
                jobs.push(created);
                return {id:created.id};
              }
              if (sql.includes("SELECT id,status FROM video_processing_jobs")) {
                const active = jobs.find(j => j.status === "queued" || j.status === "processing");
                return active ? {id:active.id,status:active.status} : null;
              }
              return null;
            },
            async run() {
              if (sql.includes("UPDATE video_processing_jobs SET status='queued'")) {
                const job = jobs.find(j => j.id === params[0]);
                if (job && (job.status === "failed" || (job.status === "processing" && stalled))) {
                  job.status = "queued";
                  return {meta:{changes:1}};
                }
                return {meta:{changes:0}};
              }
              return {meta:{changes:0}};
            }
          };
        }
      };
    }
  };
  const env={DB:db,MEDIA:{async head(key){return sourcePresent&&key===sourceKey?{size:2048}:null}}};
  function request(path, method="GET", body) {
    return new Request(root + path, {
      method, headers: {Cookie:cookie, ...(method!=="GET"?{Origin:root}:{}),
        ...(body!==undefined?{"Content-Type":"application/json"}:{})},
      ...(body!==undefined?{body:JSON.stringify(body)}:{})
    });
  }
  return {env,jobs,request};
}

test("only authenticated owners can retry failed processing jobs", async()=>{
  const t=fixture({initial:"failed"});
  const unauthorized=await worker.fetch(new Request(root+retryPath,{method:"POST",headers:{Origin:root}}),t.env);
  assert.equal(unauthorized.status,401);
  const owner=await worker.fetch(t.request(retryPath,"POST",{}),t.env);
  assert.equal(owner.status,200);
  assert.deepEqual(await owner.json(),{jobId:id,status:"queued",requeued:true});
  assert.equal(t.jobs[0].status,"queued");
});

test("processing retries reject live, completed and published work", async()=>{
  const active=fixture({initial:"processing"});
  assert.equal((await worker.fetch(active.request(retryPath,"POST",{}),active.env)).status,409);
  const completed=fixture({initial:"ready"});
  assert.equal((await worker.fetch(completed.request(retryPath,"POST",{}),completed.env)).status,409);
  const published=fixture({initial:"failed",draft:false});
  assert.equal((await worker.fetch(published.request(retryPath,"POST",{}),published.env)).status,409);
  const noOriginal=fixture({initial:"failed",sourcePresent:false});
  assert.equal((await worker.fetch(noOriginal.request(retryPath,"POST",{}),noOriginal.env)).status,409);
});

test("stalled processing jobs can be explicitly recovered without breaking active work", async()=>{
  const t=fixture({initial:"processing",stalled:true});
  const res=await worker.fetch(t.request(retryPath,"POST",{}),t.env);
  assert.equal(res.status,200);
  assert.equal(t.jobs[0].status,"queued");
});

test("queue endpoint deduplicates active work when clicked twice", async()=>{
  const t=fixture();
  const queue=()=>worker.fetch(t.request("/v1/admin/processing-jobs","POST",{
    seriesId:"demo",episode:1
  }),t.env);
  const first=await queue(),second=await queue();
  assert.equal(first.status,202);
  assert.equal(second.status,200);
  const a=await first.json(),b=await second.json();
  assert.equal(a.jobId,b.jobId);
  assert.equal(t.jobs.length,1);
  assert.equal(t.jobs[0].status,"queued");
});

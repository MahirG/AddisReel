import test from "node:test";
import assert from "node:assert/strict";
import worker from "../short-url.js";

test("short home address keeps the short hostname after redirect", async () => {
  const res=await worker.fetch(new Request("https://ar.eddieaman475.workers.dev/"),{});
  assert.equal(res.status,302);
  assert.equal(res.headers.get("Location"),"https://ar.eddieaman475.workers.dev/admin");
});
test("short studio forwards session and upload requests without changing Origin or Cookie",async()=>{
  let captured;
  const original = new Request("https://ar.eddieaman475.workers.dev/v1/admin/optimization/jobs",{
    method:"POST",headers:{"Origin":"https://ar.eddieaman475.workers.dev","Cookie":"__Host-addisreel_session=fake","Content-Type":"application/json"},
    body:JSON.stringify({sample:true})
  });
  const res=await worker.fetch(original,{CATALOG:{async fetch(req){
    captured=req;
    return new Response("ok",{status:200});
  }}});
  assert.equal(res.status,200);
  assert.equal(captured.url,original.url);
  assert.equal(captured.headers.get("Origin"),"https://ar.eddieaman475.workers.dev");
  assert.equal(captured.headers.get("Cookie"),"__Host-addisreel_session=fake");
  assert.equal(await captured.text(),JSON.stringify({sample:true}));
});
test("service binding is required: no unguarded external proxy",async()=>{
  const res=await worker.fetch(new Request("https://ar.eddieaman475.workers.dev/admin"),{});
  assert.equal(res.status,503);
  assert.equal(res.headers.get("Cache-Control"),"no-store");
});

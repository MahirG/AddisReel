import test from "node:test";
import assert from "node:assert/strict";
import { webcrypto, createHash } from "node:crypto";
import vm from "node:vm";
import { clientScript } from "../client.js";
import { studioHtml } from "../studio.js";

async function checksum(file) { return createHash("sha256").update(Buffer.from(await file.arrayBuffer())).digest("hex"); }
async function waitFor(predicate) {
  for (let i=0;i<80;i++) {
    if (predicate()) return;
    await new Promise(resolve=>setTimeout(resolve,10));
  }
  assert.fail("Timed out waiting for upload");
}
function harness({ resumable = new Set(), jobs = [] } = {}) {
  const elements = new Map(), requests=[], statusRequests=[];
  let ready;
  function makeNode(tag="div") {
    const handlers = new Map();
    const elem = {
      tagName: tag, value: "", textContent: "", hidden: false, disabled: false,
      files: [], children: [], className: "",
      classList: { toggle() {} },
      append(...children) { this.children.push(...children); },
      replaceChildren(...children) { this.children=[...children]; },
      setAttribute() {}, getAttribute() { return null; },
      addEventListener(type, callback) { handlers.set(type, callback); },
      trigger(type) {
        const fn = handlers.get(type);
        assert.ok(fn, `Missing ${type} handler`);
        return fn({ preventDefault() {} });
      }
    };
    return elem;
  }
  function el(id) {
    if (!elements.has(id)) elements.set(id,makeNode(id));
    return elements.get(id);
  }
  class MockXHR {
    constructor() { this.upload={};this.headers=new Map();this.status=200;this.responseText="{}";requests.push(this); }
    open(method,path) {this.method=method;this.path=path;}
    setRequestHeader(k,v) {this.headers.set(k,v);}
    send(blob) {this.blob=blob;}
    progress(loaded) {this.upload.onprogress({lengthComputable:true,loaded,total:this.blob.size});}
    complete(status=200,responseText="{}") {this.status=status;this.responseText=responseText;this.onload();}
    abort() {this.onabort();}
  }
  const context = {
    document: {
      readyState: "loading", hidden:false,
      getElementById: el,createElement: makeNode,querySelectorAll() {return [];},
      addEventListener(type,listener) {if(type==="DOMContentLoaded")ready=listener;}
    },
    window: { addEventListener() {} },
    navigator: {},
    XMLHttpRequest: MockXHR,
    crypto: webcrypto, Blob, console, Date,
    fetch: async (route) => {
      if(String(route).includes("/status?")) {
        const path=String(route).split("?")[0].replace(/\/status$/,""),size=Number(new URL("https://example.test"+route).searchParams.get("size"));
        statusRequests.push(route);
        return {ok:true,status:200,json:async()=>({uploaded:resumable.has(path),bytes:resumable.has(path)?size:0})};
      }
      if(route==="/v1/admin/processing-jobs")
        return {ok:true,status:200,json:async()=>({jobs})};
      return {ok:true,status:200,json:async()=>({authenticated:false,setupRequired:false})};
    }
  };
  vm.runInNewContext(clientScript,context,{timeout:1500});
  return {el,requests,statusRequests,ready:async()=>{assert.ok(ready);await ready();}};
}

test("Studio client parses and includes recovery controls",()=>{
  assert.doesNotThrow(()=>new vm.Script(clientScript));
  for(const id of ["transferProgress","transferState","cancelUpload","processingJobs"])
    assert.match(studioHtml,new RegExp('id="'+id+'"'));
  assert.match(studioHtml,/same folder again/);
});

test("MP4 progress is server-confirmed before showing 100 percent",async()=>{
  const t=harness();await t.ready();
  t.el("hlsSeries").value="series-a";t.el("hlsEpisode").value="1";
  const file=new Blob([new Uint8Array(100)],{type:"video/mp4"});file.name="episode.mp4";
  t.el("mp4File").files=[file];
  const task=t.el("uploadMp4").trigger("click");
  assert.equal(t.requests.length,1);
  assert.equal(t.requests[0].path,"/v1/admin/hls/series-a/1/source.mp4");
  t.requests[0].progress(50);
  assert.equal(t.el("transferProgress").value,50);
  t.requests[0].progress(100);
  assert.equal(t.el("transferProgress").value,99);
  t.requests[0].complete();await task;
  assert.equal(t.el("transferProgress").value,100);
  assert.match(t.el("mp4State").textContent,/uploaded/);
  assert.equal(t.el("uploadMp4").disabled,false);
});

test("upload cancellation aborts current request and resets controls",async()=>{
  const t=harness();await t.ready();
  t.el("hlsSeries").value="series-a";t.el("hlsEpisode").value="1";
  const file=new Blob([new Uint8Array(64)],{type:"video/mp4"});file.name="take.mp4";
  t.el("mp4File").files=[file];
  const task=t.el("uploadMp4").trigger("click");
  t.el("cancelUpload").trigger("click");
  await task;
  assert.match(t.el("mediaNotice").textContent,/cancelled/);
  assert.equal(t.el("uploadMp4").disabled,false);
  assert.equal(t.el("cancelUpload").hidden,true);
});

test("HLS batch checks master playlist before upload",async()=>{
  const t=harness();await t.ready();
  t.el("hlsSeries").value="series-a";t.el("hlsEpisode").value="1";
  const file=new Blob([new Uint8Array(25)],{type:"video/mp2t"});
  file.name="seg-0.ts";file.webkitRelativePath="prepared/v0/seg-0.ts";
  t.el("hlsFiles").files=[file];
  await t.el("uploadHls").trigger("click");
  assert.equal(t.requests.length,0);
  assert.match(t.el("mediaNotice").textContent,/master.m3u8/);
});

function hlsFiles() {
  const master=new Blob([new Uint8Array(25)],{type:"application/vnd.apple.mpegurl"});
  master.name="master.m3u8";master.webkitRelativePath="folder/master.m3u8";
  const segment=new Blob([new Uint8Array(75)],{type:"video/mp2t"});
  segment.name="0.ts";segment.webkitRelativePath="folder/v0/0.ts";
  return {master,segment};
}

test("HLS progress accounts for total bytes and checks SHA-256",async()=>{
  const t=harness();await t.ready();
  const {master,segment}=hlsFiles();
  t.el("hlsSeries").value="series-a";t.el("hlsEpisode").value="1";
  t.el("hlsFiles").files=[master,segment];
  const task=t.el("uploadHls").trigger("click");
  await waitFor(()=>t.requests.length===1);
  assert.equal(t.statusRequests.length,1);
  const first=t.requests[0];
  assert.equal(first.headers.get("X-Upload-SHA256"),await checksum(master));
  first.progress(12);
  assert.equal(t.el("transferProgress").value,12);
  first.complete(200,JSON.stringify({sha256:await checksum(master),bytes:25}));
  await waitFor(()=>t.requests.length===2);
  const second=t.requests[1];
  second.progress(25);
  assert.equal(t.el("transferProgress").value,50);
  second.complete(200,JSON.stringify({sha256:await checksum(segment),bytes:75}));
  await task;
  assert.equal(t.el("transferProgress").value,100);
  assert.match(t.el("uploadState").textContent,/2 verified files · 2 uploaded · 0 already stored/);
});

test("resuming HLS skips only server-verified files",async()=>{
  const masterPath="/v1/admin/hls/series-a/1/master.m3u8";
  const t=harness({resumable:new Set([masterPath])});await t.ready();
  const {master,segment}=hlsFiles();
  t.el("hlsSeries").value="series-a";t.el("hlsEpisode").value="1";
  t.el("hlsFiles").files=[master,segment];
  const task=t.el("uploadHls").trigger("click");
  await waitFor(()=>t.requests.length===1);
  assert.equal(t.requests[0].path,"/v1/admin/hls/series-a/1/v0/0.ts");
  t.requests[0].complete(200,JSON.stringify({sha256:await checksum(segment),bytes:75}));
  await task;
  assert.equal(t.el("transferProgress").value,100);
  assert.match(t.el("uploadState").textContent,/1 uploaded · 1 already stored/);
});

test("processing jobs show exact queue state and actionable errors",async()=>{
  const t=harness({jobs:[
    {series_id:"show-1",episode_number:1,status:"queued",progress:0,updated_at:"2026-11-23 09:00:00"},
    {series_id:"show-2",episode_number:2,status:"failed",progress:35,error_message:"Transcode failed",updated_at:"2026-11-23 09:01:00"}
  ]});await t.ready();
  t.el("appShell").hidden=false;
  await t.el("refreshProcessing").trigger("click");
  const cards=t.el("processingJobs").children;
  assert.equal(cards.length,2);
  assert.equal(cards[0].children[1].value,0);
  assert.match(cards[0].children[2].textContent,/Waiting for an external FFmpeg processor/);
  assert.equal(cards[1].children[1].value,35);
  assert.match(cards[1].children[2].textContent,/Transcode failed/);
});

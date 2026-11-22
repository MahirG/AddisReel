import test from "node:test";
import assert from "node:assert/strict";
import worker from "../worker.js";
import { studioHtml } from "../studio.js";
import { wizardScript } from "../wizard.js";

const origin = "https://studio.example.test";
const envWith = available => ({
  DB: {
    prepare() {
      return {bind(){return this},async first(){return {series_id:"my-drama",number:1,title:"Episode One",media_prefix:"series/my-drama/episodes/1"}}};
    }
  },
  MEDIA: {async head(key){return available.includes(key.split("/").at(-1))?{size:10}:null;}}
});

test("one-flow publishing UI includes all three steps and advanced fallback", () => {
  for (const id of ["wTitle","wCoverFile","wVideos","wUploadVideos","wReview","wRights","wPublish","advancedEditor"]) {
    assert.ok(studioHtml.includes('id="'+id+'"'),"Missing "+id);
  }
  assert.ok(studioHtml.includes("/admin/wizard.js"));
  assert.doesNotThrow(()=>new Function(wizardScript));
});

test("published MP4 episodes get an MP4 playback resolver", async () => {
  const response=await worker.fetch(new Request(origin+"/v1/play/my-drama/1"),envWith(["video.mp4"]));
  assert.equal(response.status,200);
  const data=await response.json();
  assert.equal(data.format,"mp4");
  assert.equal(data.playbackUrl,origin+"/v1/media/my-drama/1/video.mp4");
});

test("published HLS remains preferred when HLS and MP4 exist", async () => {
  const response=await worker.fetch(new Request(origin+"/v1/play/my-drama/1"),envWith(["master.m3u8","video.mp4"]));
  assert.equal(response.status,200);
  const data=await response.json();
  assert.equal(data.format,"hls");
  assert.ok(data.playbackUrl.endsWith("/master.m3u8"));
});

test("never advertise missing video as playable", async () => {
  const response=await worker.fetch(new Request(origin+"/v1/play/my-drama/1"),envWith([]));
  assert.equal(response.status,404);
});

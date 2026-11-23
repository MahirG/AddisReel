import test from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
const java=name=>readFileSync("studio/src/main/java/et/addisreel/studio/"+name+".java","utf8");

test("Native Studio header exposes actionable live activity and unread notification bell",()=>{
  const activity=java("StudioActivity");
  assert.match(activity,/activityBell\.setOnClickListener/);
  assert.match(activity,/status\.setOnClickListener/);
  assert.match(activity,/ActivityJournal\.unread\(this\)/);
  assert.match(activity,/OptimizationClient\.matching/);
  assert.match(activity,/WindowInsets\.Type\.displayCutout/);
});
test("Live Activity Center uses verified progression without fake optimizer percentages",()=>{
  const center=java("ActivityCenter");
  assert.match(center,/Verifying storage/);
  assert.match(center,/Waiting for optimizer/);
  assert.match(center,/Optimizing on Windows PC/);
  assert.match(center,/Checking output quality/);
  assert.match(center,/ready.*original/);
  assert.match(center,/confirmedBytes/);
  assert.match(center,/Pause/);assert.match(center,/Retry optimization/);
  assert.match(center,/ActivityJournal\.markRead/);
});
test("Native event journal writes private, capped history and avoids raw credentials",()=>{
  const journal=java("ActivityJournal");
  assert.match(journal,/LIMIT=60/);
  assert.match(journal,/MODE_PRIVATE/);
  assert.doesNotMatch(journal,/CookieManager|Authorization|Processor-Lease/);
  const client=java("OptimizationClient");
  assert.match(client,/\/v1\/admin\/optimization\/jobs/);
  assert.match(client,/\/v1\/admin\/optimization\/retry/);
  assert.match(client,/CookieManager\.getInstance/);
  assert.match(client,/hadPrevious/);
});
test("Real native multipart encoder journals independent stages and confirmed bytes",()=>{
  const engine=java("UploadEngine");
  assert.match(engine,/UploadStore\.stage\(app,"preparing"/);
  assert.match(engine,/UploadStore\.stage\(app,"uploading"/);
  assert.match(engine,/UploadStore\.stage\(app,"verifying"/);
  assert.match(engine,/UploadStore\.updateConfirmedBytes/);
  assert.match(engine,/UploadStore\.changeState\(app,"done"/);
  assert.match(java("UploadController"),/UploadStore\.changeState\(context,"cancelled"/);
});
test("New APK retains Studio package, signature compatibility and increments version",()=>{
  const gradle=readFileSync("studio/build.gradle.kts","utf8");
  assert.match(gradle,/applicationId = "et\.addisreel\.studio"/);
  assert.match(gradle,/versionCode = 2/);
  assert.match(gradle,/versionName = "2\.0\.0"/);
  assert.match(gradle,/signingConfigs/);
});

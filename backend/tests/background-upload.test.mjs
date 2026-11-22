import test from "node:test";
import assert from "node:assert/strict";
import { wizardScript } from "../wizard.js";
import { studioHtml } from "../studio.js";
import { serviceWorker } from "../pwa.js";

test("Studio provides visible progress and direct pause/resume controls across pages",()=>{
  for(const id of ["wPauseUpload","wBackgroundUploadBar","wBackgroundUploadText",
    "wBackgroundUploadProgress","wBackgroundUploadAction","wBackgroundUploadView"]) {
    assert.match(studioHtml,new RegExp('id="'+id+'"'));
  }
  assert.match(studioHtml,/Android keeps Studio running/);
  assert.match(studioHtml,/reselect the same files/);
});
test("every MP4 routes through resumable storage, including small episodes",()=>{
  assert.match(wizardScript,/return multipartMp4\(file,number\)/);
  assert.doesNotMatch(wizardScript,/if\(file.size>smallFileBytes\)/);
  assert.match(wizardScript,/localStorage\.setItem\(stateKey/);
  assert.match(wizardScript,/localStorage\.setItem\(batchKey/);
});
test("upload lifecycle never aborts automatically on minimizing",()=>{
  assert.match(wizardScript,/visibilitychange/);
  assert.match(wizardScript,/pagehide/);
  assert.doesNotMatch(wizardScript,/visibilitychange[^\n]*\.abort\(/);
  assert.match(wizardScript,/if\(activeXhr\)activeXhr\.abort\(\)/);
  assert.match(wizardScript,/waitForOnline/);
  assert.match(wizardScript,/pauseRequested/);
});
test("no credentials or video data persisted in local browser checkpoint",()=>{
  assert.doesNotMatch(wizardScript,/localStorage\.setItem\([^,]+,\s*file\)/);
  assert.doesNotMatch(wizardScript,/localStorage\.setItem\([^,]+,\s*blob\)/);
  assert.doesNotMatch(wizardScript,/localStorage\.setItem\([^,]+,\s*token\)/);
  assert.match(wizardScript,/fingerprint=f=>\(\{name:f\.name,size:f\.size,modified:f\.lastModified\}\)/);
});
test("new PWA version invalidates stale client cache",()=>{
  assert.match(serviceWorker,/addisreel-admin-pwa-v6/);
  assert.doesNotThrow(()=>new Function(wizardScript));
});

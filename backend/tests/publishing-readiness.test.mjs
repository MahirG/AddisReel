import test from "node:test";
import assert from "node:assert/strict";
import { publishingReadiness } from "../publishing-readiness.js";

const series={title:"The Last Coffee Ceremony",genre:"Family",description:"A family reunites.",licensed:true};
const episode={number:1,title:"Homecoming",licensed:true};
test("complete licensed episode passes advisory publishing preflight",()=>{
 assert.deepEqual(publishingReadiness(series,episode,{posterPresent:true,hlsMasterPresent:true}),{ready:true,issues:[]});
});
test("unlicensed media never passes preflight",()=>{
 const result=publishingReadiness({...series,licensed:false},{...episode,licensed:false},{posterPresent:true,hlsMasterPresent:true});
 assert.equal(result.ready,false);
 assert.equal(result.issues.filter(x=>x.includes("rights")).length,2);
});
test("missing poster and HLS are reported separately",()=>{
 const result=publishingReadiness(series,episode);
 assert.equal(result.ready,false);
 assert.ok(result.issues.some(x=>x.includes("poster")));
 assert.ok(result.issues.some(x=>x.includes("master.m3u8")));
});
test("missing series and episode do not throw",()=>{
 assert.equal(publishingReadiness(null,null).ready,false);
 assert.ok(publishingReadiness(series,null).issues.some(x=>x.includes("episode")));
});

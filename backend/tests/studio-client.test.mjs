import test from "node:test";
import assert from "node:assert/strict";
import vm from "node:vm";
import { clientScript } from "../client.js";
import { studioHtml } from "../studio.js";
import { parseMovieFields } from "../importer.js";

function browser(initial,records={series:[],episodes:[]}) {
  const elements=new Map(),calls=[];
  const createNode=()=>({
    hidden:false,value:"",textContent:"",checked:false,disabled:false,
    dataset:{},classList:{toggle(){}},handlers:{},
    addEventListener(name,fn){this.handlers[name]=fn;},
    replaceChildren(){this.children=[];},append(...xs){this.children=(this.children||[]).concat(xs);},
    setAttribute(){},focus(){}
  });
  const el=id=>{
    if(!elements.has(id))elements.set(id,createNode());
    return elements.get(id);
  };
  async function fetch(path,opts={}) {
    calls.push({path,method:opts.method||"GET"});
    let json,code=200;
    if(path==="/v1/auth/session")json=initial;
    else if(path==="/v1/admin/series")json=records;
    else if(path==="/v1/auth/logout")json={authenticated:false};
    else if(path==="/v1/auth/login")json={authenticated:true,email:"admin@example.com"};
    else if(path==="/v1/auth/setup")json={authenticated:true,email:"admin@example.com"};
    else {json={saved:true};code=200}
    return {ok:true,status:code,async json(){return json}};
  }
  const doc={readyState:"complete",getElementById:el,querySelectorAll(){return[]}};
  const window={parseMovieFields,scrollTo(){},addEventListener(){}};
  vm.runInNewContext(clientScript,{document:doc,window,navigator:{},fetch,Blob,console});
  const settle=()=>new Promise(resolve=>setTimeout(resolve,10));
  return {el,calls,settle};
}

test("PWA has setup/login/account screens and no token storage",()=>{
  for(const id of ["setupForm","loginForm","logoutButton","accountEmail","importText","saveImported"])
    assert.match(studioHtml,new RegExp('id="'+id+'"'));
  assert.match(studioHtml,/manifest.webmanifest/);
  assert.match(studioHtml,/data-page="overview"/);
  assert.match(studioHtml,/data-page="library"/);
  assert.match(studioHtml,/data-page="media"/);
  assert.doesNotMatch(clientScript,/localStorage|sessionStorage/);
});

test("fresh owner sees one-time account setup, with password-only sign-in after setup",async()=>{
 const app=browser({authenticated:false,setupRequired:true});
 await app.settle();
 assert.equal(app.el("authScreen").hidden,false);
 assert.equal(app.el("setupForm").hidden,false);
 assert.equal(app.el("loginForm").hidden,true);
 app.el("setupToken").value="token-that-should-never-be-stored";
 app.el("setupEmail").value="admin@example.com";
 app.el("setupPassword").value="a-long-private-password";
 await app.el("setupForm").handlers.submit({preventDefault(){}});
 await app.settle();
 assert.equal(app.el("setupToken").value,"");
 assert.equal(app.el("setupPassword").value,"");
 assert.equal(app.el("appShell").hidden,false);
 assert.equal(app.el("authScreen").hidden,true);
 assert.ok(app.calls.some(c=>c.path==="/v1/auth/setup"));
});

test("authenticated device restores Studio without requesting token on refresh",async()=>{
 const app=browser({authenticated:true,email:"admin@example.com",setupRequired:false});
 await app.settle();
 assert.equal(app.el("appShell").hidden,false);
 assert.equal(app.el("authScreen").hidden,true);
 assert.equal(app.el("accountEmail").textContent,"admin@example.com");
 assert.ok(app.calls.some(c=>c.path==="/v1/auth/session"));
 assert.ok(app.calls.some(c=>c.path==="/v1/admin/series"));
 assert.ok(!app.calls.some(c=>c.path==="/v1/auth/login"||c.path==="/v1/auth/setup"));
 await app.el("logoutButton").handlers.click();
 assert.equal(app.el("appShell").hidden,true);
 assert.equal(app.el("loginForm").hidden,false);
 assert.ok(app.calls.some(c=>c.path==="/v1/auth/logout"));
});

test("Smart Paste reuses already-saved titles rather than overwriting on the new UI",async()=>{
 const series={id:"the-last-coffee-ceremony",title:"The Last Coffee Ceremony",genre:"Family",
   description:"A story",status:"published",licensed:0,sort_order:3};
 const episode={series_id:series.id,number:1,title:"Homecoming",status:"published",licensed:0};
 const app=browser({authenticated:true,email:"admin@example.com"}, {series:[series],episodes:[episode]});
 await app.settle();
 const copied=[
   "Series slug: the-last-coffee-ceremony","Title: The Last Coffee Ceremony",
   "Genre: Family","Description: Three siblings reunite.",
   "Episode number: 1","Episode title: Homecoming"].join("\n");
 app.el("importText").value=copied;
 app.el("fillMovie").handlers.click();
 assert.equal(app.el("saveImported").disabled,false);
 await app.el("saveImported").handlers.click();
 assert.match(app.el("importStatus").textContent,/Already saved/i);
 assert.equal(app.calls.filter(c=>c.method==="POST"&&c.path.startsWith("/v1/admin/")).length,0);
 assert.equal(series.status,"published");
});

import test from "node:test";
import assert from "node:assert/strict";
import { webcrypto } from "node:crypto";
import { handleAuth, requireAdmin } from "../auth.js";
if (!globalThis.crypto) globalThis.crypto = webcrypto;

const base = "https://addisreel-catalog.example.test";
function fakeDB() {
  const state={account:null,sessions:new Map()};
  const DB={
    prepare(sql) {
      let vals=[];
      return {
        bind(...args){vals=args;return this;},
        async first(){
          if(sql.includes("FROM admin_account WHERE id=1"))return state.account;
          if(sql.includes("FROM admin_sessions s JOIN admin_account")) {
            const s=state.sessions.get(vals[0]);
            return s&&s.expires_at>vals[1]?{id:1,email:state.account.email,expires_at:s.expires_at}:null;
          }
          if(sql.includes("SELECT")&&sql.includes("series"))return null;
          throw Error("Unexpected SELECT: "+sql);
        },
        async all() {return {results:[]};},
        async run(){
          if(sql.startsWith("INSERT INTO admin_account")){
            if(state.account)throw Error("Account exists");
            state.account={id:1,email:vals[0],password_salt:vals[1],password_hash:vals[2],failed_attempts:0,locked_until:0};return {};
          }
          if(sql.startsWith("INSERT INTO admin_sessions")){
            state.sessions.set(vals[0],{expires_at:vals[1],created_at:vals[2],last_seen_at:vals[3]});return {};
          }
          if(sql.startsWith("DELETE FROM admin_sessions WHERE expires_at")) {
            for(const [key,s] of state.sessions)if(s.expires_at<vals[0])state.sessions.delete(key);
            return {};
          }
          if(sql.startsWith("DELETE FROM admin_sessions WHERE token_hash")){state.sessions.delete(vals[0]);return {}}
          if(sql.startsWith("UPDATE admin_sessions SET expires_at")){
            const ses=state.sessions.get(vals[2]);if(ses){ses.expires_at=vals[0];ses.last_seen_at=vals[1]};return {};
          }
          if(sql.startsWith("UPDATE admin_account SET failed_attempts=0")){
            state.account.failed_attempts=0;state.account.locked_until=0;return {};
          }
          if(sql.startsWith("UPDATE admin_account SET failed_attempts=failed_attempts+1")){
            state.account.failed_attempts+=1;
            if(state.account.failed_attempts>=5)state.account.locked_until=vals[0];return {};
          }
          throw Error("Unexpected SQL: "+sql);
        }
      };
    }
  };
  return {DB,state};
}
const options=(path,method="GET",body={},cookie=null,origin=base)=>{
  const headers={};
  if(method!=="GET") {headers["Origin"]=origin;headers["Content-Type"]="application/json";}
  if(cookie)headers.Cookie=cookie;
  return new Request(base+path,{method,headers,body:method==="GET"?undefined:JSON.stringify(body)});
};
const token="P".repeat(48);
const email="owner@example.com";
const password="A unique long passphrase 2026!";
const passwordProof="a".repeat(64);
const passwordSalt="AbCdEfGhIjKlMnOpQrStUv";
const setupPayload={setupToken:token,email,passwordProof,passwordSalt,passwordLength:password.length};
const authPepper="server-side-test-pepper-which-is-not-a-production-secret";
test("owner account setup exchanges token for an HttpOnly persistent session",async()=>{
  const {DB,state}=fakeDB(),env={DB,ADMIN_TOKEN:token,AUTH_PEPPER:authPepper};
  const initial=await handleAuth(options("/v1/auth/session"),env,new URL(base+"/v1/auth/session"));
  assert.deepEqual(await initial.json(),{authenticated:false,setupRequired:true});

  const request=options("/v1/auth/setup","POST",setupPayload);
  const result=await handleAuth(request,env,new URL(request.url));
  assert.equal(result.status,201);
  const cookie=result.headers.get("set-cookie");
  assert.match(cookie,/^__Host-addisreel_session=/);
  assert.match(cookie,/Secure/);assert.match(cookie,/HttpOnly/);assert.match(cookie,/SameSite=Strict/);
  assert.match(cookie,/Max-Age=7776000/);
  assert.equal(state.account.email,email);
  assert.notEqual(state.account.password_hash,password);
  assert.notEqual(state.account.password_hash,passwordProof);
  assert.notEqual(state.account.password_hash,token);
  assert.ok(!JSON.stringify(state).includes(token));
  const cookieValue=cookie.split(";")[0];

  const reload=options("/v1/auth/session","GET",undefined,cookieValue);
  const session=await handleAuth(reload,env,new URL(reload.url));
  assert.equal((await session.json()).authenticated,true);

  const admin=options("/v1/admin/series","GET",undefined,cookieValue);
  const access=await requireAdmin(admin,env,new URL(admin.url));
  assert.equal(access.user.email,email);
  const csrf=options("/v1/admin/series","POST",{},cookieValue,"https://untrusted.test");
  const blocked=await requireAdmin(csrf,env,new URL(csrf.url));
  assert.equal(blocked.error.status,403);

  const signOut=options("/v1/auth/logout","POST",{},cookieValue);
  const logout=await handleAuth(signOut,env,new URL(signOut.url));
  assert.equal(logout.status,200);
  assert.match(logout.headers.get("set-cookie"),/Max-Age=0/);
  const after=await handleAuth(reload,env,new URL(reload.url));
  assert.deepEqual(await after.json(),{authenticated:false,setupRequired:false});

  const signIn=options("/v1/auth/login","POST",{email,passwordProof});
  const login=await handleAuth(signIn,env,new URL(signIn.url));
  assert.equal(login.status,200);
  assert.match(login.headers.get("set-cookie"),/HttpOnly/);
  assert.equal(state.sessions.size,1);
});

test("setup token cannot create another admin and cannot bypass logged-in session",async()=>{
 const {DB}=fakeDB(),env={DB,ADMIN_TOKEN:token,AUTH_PEPPER:authPepper};
 const initial=options("/v1/auth/setup","POST",{setupToken:token,email,password});
 assert.equal((await handleAuth(initial,env,new URL(initial.url))).status,201);
 const second=options("/v1/auth/setup","POST",{...setupPayload,email:"other@example.com"});
 assert.equal((await handleAuth(second,env,new URL(second.url))).status,409);
 const noCookie=new Request(base+"/v1/admin/series",{headers:{Authorization:"Bearer "+token}});
 const denied=await requireAdmin(noCookie,env,new URL(noCookie.url));
 assert.equal(denied.error.status,401);
});

test("password and setup flows enforce valid origin and strong passwords",async()=>{
 const {DB}=fakeDB(),env={DB,ADMIN_TOKEN:token,AUTH_PEPPER:authPepper};
 const wrongOrigin=options("/v1/auth/setup","POST",{setupToken:token,email,password},null,"https://attacker.example");
 assert.equal((await handleAuth(wrongOrigin,env,new URL(wrongOrigin.url))).status,403);
 const weak=options("/v1/auth/setup","POST",{...setupPayload,passwordLength:3});
 const response=await handleAuth(weak,env,new URL(weak.url));
 assert.equal(response.status,400);
 assert.match((await response.json()).error,/12/);
});

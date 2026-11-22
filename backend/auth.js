// AddisReel owner authentication. No admin token/password is stored in browser storage.
// Bootstrap with ADMIN_TOKEN once; afterwards use email+password and a HttpOnly session.
const COOKIE = "__Host-addisreel_session";
const SESSION_SECONDS = 90 * 24 * 60 * 60;
const encoder = new TextEncoder();
const nowSeconds = () => Math.floor(Date.now() / 1000);
const toHex = arr => Array.from(arr, n => n.toString(16).padStart(2, "0")).join("");
const bytes = count => crypto.getRandomValues(new Uint8Array(count));
const base64url = input => btoa(String.fromCharCode(...input)).replaceAll("+","-").replaceAll("/","_").replace(/=+$/,"");
const decode64 = input => Uint8Array.from(atob(input.replaceAll("-","+").replaceAll("_","/")), c => c.charCodeAt(0));
const parseCookies = request => {
  const cookie = request.headers.get("Cookie") || "";
  const value = cookie.split(";").map(item=>item.trim()).find(item=>item.startsWith(COOKIE+"="));
  return value ? value.slice(COOKIE.length+1) : null;
};
const setCookie = token => COOKIE+"="+token+"; Path=/; HttpOnly; Secure; SameSite=Strict; Max-Age="+SESSION_SECONDS;
const clearCookie = () => COOKIE+"=; Path=/; HttpOnly; Secure; SameSite=Strict; Max-Age=0";
const json = (data, status=200, cookie) => new Response(JSON.stringify(data), {
  status,headers:{"Content-Type":"application/json; charset=utf-8","Cache-Control":"no-store",
    "X-Content-Type-Options":"nosniff",...(cookie?{"Set-Cookie":cookie}:{})}
});
async function digest(input) {
  const hash=await crypto.subtle.digest("SHA-256",encoder.encode(input));
  return toHex(new Uint8Array(hash));
}
// Password stretching runs in the owner browser so Cloudflare's free-tier
// 10ms CPU limit cannot abort setup. A separate server-only pepper authenticates
// the derived proof; neither the clear password nor the proof is stored in D1.
async function protectedProof(proof,env) {
  if(!env.AUTH_PEPPER || env.AUTH_PEPPER.length<32) throw Error("Admin password service not configured");
  const key=await crypto.subtle.importKey("raw",encoder.encode(env.AUTH_PEPPER),
    {name:"HMAC",hash:"SHA-256"},false,["sign"]);
  const signature=await crypto.subtle.sign("HMAC",key,encoder.encode(proof));
  return toHex(new Uint8Array(signature));
}
function proofValid(value) {return typeof value==="string"&&/^[0-9a-f]{64}$/.test(value);}
function saltValid(value) {return typeof value==="string"&&/^[A-Za-z0-9_-]{22}$/.test(value);}
async function equalString(a,b) {
  if (typeof a!=="string" || typeof b!=="string" || a.length !== b.length) return false;
  const hashes=await Promise.all([digest(a),digest(b)]);
  let mismatch=0;
  for (let i=0;i<hashes[0].length;i++) mismatch|=hashes[0].charCodeAt(i)^hashes[1].charCodeAt(i);
  return mismatch===0;
}
async function bodyJSON(request) {
  const length=Number(request.headers.get("Content-Length")||0);
  if(length>4096) throw new Error("Request too large");
  const value=await request.json();
  if(!value || Array.isArray(value) || typeof value!=="object") throw new Error("Invalid request");
  return value;
}
async function owner(env) {
  return env.DB.prepare("SELECT id,email,password_salt,password_hash,failed_attempts,locked_until FROM admin_account WHERE id=1").first();
}
async function newSession(env) {
  const token=base64url(bytes(32));
  const hash=await digest(token);
  const now=nowSeconds();
  await env.DB.prepare("DELETE FROM admin_sessions WHERE expires_at < ?").bind(now).run();
  await env.DB.prepare("INSERT INTO admin_sessions(token_hash,account_id,expires_at,created_at,last_seen_at) VALUES (?,1,?,?,?)")
    .bind(hash,now+SESSION_SECONDS,now,now).run();
  return setCookie(token);
}
export async function sessionUser(request,env) {
  const token=parseCookies(request);
  if(!token || !/^[\w-]{40,100}$/.test(token))return null;
  const hash=await digest(token), now=nowSeconds();
  const s=await env.DB.prepare("SELECT a.id,a.email,s.expires_at FROM admin_sessions s JOIN admin_account a ON a.id=s.account_id WHERE s.token_hash=? AND s.expires_at>?")
    .bind(hash,now).first();
  return s ? {id:s.id,email:s.email,expiry:s.expires_at,sessionHash:hash} : null;
}
export async function requireAdmin(request,env,url) {
  // SameSite=Strict cookie + exact Origin validation protects state-changing requests.
  if(request.method!=="GET" && request.headers.get("Origin")!==url.origin)return {error:json({error:"Invalid request origin"},403)};
  const user=await sessionUser(request,env);
  if(!user) return {error:json({error:"Sign in to AddisReel Studio"},401)};
  return {user};
}
export async function handleAuth(request,env,url) {
  const path=url.pathname,method=request.method;
  if(method!=="GET" && request.headers.get("Origin")!==url.origin)
    return json({error:"Invalid request origin"},403);
  if(method==="GET" && path==="/v1/auth/session") {
    const [account,user]=await Promise.all([owner(env),sessionUser(request,env)]);
    if(!user)return json({authenticated:false,setupRequired:!account});
    let cookie;
    if(user.expiry < nowSeconds()+60*24*60*60) {
      const now=nowSeconds();
      await env.DB.prepare("UPDATE admin_sessions SET expires_at=?,last_seen_at=? WHERE token_hash=?")
        .bind(now+SESSION_SECONDS,now,user.sessionHash).run();
      cookie=setCookie(parseCookies(request));
    }
    return json({authenticated:true,email:user.email,setupRequired:false},200,cookie);
  }
  if(method==="GET" && path==="/v1/auth/salt") {
    const account=await owner(env);
    return json({salt:account?.password_salt||base64url(bytes(16)),iterations:180000});
  }
  if(method==="POST" && path==="/v1/auth/setup") {
    if(await owner(env))return json({error:"Admin account already exists"},409);
    const data=await bodyJSON(request);
    if(!env.ADMIN_TOKEN || typeof data.setupToken!=="string" ||
       data.setupToken.length<32 || !await equalString(data.setupToken,env.ADMIN_TOKEN))
      return json({error:"Invalid setup token"},403);
    const email=typeof data.email==="string"?data.email.trim().toLowerCase():"";
    const salt=data.passwordSalt;
    const proof=data.passwordProof;
    if(!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email)||email.length>254)
      return json({error:"Enter a valid email address"},400);
    if(!Number.isInteger(data.passwordLength)||data.passwordLength<12||data.passwordLength>128||
       !saltValid(salt)||!proofValid(proof))
      return json({error:"Invalid password setup. Enter 12–128 characters."},400);
    if(!env.AUTH_PEPPER||env.AUTH_PEPPER.length<32)
      return json({error:"Password service not configured"},503);
    const passwordHash=await protectedProof(proof,env);
    try {
      await env.DB.prepare("INSERT INTO admin_account(id,email,password_salt,password_hash,failed_attempts,locked_until) VALUES(1,?,?,?,0,0)")
        .bind(email,salt,passwordHash).run();
    }catch(error){
      return json({error:"Admin account already exists"},409);
    }
    const cookie=await newSession(env);
    return json({authenticated:true,email},201,cookie);
  }
  if(method==="POST" && path==="/v1/auth/login") {
    const data=await bodyJSON(request);
    const email=typeof data.email==="string"?data.email.trim().toLowerCase():"";
    const proof=data.passwordProof;
    if(email.length>254 || !proofValid(proof))return json({error:"Invalid credentials"},401);
    if(!env.AUTH_PEPPER||env.AUTH_PEPPER.length<32)
      return json({error:"Password service not configured"},503);
    const account=await owner(env);
    if(!account || account.locked_until>nowSeconds())
      return json({error:"Invalid credentials or temporarily locked. Try later."},401);
    const valid=email===account.email && await equalString(await protectedProof(proof,env),account.password_hash);
    if(!valid) {
      await env.DB.prepare("UPDATE admin_account SET failed_attempts=failed_attempts+1,locked_until=CASE WHEN failed_attempts+1>=5 THEN ? ELSE 0 END WHERE id=1")
        .bind(nowSeconds()+15*60).run();
      return json({error:"Invalid credentials or temporarily locked. Try later."},401);
    }
    await env.DB.prepare("UPDATE admin_account SET failed_attempts=0,locked_until=0 WHERE id=1").run();
    const cookie=await newSession(env);
    return json({authenticated:true,email},200,cookie);
  }
  if(method==="POST" && path==="/v1/auth/logout") {
    const session=await sessionUser(request,env);
    if(session) await env.DB.prepare("DELETE FROM admin_sessions WHERE token_hash=?").bind(session.sessionHash).run();
    return json({authenticated:false},200,clearCookie());
  }
  return json({error:"Auth route not found"},404);
}

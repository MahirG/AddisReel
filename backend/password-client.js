// Strong password stretching in the browser prevents exceeding Worker CPU limits.
// A derived proof is transmitted only over TLS, never persisted in web storage.
// The backend stores HMAC(AUTH_PEPPER, proof) instead of either password or proof.
export function newSalt() {
  const bytes=crypto.getRandomValues(new Uint8Array(16));
  return btoa(String.fromCharCode(...bytes))
    .replaceAll("+","-").replaceAll("/","_").replace(/=+$/,"");
}
export async function deriveProof(password,salt) {
  if(typeof password!=="string"||password.length<12||password.length>128)
    throw Error("Password must contain 12–128 characters.");
  if(typeof salt!=="string"||!/^[A-Za-z0-9_-]{22}$/.test(salt))
    throw Error("Invalid password challenge.");
  const bytes=Uint8Array.from(atob(salt.replaceAll("-","+").replaceAll("_","/")),c=>c.charCodeAt(0));
  const key=await crypto.subtle.importKey("raw",new TextEncoder().encode(password),
    {name:"PBKDF2"},false,["deriveBits"]);
  const bits=await crypto.subtle.deriveBits({
    name:"PBKDF2",salt:bytes,iterations:180000,hash:"SHA-256"
  },key,256);
  return Array.from(new Uint8Array(bits),b=>b.toString(16).padStart(2,"0")).join("");
}

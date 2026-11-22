import test from "node:test";
import assert from "node:assert/strict";
import {webcrypto} from "node:crypto";
import {newSalt,deriveProof} from "../password-client.js";
if(!globalThis.crypto) globalThis.crypto=webcrypto;

test("browser PBKDF2 derives a repeatable, strong proof using a random salt",async()=>{
 const salt=newSalt();
 assert.match(salt,/^[A-Za-z0-9_-]{22}$/);
 const password="A strong long demonstration password 2026";
 const a=await deriveProof(password,salt);
 const b=await deriveProof(password,salt);
 assert.match(a,/^[0-9a-f]{64}$/);
 assert.equal(a,b);
 assert.notEqual(await deriveProof(password,newSalt()),a);
 assert.notEqual(await deriveProof(password+" extra",salt),a);
});

test("browser refuses weak passwords and broken salt input",async()=>{
 await assert.rejects(deriveProof("123","AbCdEfGhIjKlMnOpQrStUv"),/12/);
 await assert.rejects(deriveProof("A strong 2026 password","oops"),/challenge/);
});

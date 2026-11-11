// Runs the Worker against an in-memory KV. `node test/relay.test.js`
import worker from "../src/index.js";

const store = new Map();
const KV = {
  async get(k, opt) {
    if (!store.has(k)) return null;
    const v = store.get(k);
    if (opt?.type === "json") return JSON.parse(v);
    if (opt?.type === "stream") return new Response(v).body;
    return v;
  },
  async put(k, v) { store.set(k, typeof v === "string" ? v : new Uint8Array(v)); },
  async delete(k) { store.delete(k); },
};
const env = { KV };
const B = "https://relay.test";
let fails = 0;
const check = (name, ok, info = "") => { console.log((ok ? "PASS " : "FAIL ") + name, info); if (!ok) fails++; };
const call = (path, { method = "GET", body, who, raw } = {}) => {
  const headers = {};
  if (who) { headers["x-cratez-code"] = who.code; headers["x-cratez-secret"] = who.secret; }
  if (body && !raw) headers["content-type"] = "application/json";
  return worker.fetch(new Request(B + path, { method, headers, body: raw ? body : body ? JSON.stringify(body) : undefined }), env);
};

const A = { code: "CRZ-AAAA-1111", secret: "a".repeat(32), name: "Alice" };
const Z = { code: "CRZ-ZZZZ-9999", secret: "z".repeat(32), name: "Zed" };
const E = { code: "CRZ-EEEE-6666", secret: "e".repeat(32) };

check("health", (await call("/healthz")).status === 200);
check("register A", (await call("/v1/register", { method: "POST", body: A })).status === 200);
check("register Z", (await call("/v1/register", { method: "POST", body: Z })).status === 200);
check("hijack blocked", (await call("/v1/register", { method: "POST", body: { ...A, secret: "x".repeat(32) } })).status === 409);
check("bad auth", (await call("/v1/inbox", { who: { ...A, secret: "b".repeat(32) } })).status === 401);
const lk = await (await call("/v1/lookup/CRZ-ZZZZ-9999", { who: A })).json();
check("lookup", lk.exists && lk.name === "Zed", JSON.stringify(lk));

const data = new Uint8Array(1000).map((_, i) => i % 251);
check("upload chunk", (await call("/v1/blob/track0001/0", { method: "PUT", who: A, raw: true, body: data })).status === 200);
check("upload art", (await call("/v1/blob/track0001/art", { method: "PUT", who: A, raw: true, body: new Uint8Array([1, 2, 3]) })).status === 200);
check("send to unknown", (await call("/v1/send", { method: "POST", who: A, body: { to: E.code, items: [{ id: "track0001", chunks: 1, size: 1000 }] } })).status === 404);
const s = await call("/v1/send", { method: "POST", who: A, body: { to: Z.code, items: [{ id: "track0001", chunks: 1, size: 1000, meta: { title: "Losing It", hasArt: true } }] } });
check("send", s.status === 200, await s.text());
const inbox = await (await call("/v1/inbox", { who: Z })).json();
check("inbox", inbox.items.length === 1 && inbox.items[0].fromName === "Alice" && inbox.items[0].meta.title === "Losing It");
const dl = new Uint8Array(await (await call("/v1/blob/CRZ-AAAA-1111/track0001/0", { who: Z })).arrayBuffer());
check("download", dl.length === 1000 && dl[250] === 250);
check("art", (await call("/v1/blob/CRZ-AAAA-1111/track0001/art", { who: Z })).status === 200);
check("stranger can't download", (await call("/v1/blob/CRZ-AAAA-1111/track0001/0", { who: A })).status === 404);
const ack = await (await call("/v1/ack", { method: "POST", who: Z, body: { items: [{ from: A.code, id: "track0001" }] } })).json();
check("ack", ack.removed === 1 && !store.has("b:CRZ-AAAA-1111:track0001:0") && !store.has("b:CRZ-AAAA-1111:track0001:art"));
check("inbox empty", (await (await call("/v1/inbox", { who: Z })).json()).items.length === 0);
console.log(fails ? `${fails} FAILED` : "ALL PASS");
process.exit(fails ? 1 : 0);

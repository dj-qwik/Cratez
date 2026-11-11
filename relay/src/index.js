/**
 * Cratez relay — lets friends send tracks to each other's friend code over the internet.
 *
 * Runs on Cloudflare Workers (free plan) with one KV namespace bound as `KV`.
 * Files are stored in chunks (<= 24 MiB each) and expire automatically after 7 days,
 * or as soon as the recipient's phone has downloaded them.
 *
 * Keys:
 *   dev:<code>              { h: sha256(secret), name, t }       a registered phone
 *   idx:<code>              [ { from, fromName, id, chunks, size, meta, t } ]   that phone's inbox
 *   b:<from>:<id>:<n>       raw bytes of chunk n of an uploaded file (n = "art" for its cover art)
 */

const TTL = 7 * 24 * 3600;
const MAX_CHUNK = 24 * 1024 * 1024;
const MAX_CHUNKS = 12; // ~288 MB per file
const MAX_INBOX = 300;
const CODE_RE = /^CRZ-[A-Z0-9]{4}-[A-Z0-9]{4}$/;
const ID_RE = /^[A-Za-z0-9_-]{8,40}$/;

const json = (data, status = 200) =>
  new Response(JSON.stringify(data), { status, headers: { "content-type": "application/json" } });
const fail = (status, error) => json({ ok: false, error }, status);

async function sha256(text) {
  const d = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(text));
  return [...new Uint8Array(d)].map((b) => b.toString(16).padStart(2, "0")).join("");
}

async function auth(request, env) {
  const code = (request.headers.get("x-cratez-code") || "").toUpperCase();
  const secret = request.headers.get("x-cratez-secret") || "";
  if (!CODE_RE.test(code) || secret.length < 16) return null;
  const dev = await env.KV.get(`dev:${code}`, { type: "json" });
  if (!dev || dev.h !== (await sha256(secret))) return null;
  return { code, name: dev.name || code };
}

async function readInbox(env, code) {
  const list = (await env.KV.get(`idx:${code}`, { type: "json" })) || [];
  const cutoff = Date.now() - TTL * 1000;
  return list.filter((i) => i.t > cutoff);
}

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    const parts = url.pathname.split("/").filter(Boolean);
    const method = request.method;

    try {
      if (parts[0] === "healthz" || url.pathname === "/") return json({ ok: true, service: "cratez-relay" });
      if (parts[0] !== "v1") return fail(404, "not found");
      const route = parts[1];

      // ---- Register this phone's friend code (first claim wins) ----
      if (route === "register" && method === "POST") {
        const body = await request.json();
        const code = String(body.code || "").toUpperCase();
        const secret = String(body.secret || "");
        const name = String(body.name || "").slice(0, 32);
        if (!CODE_RE.test(code) || secret.length < 16) return fail(400, "bad code or secret");
        const h = await sha256(secret);
        const existing = await env.KV.get(`dev:${code}`, { type: "json" });
        if (existing && existing.h !== h) return fail(409, "code already claimed");
        if (!existing || existing.name !== name) await env.KV.put(`dev:${code}`, JSON.stringify({ h, name, t: Date.now() }));
        return json({ ok: true });
      }

      const me = await auth(request, env);
      if (!me) return fail(401, "unauthorized");

      // ---- Does a friend code exist? ----
      if (route === "lookup" && method === "GET") {
        const code = String(parts[2] || "").toUpperCase();
        if (!CODE_RE.test(code)) return fail(400, "bad code");
        const dev = await env.KV.get(`dev:${code}`, { type: "json" });
        return json({ ok: true, exists: !!dev, name: dev ? dev.name : null });
      }

      // ---- Upload one chunk of a file:  PUT /v1/blob/<id>/<n> ----
      if (route === "blob" && method === "PUT") {
        const id = parts[2]; const art = parts[3] === "art"; const n = art ? "art" : Number(parts[3]);
        if (!ID_RE.test(id || "") || (!art && (!Number.isInteger(n) || n < 0 || n >= MAX_CHUNKS))) return fail(400, "bad blob path");
        const buf = await request.arrayBuffer();
        if (buf.byteLength === 0 || buf.byteLength > (art ? 1024 * 1024 : MAX_CHUNK)) return fail(413, "chunk too large");
        await env.KV.put(`b:${me.code}:${id}:${n}`, buf, { expirationTtl: TTL });
        return json({ ok: true, bytes: buf.byteLength });
      }

      // ---- Deliver uploaded files to a friend's inbox ----
      if (route === "send" && method === "POST") {
        const body = await request.json();
        const to = String(body.to || "").toUpperCase();
        if (!CODE_RE.test(to)) return fail(400, "bad recipient");
        const target = await env.KV.get(`dev:${to}`, { type: "json" });
        if (!target) return fail(404, "That friend hasn't turned on Cratez online sharing yet");
        const items = Array.isArray(body.items) ? body.items : [];
        if (items.length === 0 || items.length > 50) return fail(400, "bad items");
        const now = Date.now();
        const clean = [];
        for (const it of items) {
          const chunks = Number(it.chunks);
          if (!ID_RE.test(String(it.id || "")) || !Number.isInteger(chunks) || chunks < 1 || chunks > MAX_CHUNKS) return fail(400, "bad item");
          const meta = JSON.stringify(it.meta || {});
          if (meta.length > 20000) return fail(413, "metadata too large");
          clean.push({ from: me.code, fromName: me.name, id: String(it.id), chunks, size: Number(it.size) || 0, meta: it.meta || {}, t: now });
        }
        const inbox = (await readInbox(env, to)).concat(clean).slice(-MAX_INBOX);
        await env.KV.put(`idx:${to}`, JSON.stringify(inbox), { expirationTtl: TTL });
        return json({ ok: true, delivered: clean.length, toName: target.name });
      }

      // ---- My inbox ----
      if (route === "inbox" && method === "GET") {
        return json({ ok: true, items: await readInbox(env, me.code) });
      }

      // ---- Download a chunk sent to me:  GET /v1/blob/<from>/<id>/<n> ----
      if (route === "blob" && method === "GET") {
        const from = String(parts[2] || "").toUpperCase(); const id = parts[3];
        const art = parts[4] === "art"; const n = art ? "art" : Number(parts[4]);
        if (!CODE_RE.test(from) || !ID_RE.test(id || "") || (!art && !Number.isInteger(n))) return fail(400, "bad blob path");
        const inbox = await readInbox(env, me.code);
        const item = inbox.find((i) => i.from === from && i.id === id);
        if (!item || (!art && (n < 0 || n >= item.chunks))) return fail(404, "not in your inbox");
        const stream = await env.KV.get(`b:${from}:${id}:${n}`, { type: "stream" });
        if (!stream) return fail(410, "file expired");
        return new Response(stream, { headers: { "content-type": "application/octet-stream" } });
      }

      // ---- Downloaded: remove from inbox and free the storage ----
      if (route === "ack" && method === "POST") {
        const body = await request.json();
        const done = Array.isArray(body.items) ? body.items : [];
        const inbox = await readInbox(env, me.code);
        const isDone = (i) => done.some((d) => String(d.from).toUpperCase() === i.from && d.id === i.id);
        const removed = inbox.filter(isDone);
        await env.KV.put(`idx:${me.code}`, JSON.stringify(inbox.filter((i) => !isDone(i))), { expirationTtl: TTL });
        for (const r of removed) {
          for (let n = 0; n < r.chunks; n++) await env.KV.delete(`b:${r.from}:${r.id}:${n}`);
          if (r.meta && r.meta.hasArt) await env.KV.delete(`b:${r.from}:${r.id}:art`);
        }
        return json({ ok: true, removed: removed.length });
      }

      return fail(404, "not found");
    } catch (e) {
      return fail(500, String(e && e.message ? e.message : e));
    }
  },
};

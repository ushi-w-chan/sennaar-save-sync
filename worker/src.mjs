const prefix = "sennaar/v1/";
const idPattern = /^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/;
const saveNamePattern = /^(?:journal|places)_([0-9]+)(?:_backup)?\.cos$/;
const sha256Pattern = /^[0-9a-f]{64}$/;
const maxBytes = 320 * 1024;

async function contentHash(files) {
  const names = Object.keys(files).sort();
  if (names.length > 32) return undefined;
  const entries = [];
  for (const name of names) {
    const entry = files[name];
    if (saveNamePattern.exec(name)?.[0] !== name || typeof entry?.sha256 !== "string" ||
        !sha256Pattern.test(entry.sha256) || typeof entry.data !== "string") return undefined;
    let decoded;
    try {
      decoded = atob(entry.data);
    } catch {
      return undefined;
    }
    const bytes = new Uint8Array(decoded.length);
    for (let index = 0; index < decoded.length; index++) bytes[index] = decoded.charCodeAt(index);
    if (await sha256(bytes) !== entry.sha256) return undefined;
    entries.push(`${name}:${entry.sha256}`);
  }
  return sha256(new TextEncoder().encode(entries.join("\n")));
}

async function sha256(bytes) {
  const digest = await crypto.subtle.digest("SHA-256", bytes);
  return Array.from(new Uint8Array(digest), (byte) => byte.toString(16).padStart(2, "0")).join("");
}

async function authorized(request, env) {
  const header = request.headers.get("Authorization");
  if (!env.SYNC_TOKEN || !header?.startsWith("Bearer ") || header.length > 520) return false;
  const digest = (value) => crypto.subtle.digest("SHA-256", new TextEncoder().encode(value));
  const [actual, expected] = await Promise.all([digest(header.slice(7)), digest(env.SYNC_TOKEN)]);
  return crypto.subtle.timingSafeEqual(actual, expected);
}

async function readBounded(request) {
  if (!request.body) return null;
  const reader = request.body.getReader();
  const chunks = [];
  let size = 0;
  while (true) {
    const { done, value } = await reader.read();
    if (done) break;
    size += value.length;
    if (size > maxBytes) {
      await reader.cancel();
      return null;
    }
    chunks.push(value);
  }
  const bytes = new Uint8Array(size);
  let offset = 0;
  for (const chunk of chunks) {
    bytes.set(chunk, offset);
    offset += chunk.length;
  }
  return bytes;
}

export default {
  async fetch(request, env) {
    if (!(await authorized(request, env))) return new Response(null, { status: 401 });
    const url = new URL(request.url);
    const headers = { "Cache-Control": "no-store" };
    if (url.pathname === "/snapshots" && request.method === "GET") {
      const result = await env.SAVES.list({
        prefix, limit: 100, include: ["customMetadata"],
        cursor: url.searchParams.get("cursor") || undefined,
      });
      return Response.json({
        items: result.objects.map(({ key, uploaded, customMetadata }) => ({
          id: key.slice(prefix.length),
          timestamp: uploaded.toISOString(),
          device: customMetadata?.device || "",
          slot: customMetadata?.slot,
          contentHash: customMetadata?.contentHash,
        })),
        cursor: result.truncated ? result.cursor : null,
      }, { headers });
    }
    const id = url.pathname.split("/")[2];
    if (url.pathname !== `/snapshots/${id}` || !idPattern.test(id ?? "")) {
      return new Response(null, { status: 404 });
    }
    const key = prefix + id;
    if (request.method === "DELETE") {
      await env.SAVES.delete(key);
      return new Response(null, { status: 204, headers });
    }
    if (request.method === "GET") {
      const object = await env.SAVES.get(key);
      return object
        ? new Response(object.body, { headers: { ...headers, "Content-Type": "application/json" } })
        : new Response(null, { status: 404 });
    }
    if (request.method === "PUT") {
      const bytes = await readBounded(request);
      if (!bytes) return new Response(null, { status: 413 });
      let snapshot;
      try {
        snapshot = JSON.parse(new TextDecoder().decode(bytes));
      } catch {
        return new Response(null, { status: 400 });
      }
      if (snapshot?.id !== id || (snapshot.version !== 1 && snapshot.version !== 2) ||
          snapshot.package !== "com.playdigious.chantsofsennaar.epic" ||
          typeof snapshot.device !== "string" || !Object.keys(snapshot.files ?? {}).length) {
        return new Response(null, { status: 400 });
      }
      const customMetadata = { device: snapshot.device.slice(0, 100) };
      if (snapshot.version === 2) {
        const { slot, files } = snapshot;
        if (typeof slot !== "string" || typeof files !== "object" ||
            !Object.keys(files).every((name) => {
              const match = saveNamePattern.exec(name);
              return match?.[0] === name && match[1] === slot;
            })) {
          return new Response(null, { status: 400 });
        }
        customMetadata.slot = slot;
      }
      const hash = await contentHash(snapshot.files);
      if (hash) customMetadata.contentHash = hash;
      const saved = await env.SAVES.put(key, bytes, {
        onlyIf: new Headers({ "If-None-Match": "*" }),
        customMetadata,
      });
      return new Response(null, { status: saved ? 201 : 412 });
    }
    return new Response(null, { status: 405 });
  },
};

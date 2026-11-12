const prefix = "sennaar/v1/";
const idPattern = /^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/;
const maxBytes = 320 * 1024;

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
        })),
        cursor: result.truncated ? result.cursor : null,
      }, { headers });
    }
    const id = url.pathname.split("/")[2];
    if (url.pathname !== `/snapshots/${id}` || !idPattern.test(id ?? "")) {
      return new Response(null, { status: 404 });
    }
    const key = prefix + id;
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
      if (snapshot?.id !== id || snapshot.version !== 1 ||
          snapshot.package !== "com.playdigious.chantsofsennaar.epic" ||
          typeof snapshot.device !== "string" || !Object.keys(snapshot.files ?? {}).length) {
        return new Response(null, { status: 400 });
      }
      const saved = await env.SAVES.put(key, bytes, {
        onlyIf: new Headers({ "If-None-Match": "*" }),
        customMetadata: { device: snapshot.device.slice(0, 100) },
      });
      return new Response(null, { status: saved ? 201 : 412 });
    }
    return new Response(null, { status: 405 });
  },
};

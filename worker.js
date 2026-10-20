// BVR TV — прокси для http-потоков (Cloudflare Worker)
// Использование: https://<имя-воркера>.workers.dev/?u=<ссылка на поток>

// Разрешены любые серверы с потоками, чтобы новые каналы добавлялись без правки этого кода.
// Если прокси начнут использовать чужие, сюда можно вернуть список разрешённых серверов.
const ALLOWED_HOSTS = null;

const CORS = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Methods": "GET, HEAD, OPTIONS",
  "Access-Control-Allow-Headers": "*",
};

function proxied(origin, absUrl) {
  return origin + "/?u=" + encodeURIComponent(absUrl);
}

function rewritePlaylist(text, baseUrl, origin) {
  return text.split("\n").map((line) => {
    const t = line.trim();
    if (!t) return line;
    if (t.startsWith("#")) {
      // ссылки внутри тегов, например #EXT-X-KEY:URI="..."
      return line.replace(/URI="([^"]+)"/g, (m, u) =>
        'URI="' + proxied(origin, new URL(u, baseUrl).href) + '"');
    }
    return proxied(origin, new URL(t, baseUrl).href);
  }).join("\n");
}

export default {
  async fetch(request) {
    if (request.method === "OPTIONS") return new Response(null, { headers: CORS });

    const reqUrl = new URL(request.url);
    const target = reqUrl.searchParams.get("u");
    if (!target) return new Response("BVR TV proxy работает (версия 2)", { headers: CORS });

    let t;
    try { t = new URL(target); } catch (e) {
      return new Response("Неверная ссылка", { status: 400, headers: CORS });
    }
    if (!/^https?:$/.test(t.protocol) || (ALLOWED_HOSTS && !ALLOWED_HOSTS.includes(t.hostname))) {
      return new Response("Этот сервер не разрешён", { status: 403, headers: CORS });
    }

    const upstream = await fetch(t.href, {
      headers: { "User-Agent": "Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.0 Mobile/15E148 Safari/604.1" },
      redirect: "follow",
    });

    const finalUrl = upstream.url || t.href;
    const type = upstream.headers.get("content-type") || "";
    const isPlaylist = /mpegurl/i.test(type) || /\.m3u8?$/i.test(new URL(finalUrl).pathname);

    if (isPlaylist && upstream.ok) {
      const text = await upstream.text();
      const body = rewritePlaylist(text, finalUrl, reqUrl.origin);
      return new Response(body, {
        status: 200,
        headers: { ...CORS, "Content-Type": "application/vnd.apple.mpegurl", "Cache-Control": "no-cache" },
      });
    }

    const headers = new Headers(CORS);
    headers.set("Content-Type", type || "video/mp2t");
    const len = upstream.headers.get("content-length");
    if (len) headers.set("Content-Length", len);
    return new Response(upstream.body, { status: upstream.status, headers });
  },
};

// BVR TV — прокси для http-потоков (Cloudflare Worker)
// Использование: https://<имя-воркера>.workers.dev/?u=<ссылка на поток>
// Выкладывается автоматически через GitHub Actions (.github/workflows/deploy-worker.yml)

// Разрешены любые серверы с потоками, чтобы новые каналы добавлялись без правки этого кода.
// Если прокси начнут использовать чужие, сюда можно вернуть список разрешённых серверов.
const ALLOWED_HOSTS = null;

const CORS = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Methods": "GET, HEAD, OPTIONS",
  "Access-Control-Allow-Headers": "*",
};

// ref — адрес сайта, который некоторые серверы требуют в заголовке Referer (передаётся и всем кускам видео)
function proxied(origin, absUrl, ref) {
  return origin + "/?u=" + encodeURIComponent(absUrl) + (ref ? "&ref=" + encodeURIComponent(ref) : "");
}

function rewritePlaylist(text, baseUrl, origin, ref) {
  return text.split("\n").map((line) => {
    const t = line.trim();
    if (!t) return line;
    if (t.startsWith("#")) {
      // ссылки внутри тегов, например #EXT-X-KEY:URI="..."
      return line.replace(/URI="([^"]+)"/g, (m, u) =>
        'URI="' + proxied(origin, new URL(u, baseUrl).href, ref) + '"');
    }
    return proxied(origin, new URL(t, baseUrl).href, ref);
  }).join("\n");
}

// ---------- Бот @rus_tv_bot: приветствие на /start ----------
// Токен бота хранится секретом TELEGRAM_BOT_TOKEN в Cloudflare (кладётся туда автоматически из GitHub).
const APP_URL = "https://bvrgames.github.io/BVR_TV/";
const WELCOME =
  "Привет! Это <b>rus tv</b> — русские каналы прямо в Telegram 📺\n\n" +
  "Нажми «Смотреть ТВ» и выбери канал: Первый, Россия 1, НТВ, СТС, ТНТ, спорт, кино и детские.\n\n" +
  "Если канал не включился сразу, подожди несколько секунд: приложение само найдёт рабочую трансляцию.";

// Секрет для проверки, что запрос пришёл именно от Telegram: первые 32 символа SHA-256 от токена
async function hookSecret(token) {
  const d = await crypto.subtle.digest("SHA-256", new TextEncoder().encode("rustv:" + token));
  return [...new Uint8Array(d)].map((b) => b.toString(16).padStart(2, "0")).join("").slice(0, 32);
}

async function handleBot(request, env) {
  const token = env.TELEGRAM_BOT_TOKEN;
  if (!token) return new Response("no token", { status: 503 });
  if (request.headers.get("X-Telegram-Bot-Api-Secret-Token") !== (await hookSecret(token))) {
    return new Response("forbidden", { status: 403 });
  }
  let upd = {};
  try { upd = await request.json(); } catch (e) {}
  const msg = upd.message;
  if (msg && msg.chat && typeof msg.text === "string" && msg.text.startsWith("/start")) {
    await fetch("https://api.telegram.org/bot" + token + "/sendMessage", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        chat_id: msg.chat.id,
        text: WELCOME,
        parse_mode: "HTML",
        reply_markup: { inline_keyboard: [[{ text: "📺 Смотреть ТВ", web_app: { url: APP_URL } }]] },
      }),
    });
  }
  return new Response("ok");
}

export default {
  async fetch(request, env) {
    if (request.method === "OPTIONS") return new Response(null, { headers: CORS });

    const reqUrl = new URL(request.url);
    if (reqUrl.pathname === "/tg" && request.method === "POST") return handleBot(request, env);
    const target = reqUrl.searchParams.get("u");
    if (!target) return new Response("BVR TV proxy работает (версия 4)", { headers: CORS });

    let t;
    try { t = new URL(target); } catch (e) {
      return new Response("Неверная ссылка", { status: 400, headers: CORS });
    }
    if (!/^https?:$/.test(t.protocol) || (ALLOWED_HOSTS && !ALLOWED_HOSTS.includes(t.hostname))) {
      return new Response("Этот сервер не разрешён", { status: 403, headers: CORS });
    }

    const ref = reqUrl.searchParams.get("ref") || "";
    const hdrs = { "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/144.0.0.0 Safari/537.36" };
    if (ref) { hdrs["Referer"] = ref; try { hdrs["Origin"] = new URL(ref).origin; } catch (e) {} }
    const upstream = await fetch(t.href, { headers: hdrs, redirect: "follow" });

    const finalUrl = upstream.url || t.href;
    const type = upstream.headers.get("content-type") || "";
    const isPlaylist = /mpegurl/i.test(type) || /\.m3u8?$/i.test(new URL(finalUrl).pathname);

    if (isPlaylist && upstream.ok) {
      const text = await upstream.text();
      const body = rewritePlaylist(text, finalUrl, reqUrl.origin, ref);
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

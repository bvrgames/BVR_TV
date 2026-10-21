// BVR TV — прокси для http-потоков (Cloudflare Worker)
// Использование: https://<имя-воркера>.workers.dev/?u=<ссылка на поток>
// Выкладывается автоматически через GitHub Actions (.github/workflows/deploy-worker.yml)

// Безопасность: прокси не открыт для всех.
//  1) Первая ссылка (плейлист канала) пропускается, только если её сервер есть в списке каналов приложения —
//     список берётся прямо из index.html на сайте, поэтому новые каналы работают без правки этого кода.
//  2) Все ссылки внутри плейлиста (куски видео, качества, ключи) прокси подписывает сам — чужую ссылку
//     с поддельной подписью он не пропустит.
const HOSTS_TTL = 10 * 60 * 1000;
const INDEX_SOURCES = ["https://rus-tv.vercel.app/index.html", "https://bvrgames.github.io/BVR_TV/index.html", "https://raw.githubusercontent.com/bvrgames/BVR_TV/main/index.html"];
const ALLOWED_PREFIXES = ["https://raw.githubusercontent.com/bvrgames/BVR_TV/"]; // телепрограмма для ТВ-приложения
let hostsCache = null, hostsAt = 0;

async function allowedHosts() {
  if (hostsCache && Date.now() - hostsAt < HOSTS_TTL) return hostsCache;
  for (const src of INDEX_SOURCES) {
    try {
      const r = await fetch(src, { cf: { cacheTtl: 300 } });
      if (!r.ok) continue;
      const html = await r.text();
      const from = html.indexOf("const CHANNELS");
      const block = from >= 0 ? html.slice(from) : html;
      const set = new Set();
      for (const m of block.matchAll(/["'](https?:\/\/[^"'\s]+)["']/g)) {
        try { set.add(new URL(m[1]).hostname); } catch (e) {}
      }
      if (set.size >= 5) { hostsCache = set; hostsAt = Date.now(); return set; }
    } catch (e) {}
  }
  return hostsCache; // сайт недоступен — работаем по последнему известному списку
}

let signKey = null, signKeyFor = null;
async function sign(env, url, ref) {
  const secret = "proxy:" + (env.TELEGRAM_BOT_TOKEN || "rustv");
  if (!signKey || signKeyFor !== secret) {
    signKey = await crypto.subtle.importKey("raw", new TextEncoder().encode(secret), { name: "HMAC", hash: "SHA-256" }, false, ["sign"]);
    signKeyFor = secret;
  }
  const mac = await crypto.subtle.sign("HMAC", signKey, new TextEncoder().encode(url + "|" + (ref || "")));
  return [...new Uint8Array(mac)].slice(0, 12).map((b) => b.toString(16).padStart(2, "0")).join("");
}

// Опасные типы (страницы, скрипты) прокси никогда не отдаёт как есть — только как «файл»
function safeType(type) {
  return /html|javascript|ecmascript|svg|xml|json/i.test(type) && !/mpegurl|dash/i.test(type) ? "application/octet-stream" : type;
}

const CORS = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Methods": "GET, HEAD, POST, OPTIONS",
  "Access-Control-Allow-Headers": "*",
};

// ref — адрес сайта, который некоторые серверы требуют в заголовке Referer (передаётся и всем кускам видео)
async function proxied(env, origin, absUrl, ref) {
  return origin + "/?u=" + encodeURIComponent(absUrl) + (ref ? "&ref=" + encodeURIComponent(ref) : "") +
    "&s=" + (await sign(env, absUrl, ref));
}

async function rewritePlaylist(env, text, baseUrl, origin, ref) {
  const out = [];
  for (const line of text.split("\n")) {
    const t = line.trim();
    if (!t) { out.push(line); continue; }
    if (t.startsWith("#")) {
      // ссылки внутри тегов, например #EXT-X-KEY:URI="..."
      const uris = [...line.matchAll(/URI="([^"]+)"/g)];
      let l = line;
      for (const m of uris) l = l.replace(m[0], 'URI="' + (await proxied(env, origin, new URL(m[1], baseUrl).href, ref)) + '"');
      out.push(l);
      continue;
    }
    out.push(await proxied(env, origin, new URL(t, baseUrl).href, ref));
  }
  return out.join("\n");
}

// ---------- Бот @rus_tv_bot: приветствие, версия для ТВ, донаты звёздами ----------
// Токен бота хранится секретом TELEGRAM_BOT_TOKEN в Cloudflare (кладётся туда автоматически из GitHub).
const APP_URL = "https://rus-tv.vercel.app/";
const APK_URL = "https://github.com/bvrgames/BVR_TV/releases/download/tv/rus-tv.apk";
const DRIVE_URL = "https://drive.google.com/drive/folders/1S_JAApDn_H-KI_ha14Hs77xLHIuUeGgW?usp=sharing";
const TV_KB = { inline_keyboard: [[{ text: "📁 APK на Google Диске", url: DRIVE_URL }]] };
const WELCOME =
  "Привет! Это <b>rus tv</b> — русские каналы прямо в Telegram 📺\n\n" +
  "Нажми «Смотреть ТВ» и выбери канал: Первый, Россия 1, НТВ, СТС, ТНТ, спорт, кино и детские.\n\n" +
  "Если канал не включился сразу, подожди несколько секунд: приложение само найдёт рабочую трансляцию.\n\n" +
  "Есть и <b>приложение для телевизора</b> на Android TV — с программой передач и управлением с пульта.";
const TV_TEXT =
  "📺 <b>rus tv для телевизора</b>\n\n" +
  "Работает на телевизорах и приставках с Android TV: каналы листаются пультом, видно, что идёт сейчас, обновляется само.\n\n" +
  "<b>Как поставить:</b>\n" +
  "1. Установи приложение <b>Send files to TV</b> из Google Play — и на телевизор, и на Android-телефон.\n" +
  "2. Сохрани файл rus-tv.apk из этого чата на телефон: открой меню файла (⋮) → «Сохранить в загрузки».\n" +
  "3. Открой Send files to TV на обоих устройствах: на телевизоре нажми «Получить», на телефоне — «Отправить» и выбери rus-tv.apk.\n" +
  "4. На телевизоре открой полученный файл и нажми «Установить». Если телевизор спросит — разреши установку из этого источника.";
const DONATE_TEXT =
  "⭐ <b>Поддержать rus tv</b>\n\n" +
  "Приложение бесплатное и без рекламы. Если оно тебе нравится — можно поддержать проект звёздами Telegram. Спасибо!";
const DONATE_AMOUNTS = [50, 100, 250, 500];

const MAIN_KB = {
  inline_keyboard: [
    [{ text: "📺 Смотреть ТВ", web_app: { url: APP_URL } }],
    [{ text: "📲 Для телевизора", callback_data: "tv" }, { text: "⭐ Поддержать", callback_data: "donate" }],
  ],
};

// Секрет для проверки, что запрос пришёл именно от Telegram: первые 32 символа SHA-256 от токена
async function hookSecret(token) {
  const d = await crypto.subtle.digest("SHA-256", new TextEncoder().encode("rustv:" + token));
  return [...new Uint8Array(d)].map((b) => b.toString(16).padStart(2, "0")).join("").slice(0, 32);
}

function tg(token, method, body) {
  return fetch("https://api.telegram.org/bot" + token + "/" + method, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(body),
  });
}

// Бот сам присылает APK файлом в чат. По ссылке Telegram .apk не берёт — поэтому скачиваем и загружаем.
async function sendApk(token, chat) {
  let ver = "";
  try {
    const v = await fetch("https://github.com/bvrgames/BVR_TV/releases/download/tv/tv-version.txt");
    if (v.ok) ver = " · версия 1." + (await v.text()).trim().split(/\s+/)[0];
  } catch (e) {}
  const apk = await fetch(APK_URL);
  if (!apk.ok) return false;
  const fd = new FormData();
  fd.append("chat_id", String(chat));
  fd.append("document", new Blob([await apk.arrayBuffer()], { type: "application/vnd.android.package-archive" }), "rus-tv.apk");
  fd.append("caption", "📺 rus tv для Android TV" + ver);
  const r = await fetch("https://api.telegram.org/bot" + token + "/sendDocument", { method: "POST", body: fd });
  return r.ok;
}

async function sendTv(token, chat) {
  await tg(token, "sendMessage", { chat_id: chat, text: TV_TEXT, parse_mode: "HTML", reply_markup: TV_KB });
  await sendApk(token, chat);
}

// Проверка, что запрос пришёл из нашего приложения в Telegram (подпись initData), → пользователь
async function webAppUser(initData, token) {
  const p = new URLSearchParams(initData || "");
  const hash = p.get("hash");
  if (!hash) return null;
  p.delete("hash");
  const dcs = [...p.entries()].sort(([a], [b]) => (a < b ? -1 : a > b ? 1 : 0)).map(([k, v]) => k + "=" + v).join("\n");
  const enc = new TextEncoder();
  const k1 = await crypto.subtle.importKey("raw", enc.encode("WebAppData"), { name: "HMAC", hash: "SHA-256" }, false, ["sign"]);
  const secret = await crypto.subtle.sign("HMAC", k1, enc.encode(token));
  const k2 = await crypto.subtle.importKey("raw", secret, { name: "HMAC", hash: "SHA-256" }, false, ["sign"]);
  const sig = [...new Uint8Array(await crypto.subtle.sign("HMAC", k2, enc.encode(dcs)))].map((b) => b.toString(16).padStart(2, "0")).join("");
  if (sig !== hash) return null;
  const authDate = parseInt(p.get("auth_date") || "0", 10);
  if (!authDate || Date.now() / 1000 - authDate > 86400) return null; // подпись старше суток — не принимаем
  try { return JSON.parse(p.get("user") || "null"); } catch (e) { return null; }
}

// Кнопка «Получить APK» в приложении → бот присылает файл и инструкцию в чат
async function handleApkRequest(request, env) {
  const json = (o, st) => new Response(JSON.stringify(o), { status: st || 200, headers: { ...CORS, "Content-Type": "application/json" } });
  const token = env.TELEGRAM_BOT_TOKEN;
  if (!token) return json({ ok: false, error: "no token" }, 503);
  let body = {};
  try { body = await request.json(); } catch (e) {}
  const user = await webAppUser(body.initData, token);
  if (!user || !user.id) return json({ ok: false, error: "bad initData" }, 403);
  await tg(token, "sendMessage", { chat_id: user.id, text: TV_TEXT, parse_mode: "HTML", reply_markup: TV_KB });
  const ok = await sendApk(token, user.id);
  return json({ ok });
}

function sendDonate(token, chat) {
  return tg(token, "sendMessage", {
    chat_id: chat, text: DONATE_TEXT, parse_mode: "HTML",
    reply_markup: { inline_keyboard: [DONATE_AMOUNTS.map((n) => ({ text: n + " ⭐", callback_data: "pay:" + n }))] },
  });
}

// Донат звёздами Telegram (валюта XTR): платёжный провайдер не нужен
function sendInvoice(token, chat, stars) {
  return tg(token, "sendInvoice", {
    chat_id: chat,
    title: "Поддержка rus tv",
    description: "Спасибо, что помогаешь приложению развиваться! " + stars + " ⭐",
    payload: "donate:" + stars,
    currency: "XTR",
    prices: [{ label: "Поддержка", amount: stars }],
  });
}

async function handleBot(request, env) {
  const token = env.TELEGRAM_BOT_TOKEN;
  if (!token) return new Response("no token", { status: 503 });
  if (request.headers.get("X-Telegram-Bot-Api-Secret-Token") !== (await hookSecret(token))) {
    return new Response("forbidden", { status: 403 });
  }
  let upd = {};
  try { upd = await request.json(); } catch (e) {}

  // Перед оплатой Telegram спрашивает «можно?» — нужно ответить за 10 секунд
  if (upd.pre_checkout_query) {
    await tg(token, "answerPreCheckoutQuery", { pre_checkout_query_id: upd.pre_checkout_query.id, ok: true });
    return new Response("ok");
  }

  const cb = upd.callback_query;
  if (cb && cb.message) {
    const chat = cb.message.chat.id, d = cb.data || "";
    await tg(token, "answerCallbackQuery", { callback_query_id: cb.id });
    if (d === "tv") await sendTv(token, chat);
    else if (d === "donate") await sendDonate(token, chat);
    else if (d.startsWith("pay:")) {
      const n = parseInt(d.slice(4), 10);
      if (DONATE_AMOUNTS.includes(n)) await sendInvoice(token, chat, n);
    }
    return new Response("ok");
  }

  const msg = upd.message;
  if (msg && msg.chat) {
    if (msg.successful_payment) {
      await tg(token, "sendMessage", { chat_id: msg.chat.id, text: "Спасибо за поддержку! ❤️ Это очень помогает rus tv." });
    } else if (typeof msg.text === "string") {
      const t = msg.text.trim();
      if (t.startsWith("/start")) {
        await tg(token, "sendMessage", { chat_id: msg.chat.id, text: WELCOME, parse_mode: "HTML", reply_markup: MAIN_KB });
      } else if (t.startsWith("/tv")) await sendTv(token, msg.chat.id);
      else if (t.startsWith("/donate")) await sendDonate(token, msg.chat.id);
    }
  }
  return new Response("ok");
}

export default {
  async fetch(request, env) {
    if (request.method === "OPTIONS") return new Response(null, { headers: CORS });

    const reqUrl = new URL(request.url);
    if (reqUrl.pathname === "/tg" && request.method === "POST") return handleBot(request, env);
    if (reqUrl.pathname === "/apk" && request.method === "POST") return handleApkRequest(request, env);
    // Страна, из которой зашёл человек (для подсказки про VPN в приложении)
    if (reqUrl.pathname === "/geo") {
      return new Response(JSON.stringify({ country: (request.cf && request.cf.country) || "" }),
        { headers: { ...CORS, "Content-Type": "application/json", "Cache-Control": "no-store" } });
    }
    // Короткий адрес для Downloader на телевизоре: /tv → свежий APK
    if (reqUrl.pathname === "/tv" || reqUrl.pathname === "/tv/" || reqUrl.pathname === "/tv.apk") {
      return Response.redirect(APK_URL, 302);
    }
    const target = reqUrl.searchParams.get("u");
    if (!target) return new Response("BVR TV proxy работает (версия 8)", { headers: CORS });

    let t;
    try { t = new URL(target); } catch (e) {
      return new Response("Неверная ссылка", { status: 400, headers: CORS });
    }
    const ref = reqUrl.searchParams.get("ref") || "";
    let allowed = false;
    if (/^https?:$/.test(t.protocol)) {
      const sig = reqUrl.searchParams.get("s");
      if (sig) allowed = sig === (await sign(env, target, ref));
      else if (ALLOWED_PREFIXES.some((p) => target.startsWith(p))) allowed = true;
      else { const hosts = await allowedHosts(); allowed = !!(hosts && hosts.has(t.hostname)); }
    }
    if (!allowed) return new Response("Этот сервер не разрешён", { status: 403, headers: CORS });

    const hdrs = { "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/144.0.0.0 Safari/537.36" };
    if (ref) { hdrs["Referer"] = ref; try { hdrs["Origin"] = new URL(ref).origin; } catch (e) {} }
    const upstream = await fetch(t.href, { headers: hdrs, redirect: "follow" });

    const finalUrl = upstream.url || t.href;
    const type = upstream.headers.get("content-type") || "";
    const isPlaylist = /mpegurl/i.test(type) || /\.m3u8?$/i.test(new URL(finalUrl).pathname);

    if (isPlaylist && upstream.ok) {
      const text = await upstream.text();
      const body = await rewritePlaylist(env, text, finalUrl, reqUrl.origin, ref);
      return new Response(body, {
        status: 200,
        headers: { ...CORS, "Content-Type": "application/vnd.apple.mpegurl", "Cache-Control": "no-cache", "X-Content-Type-Options": "nosniff" },
      });
    }

    const headers = new Headers(CORS);
    headers.set("Content-Type", safeType(type) || "video/mp2t");
    headers.set("X-Content-Type-Options", "nosniff");
    const len = upstream.headers.get("content-length");
    if (len) headers.set("Content-Length", len);
    return new Response(upstream.body, { status: upstream.status, headers });
  },
};

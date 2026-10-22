import { DurableObject } from "cloudflare:workers";

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

// ---------- Режим разработчика: статистика ----------
// Хранится в базе SQLite внутри самого воркера (Durable Object «Stats»): отдельной настройки в Cloudflare не нужно.
// Запись статистики никогда не мешает боту и приложению: любая ошибка молча пропускается.
const DEV_ID = 454163382; // @Bikmetov_vr — только ему видна статистика
const EV_KINDS = ["open", "watch", "fail", "launch"]; // что можно прислать на /ev
const clip = (x, n) => (x == null ? null : String(x).slice(0, n));

// ---------- Пуши: в Telegram (всем, кого бот знает) и уведомлением на телефонах с APK ----------
// at — время отправки (UTC). Каждый пуш уходит один раз. Опоздали больше чем на 2 часа — не шлём.
// button — кнопка под сообщением в Telegram, открывает приложение.
const PUSHES = [
  { id: "test-owner-1", at: Date.UTC(2026, 9, 22, 9, 50), text: "Тест пушей rus tv ✅ Это видишь только ты.", button: "Смотреть", onlyOwner: true, app: false },
  { id: "upd-34", at: Date.UTC(2026, 9, 23, 17, 0), text: "rus tv обновился 📺 подправил оптимизацию." },
  { id: "ufc333-prelims", at: Date.UTC(2026, 9, 24, 15, 50), text: "🥊 Через 10 минут прелимы UFC 333: Волкановски — Евлоев. Смотри в rus tv", button: "Смотреть" },
  { id: "ufc333-main", at: Date.UTC(2026, 9, 24, 17, 50), text: "🥊 Через 10 минут главный кард UFC 333: Волкановски — Евлоев. Смотри в rus tv", button: "Смотреть" },
];
const PUSH_LATE = 2 * 3600 * 1000;
const PUSH_BATCH = 40; // за один заход (у бесплатного Cloudflare лимит 50 запросов наружу)

export class Stats extends DurableObject {
  constructor(ctx, env) {
    super(ctx, env);
    this.sql = ctx.storage.sql;
    this.sql.exec(`
      CREATE TABLE IF NOT EXISTS users (id INTEGER PRIMARY KEY, name TEXT, username TEXT, first_at INTEGER, last_at INTEGER,
        opens INTEGER DEFAULT 0, starts INTEGER DEFAULT 0, apk INTEGER DEFAULT 0, country TEXT);
      CREATE TABLE IF NOT EXISTS devices (id TEXT PRIMARY KEY, first_at INTEGER, last_at INTEGER, launches INTEGER DEFAULT 0, country TEXT);
      CREATE TABLE IF NOT EXISTS events (at INTEGER, kind TEXT, uid INTEGER, dev TEXT, ch TEXT, n INTEGER, country TEXT, src TEXT);
      CREATE INDEX IF NOT EXISTS ev_at ON events(at);
      CREATE INDEX IF NOT EXISTS ev_kind ON events(kind, at);
      CREATE TABLE IF NOT EXISTS pushes (id TEXT PRIMARY KEY, last_uid INTEGER DEFAULT 0, sent INTEGER DEFAULT 0, failed INTEGER DEFAULT 0, done INTEGER DEFAULT 0);
      CREATE TABLE IF NOT EXISTS blocked (id INTEGER PRIMARY KEY);`);
    this.rate = new Map();
  }

  // Не больше 40 событий в минуту от одного человека или устройства
  allowed(key) {
    const m = Math.floor(Date.now() / 60000), r = this.rate.get(key);
    if (!r || r.m !== m) { this.rate.set(key, { m, n: 1 }); if (this.rate.size > 5000) this.rate.clear(); return true; }
    return ++r.n <= 40;
  }

  add(e) {
    const now = Date.now(), key = e.user ? "u" + e.user.id : e.dev ? "d" + e.dev : "";
    if (!key || !this.allowed(key)) return;
    let uid = null;
    if (e.user && e.user.id) {
      uid = e.user.id;
      const name = clip([e.user.first_name, e.user.last_name].filter(Boolean).join(" "), 80), un = clip(e.user.username, 40);
      this.sql.exec(`INSERT INTO users (id, name, username, first_at, last_at, opens, starts, apk, country)
          VALUES (?, ?, ?, ?, ?, 0, 0, 0, ?)
        ON CONFLICT(id) DO UPDATE SET name = excluded.name, username = excluded.username, last_at = excluded.last_at,
          country = COALESCE(excluded.country, users.country)`, uid, name, un, now, now, e.country || null);
      if (e.kind === "open") this.sql.exec("UPDATE users SET opens = opens + 1 WHERE id = ?", uid);
      if (e.kind === "start") this.sql.exec("UPDATE users SET starts = starts + 1 WHERE id = ?", uid);
      if (e.kind === "apk") this.sql.exec("UPDATE users SET apk = apk + 1 WHERE id = ?", uid);
    }
    if (e.dev) {
      this.sql.exec(`INSERT INTO devices (id, first_at, last_at, launches, country) VALUES (?, ?, ?, 0, ?)
        ON CONFLICT(id) DO UPDATE SET last_at = excluded.last_at, country = COALESCE(excluded.country, devices.country)`,
        e.dev, now, now, e.country || null);
      if (e.kind === "launch") this.sql.exec("UPDATE devices SET launches = launches + 1 WHERE id = ?", e.dev);
    }
    this.sql.exec("INSERT INTO events (at, kind, uid, dev, ch, n, country, src) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
      now, e.kind, uid, e.dev || null, clip(e.ch, 60), e.n | 0, e.country || null, clip(e.src, 10));
  }

  // Раз в 5 минут (и сразу после каждой порции): отправляем пуши, которым пришло время
  async tick() {
    if (this.busy) return; // уже отправляем — второй заход не нужен, иначе сообщения задвоятся
    this.busy = true;
    try { await this.sendDue(); } finally { this.busy = false; }
  }

  async sendDue() {
    const token = this.env.TELEGRAM_BOT_TOKEN, now = Date.now();
    if (!token) return;
    const push = PUSHES.find((p) => p.at <= now && now - p.at < PUSH_LATE &&
      !this.sql.exec("SELECT done FROM pushes WHERE id = ? AND done = 1", p.id).toArray().length);
    if (!push) return;
    this.sql.exec("INSERT OR IGNORE INTO pushes (id) VALUES (?)", push.id);
    const st = this.sql.exec("SELECT last_uid FROM pushes WHERE id = ?", push.id).one();
    const ids = push.onlyOwner
      ? (st.last_uid ? [] : [DEV_ID])
      : this.sql.exec("SELECT id FROM users WHERE id > ? AND id NOT IN (SELECT id FROM blocked) ORDER BY id LIMIT ?",
          st.last_uid, PUSH_BATCH).toArray().map((r) => r.id);
    const markup = push.button ? { inline_keyboard: [[{ text: push.button, web_app: { url: APP_URL } }]] } : undefined;
    let sent = 0, failed = 0, last = st.last_uid;
    for (const id of ids) {
      try {
        const r = await tg(token, "sendMessage", { chat_id: id, text: push.text, reply_markup: markup });
        if (r.ok) sent++;
        else { failed++; if (r.status === 403) this.sql.exec("INSERT OR IGNORE INTO blocked (id) VALUES (?)", id); }
      } catch (e) { failed++; }
      last = id;
    }
    const done = ids.length < PUSH_BATCH || push.onlyOwner ? 1 : 0;
    this.sql.exec("UPDATE pushes SET last_uid = ?, sent = sent + ?, failed = failed + ?, done = ? WHERE id = ?",
      push.onlyOwner ? 1 : last, sent, failed, done, push.id);
    // не всё отправили или ждёт следующий пуш — продолжаем через 2 секунды
    if (!done || PUSHES.some((p) => p.id !== push.id && p.at <= now && now - p.at < PUSH_LATE)) await this.ctx.storage.setAlarm(now + 2000);
  }

  async alarm() { await this.tick(); }

  report(dayStart) {
    const all = (q, ...a) => this.sql.exec(q, ...a).toArray();
    const one = (q, ...a) => { const r = all(q, ...a)[0]; return r ? Object.values(r)[0] || 0 : 0; };
    const now = Date.now();
    const periods = { day: dayStart > 0 && dayStart <= now ? dayStart : now - 86400000, week: now - 7 * 86400000, all: 0 };
    const out = { periods: {} };
    for (const [k, from] of Object.entries(periods)) {
      out.periods[k] = {
        newUsers: one("SELECT COUNT(*) FROM users WHERE first_at >= ?", from),
        opens: one("SELECT COUNT(*) FROM events WHERE kind = 'open' AND at >= ?", from),
        openers: one("SELECT COUNT(DISTINCT uid) FROM events WHERE kind = 'open' AND at >= ?", from),
        starts: one("SELECT COUNT(*) FROM events WHERE kind = 'start' AND at >= ?", from),
        apk: one("SELECT COUNT(*) FROM events WHERE kind = 'apk' AND at >= ?", from),
        devices: one("SELECT COUNT(*) FROM devices WHERE first_at >= ?", from),
        activeDevices: one("SELECT COUNT(DISTINCT dev) FROM events WHERE kind = 'launch' AND at >= ?", from),
        launches: one("SELECT COUNT(*) FROM events WHERE kind = 'launch' AND at >= ?", from),
        minutes: Math.round(one("SELECT SUM(n) FROM events WHERE kind = 'watch' AND at >= ?", from) / 60),
        stars: one("SELECT SUM(n) FROM events WHERE kind = 'donate' AND at >= ?", from),
        donations: one("SELECT COUNT(*) FROM events WHERE kind = 'donate' AND at >= ?", from),
      };
    }
    out.totalUsers = one("SELECT COUNT(*) FROM users");
    out.totalDevices = one("SELECT COUNT(*) FROM devices");
    out.topChannels = all(`SELECT ch, ROUND(SUM(n) / 60.0) AS min, COUNT(DISTINCT COALESCE(uid, dev)) AS people,
        SUM(CASE WHEN dev IS NOT NULL THEN n ELSE 0 END) * 100 / MAX(SUM(n), 1) AS tvShare
      FROM events WHERE kind = 'watch' AND at >= ? AND ch IS NOT NULL GROUP BY ch ORDER BY SUM(n) DESC LIMIT 20`, periods.week);
    out.fails = all(`SELECT ch, COALESCE(country, '?') AS country, COUNT(*) AS n, MAX(at) AS last
      FROM events WHERE kind = 'fail' AND at >= ? GROUP BY ch, country ORDER BY n DESC LIMIT 30`, periods.week);
    out.donations = all(`SELECT e.at, e.n, u.name, u.username FROM events e LEFT JOIN users u ON u.id = e.uid
      WHERE e.kind = 'donate' ORDER BY e.at DESC LIMIT 20`);
    out.users = all(`SELECT id, name, username, first_at, last_at, opens, starts, apk, country FROM users ORDER BY last_at DESC LIMIT 100`);
    out.pushes = PUSHES.filter((p) => !p.onlyOwner).map((p) => {
      const r = all("SELECT sent, failed, done FROM pushes WHERE id = ?", p.id)[0];
      return { text: p.text, at: p.at, sent: r ? r.sent : 0, failed: r ? r.failed : 0, done: r ? !!r.done : false };
    });
    out.countries = all(`SELECT COALESCE(country, '?') AS country, COUNT(*) AS n FROM users GROUP BY country ORDER BY n DESC LIMIT 15`);
    return out;
  }
}

const statsStub = (env) => (env.STATS ? env.STATS.get(env.STATS.idFromName("main")) : null);

// Записать событие в фоне — ответ боту и приложению не ждёт и не ломается от ошибки
function track(env, ctx, e) {
  try {
    const s = statsStub(env);
    if (!s) return;
    const p = s.add(e).catch(() => {});
    ctx && ctx.waitUntil ? ctx.waitUntil(p) : null;
  } catch (err) {}
}

// Событие из приложения: Telegram (проверенный initData) или ТВ-приложение (анонимный id устройства)
async function handleEvent(request, env, ctx) {
  const json = (o, st) => new Response(JSON.stringify(o), { status: st || 200, headers: { ...CORS, "Content-Type": "application/json" } });
  let b = {};
  try { b = JSON.parse(await request.text()); } catch (e) { return json({ ok: false }, 400); }
  if (!EV_KINDS.includes(b.kind)) return json({ ok: false }, 400);
  const country = (request.cf && request.cf.country) || null;
  const e = { kind: b.kind, ch: b.ch, country, src: b.initData ? "tg" : "tv" };
  if (b.kind === "watch") e.n = Math.max(0, Math.min(parseInt(b.sec, 10) || 0, 6 * 3600));
  if (b.initData) {
    const user = env.TELEGRAM_BOT_TOKEN ? await webAppUser(b.initData, env.TELEGRAM_BOT_TOKEN) : null;
    if (!user || !user.id) return json({ ok: false }, 403);
    e.user = user;
  } else if (typeof b.dev === "string" && /^[a-f0-9]{16,40}$/.test(b.dev)) {
    e.dev = b.dev;
  } else return json({ ok: false }, 400);
  track(env, ctx, e);
  return json({ ok: true });
}

// Пуши для телефонов с APK: приложение само ставит уведомления на нужное время
function handleNews() {
  const now = Date.now();
  const list = PUSHES.filter((p) => !p.onlyOwner && p.app !== false && p.at > now - PUSH_LATE)
    .map((p) => ({ id: p.id, at: p.at, text: p.text }));
  return new Response(JSON.stringify({ news: list }), { headers: { ...CORS, "Content-Type": "application/json", "Cache-Control": "no-store" } });
}

// Страница разработчика: данные только для DEV_ID
async function handleDev(request, env) {
  const json = (o, st) => new Response(JSON.stringify(o), { status: st || 200, headers: { ...CORS, "Content-Type": "application/json", "Cache-Control": "no-store" } });
  let b = {};
  try { b = await request.json(); } catch (e) {}
  const user = env.TELEGRAM_BOT_TOKEN ? await webAppUser(b.initData, env.TELEGRAM_BOT_TOKEN) : null;
  if (!user || user.id !== DEV_ID) return json({ ok: false }, 403);
  const s = statsStub(env);
  if (!s) return json({ ok: false, error: "no storage" }, 503);
  try { return json({ ok: true, now: Date.now(), ...(await s.report(+b.dayStart || 0)) }); }
  catch (e) { return json({ ok: false, error: String(e && e.message || e) }, 500); }
}

// Кнопка «Получить APK» в приложении → бот присылает файл и инструкцию в чат
async function handleApkRequest(request, env, ctx) {
  const json = (o, st) => new Response(JSON.stringify(o), { status: st || 200, headers: { ...CORS, "Content-Type": "application/json" } });
  const token = env.TELEGRAM_BOT_TOKEN;
  if (!token) return json({ ok: false, error: "no token" }, 503);
  let body = {};
  try { body = await request.json(); } catch (e) {}
  const user = await webAppUser(body.initData, token);
  if (!user || !user.id) return json({ ok: false, error: "bad initData" }, 403);
  track(env, ctx, { kind: "apk", user, src: "app", country: (request.cf && request.cf.country) || null });
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

async function handleBot(request, env, ctx) {
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
    if (d === "tv") { track(env, ctx, { kind: "apk", user: cb.from, src: "bot" }); await sendTv(token, chat); }
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
      track(env, ctx, { kind: "donate", user: msg.from, n: msg.successful_payment.total_amount });
      await tg(token, "sendMessage", { chat_id: msg.chat.id, text: "Спасибо за поддержку! ❤️ Это очень помогает rus tv." });
    } else if (typeof msg.text === "string") {
      const t = msg.text.trim();
      if (t.startsWith("/start")) {
        if (msg.from) track(env, ctx, { kind: "start", user: msg.from });
        await tg(token, "sendMessage", { chat_id: msg.chat.id, text: WELCOME, parse_mode: "HTML", reply_markup: MAIN_KB });
      } else if (t.startsWith("/tv")) { if (msg.from) track(env, ctx, { kind: "apk", user: msg.from, src: "bot" }); await sendTv(token, msg.chat.id); }
      else if (t.startsWith("/donate")) await sendDonate(token, msg.chat.id);
    }
  }
  return new Response("ok");
}

export default {
  // Раз в 5 минут Cloudflare будит воркер — проверяем, не пора ли отправить пуш
  async scheduled(event, env, ctx) {
    const s = statsStub(env);
    if (s) ctx.waitUntil(s.tick().catch(() => {}));
  },

  async fetch(request, env, ctx) {
    if (request.method === "OPTIONS") return new Response(null, { headers: CORS });

    const reqUrl = new URL(request.url);
    if (reqUrl.pathname === "/tg" && request.method === "POST") return handleBot(request, env, ctx);
    if (reqUrl.pathname === "/apk" && request.method === "POST") return handleApkRequest(request, env, ctx);
    if (reqUrl.pathname === "/ev" && request.method === "POST") return handleEvent(request, env, ctx);
    if (reqUrl.pathname === "/dev" && request.method === "POST") return handleDev(request, env);
    if (reqUrl.pathname === "/news") return handleNews();
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

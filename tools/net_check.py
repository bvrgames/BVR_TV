"""Проверка доступности rus tv из России: через сеть зондов Globalping (обычные домашние/серверные точки в РФ)."""
import json, time, urllib.request

API = "https://api.globalping.io/v1/measurements"
TARGETS = [
    ("Приложение в Telegram (GitHub Pages)", "bvrgames.github.io", "/BVR_TV/version.txt"),
    ("Прокси и бот (Cloudflare Workers)", "rustv.bvrhockey.workers.dev", "/"),
    ("Телепрограмма (raw.githubusercontent)", "raw.githubusercontent.com", "/bvrgames/BVR_TV/epg/report.txt"),
    ("Файл версии для автообновления (GitHub Releases)", "github.com", "/bvrgames/BVR_TV/releases/download/tv/tv-version.txt"),
    ("Библиотека плеера hls.js (cdnjs)", "cdnjs.cloudflare.com", "/ajax/libs/hls.js/1.5.13/hls.light.min.js"),
    ("Скрипт Telegram для мини-приложений", "telegram.org", "/js/telegram-web-app.js"),
]

def post(body):
    req = urllib.request.Request(API, data=json.dumps(body).encode(), headers={"Content-Type": "application/json"})
    return json.load(urllib.request.urlopen(req, timeout=30))

def get(mid):
    return json.load(urllib.request.urlopen(API + "/" + mid, timeout=30))

lines = []
for name, host, path in TARGETS:
    try:
        m = post({"type": "http", "target": host, "locations": [{"country": "RU"}], "limit": 5,
                  "measurementOptions": {"protocol": "HTTPS", "request": {"path": path, "method": "GET"}}})
        for _ in range(30):
            r = get(m["id"])
            if r.get("status") != "in-progress": break
            time.sleep(2)
        lines.append(f"\n== {name} — {host}{path}")
        for x in r.get("results", []):
            p, res = x["probe"], x["result"]
            t = res.get("timings") or {}
            lines.append(f"  {p.get('city','?')}, {p.get('network','?')}: статус {res.get('statusCode')} · {res.get('status')} · "
                         f"всего {t.get('total')} мс · {(res.get('rawOutput') or '')[:0]}"
                         + (f" ОШИБКА: {res.get('rawOutput','')[:120]}" if res.get('status') != 'finished' or not res.get('statusCode') else ""))
    except Exception as e:
        lines.append(f"\n== {name}: не удалось проверить ({e})")
out = "\n".join(lines)
print(out)
open("ru-check.txt", "w", encoding="utf-8").write("Проверка из России " + time.strftime("%Y-%m-%d %H:%M UTC", time.gmtime()) + "\n" + out + "\n")

"""Собирает телепрограмму для каналов приложения в маленький epg.json.

Берёт открытые XMLTV-программы, находит в них наши каналы по названию
и оставляет передачи с «2 часа назад» до «+30 часов». Запускается по расписанию в GitHub Actions.
"""
import gzip, io, json, re, sys, time, urllib.request
import xml.etree.ElementTree as ET

SOURCES = [
    "http://epg.it999.ru/epg.xml.gz",
    "http://epg.it999.ru/edem.xml.gz",
    "https://iptvx.one/EPG_LITE",
    "https://iptvx.one/epg/epg_lite.xml.gz",
    "http://programtv.ru/xmltv.xml.gz",
    "https://epg.one/epg2.xml.gz",
]

# Другие названия того же канала в программах передач
ALIASES = {
    "Первый": ["Первый канал"], "Пятый": ["Пятый канал", "5 канал"], "ТВ Центр": ["ТВЦ", "ТВ Центр Москва"],
    "Матч ТВ": ["Матч!", "Матч"], "Матч! Премьер": ["Матч Премьер"], "Пятница!": ["Пятница"],
    "Суббота!": ["Суббота"], "Ю": ["Ю ТВ"], "Че": ["Перец", "Чe"], "Муз-ТВ": ["Муз ТВ"],
    "RU.TV": ["RU TV", "Ру ТВ"], "Шансон ТВ": ["Шансон"], "2x2": ["2х2"], "СТС Kids": ["СТС Кидс"],
    "Nickelodeon": ["Nickelodeon Россия"], "Nick Jr.": ["Nick Jr"], "Cartoon Network": ["Cartoon Network Россия"],
}


def key(s):
    s = s.lower().replace("ё", "е").replace("х", "x")
    s = re.sub(r"\b(hd|fhd|uhd|4k|orig|канал)\b", " ", s)
    s = re.sub(r"\b(тв|tv)\b", " ", s)
    return re.sub(r"[\s!.\-_'\"«»()]+", "", s)


def bad_variant(name):
    # региональные сдвиги времени и зарубежные версии нам не нужны
    return bool(re.search(r"\+\s*\d|\(\s*[+-]|\bорбита|\bинтернешнл|international|\bевропа|\bазия|\bвосток", name.lower()))


def our_channels():
    html = open(sys.argv[1], encoding="utf-8").read()
    block = html[html.index("const CHANNELS"):]
    block = block[: block.index("\n];")]
    return re.findall(r'\bname:\s*"([^"]+)"', block)


def ts(v):
    m = re.match(r"(\d{14})\s*([+-]\d{4})?", v)
    if not m:
        return None
    t = time.strptime(m.group(1), "%Y%m%d%H%M%S")
    sec = int(__import__("calendar").timegm(t))
    if m.group(2):
        z = m.group(2)
        sec -= (1 if z[0] == "+" else -1) * (int(z[1:3]) * 3600 + int(z[3:5]) * 60)
    return sec


def fetch(url):
    req = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0 rustv-epg"})
    with urllib.request.urlopen(req, timeout=90) as r:
        data = r.read()
    if data[:2] == b"\x1f\x8b":
        data = gzip.decompress(data)
    return data


def parse(data, wanted_keys, lo, hi):
    """→ {наш канал: [[start, stop, title], ...]}"""
    ids = {}  # id канала в XMLTV → наш канал
    progs = {}
    for ev, el in ET.iterparse(io.BytesIO(data), events=("end",)):
        if el.tag == "channel":
            for dn in el.findall("display-name"):
                n = (dn.text or "").strip()
                if n and not bad_variant(n) and key(n) in wanted_keys:
                    ids.setdefault(el.get("id"), wanted_keys[key(n)])
            el.clear()
        elif el.tag == "programme":
            ch = ids.get(el.get("channel"))
            if ch:
                a, b = ts(el.get("start", "")), ts(el.get("stop", ""))
                t = el.find("title")
                if a and b and b > lo and a < hi and t is not None and t.text:
                    progs.setdefault(ch, {}).setdefault(el.get("channel"), []).append([a, b, t.text.strip()])
            el.clear()
    out = {}
    for ch, by_id in progs.items():  # у одного канала бывает несколько id — берём самый полный
        best = max(by_id.values(), key=len)
        best.sort()
        out[ch] = best
    return out


def find_events(data, words, lo, hi):
    """Ищет передачи по словам (например, UFC) во ВСЕХ каналах программы — чтобы понять, где будет эфир."""
    names, found = {}, []
    for ev, el in ET.iterparse(io.BytesIO(data), events=("end",)):
        if el.tag == "channel":
            dn = el.find("display-name")
            names[el.get("id")] = (dn.text or "").strip() if dn is not None else el.get("id")
            el.clear()
        elif el.tag == "programme":
            t = el.find("title")
            title = (t.text or "") if t is not None else ""
            if any(w.lower() in title.lower() for w in words):
                a = ts(el.get("start", ""))
                if a and lo <= a <= hi:
                    found.append((a, names.get(el.get("channel"), el.get("channel")), title.strip()))
            el.clear()
    found.sort()
    return found


def main():
    names = our_channels()
    wanted = {}
    for n in names:
        for a in [n] + ALIASES.get(n, []):
            wanted.setdefault(key(a), n)
    now = int(time.time())
    lo, hi = now - 2 * 3600, now + 30 * 3600
    result, report = {}, []
    for url in SOURCES:
        missing = [n for n in names if n not in result]
        if not missing:
            break
        try:
            t0 = time.time()
            data = fetch(url)
            if url == SOURCES[0]:
                ev = find_events(data, ["UFC"], now - 3600, now + 8 * 86400)
                msk = 3 * 3600
                lines = [time.strftime("%d.%m %H:%M МСК", time.gmtime(a + msk)) + " | " + c + " | " + t for a, c, t in ev]
                open("ufc.txt", "w", encoding="utf-8").write("\n".join(lines) + "\n")
            got = parse(data, {k: v for k, v in wanted.items() if v in missing}, lo, hi)
            got = {k: v for k, v in got.items() if len(v) >= 2}
            result.update(got)
            report.append(f"{url}: {len(data)//1024} КБ, {time.time()-t0:.0f} с, нашёл {len(got)}: {', '.join(sorted(got))}")
        except Exception as e:
            report.append(f"{url}: ошибка {type(e).__name__}: {e}")
    report.append(f"ИТОГО {len(result)} из {len(names)}. Нет программы: {', '.join(n for n in names if n not in result) or '—'}")
    json.dump({"updated": now, "ch": result}, open("epg.json", "w", encoding="utf-8"), ensure_ascii=False, separators=(",", ":"))
    open("report.txt", "w", encoding="utf-8").write("\n".join(report) + "\n")
    print("\n".join(report))


main()

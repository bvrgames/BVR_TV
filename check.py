import time, urllib.request, urllib.parse, re
UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/144.0.0.0 Safari/537.36"
C = [
 ("Матч ТВ: mcquack 169 (есть)", "http://stream.mcquack.net/169/index.m3u8"),
 ("Матч ТВ: televizor-24 (есть)", "https://streaming.televizor-24-tochka.ru/live/6.m3u8"),
 ("Матч ТВ: matchtv.ru (есть)", "http://bl.video.matchtv.ru/media/playlist/free_d46d0cf1712c0542ec7fd4f0808f600a_hd/17_89756005/1080/e6bef86de8a133cd7b27deb040758a00/4796141934.m3u8"),
 ("Матч ТВ?: linkintel", "http://flussonic.linkintel.ru/match/index.m3u8"),
 ("Матч ТВ?: uplink.kz", "https://fs.uplink.kz/match/mono.m3u8?token=onlinetv"),
 ("Матч ТВ?: 45.132.87.69", "http://45.132.87.69:8080/match/index.m3u8"),
 ("Матч ТВ?: tuva", "http://tv.tuva.ru/machtv/index.m3u8"),
 ("Матч ТВ?: 31.148.5.241", "http://31.148.5.241/kanal_3/index.m3u8"),
 ("Матч! Боец: mcquack 112", "http://stream.mcquack.net/112/index.m3u8"),
 ("Матч! Премьер: mcquack 193 (есть)", "http://stream.mcquack.net/193/index.m3u8"),
]
def get(u, n=None):
    t0 = time.time()
    r = urllib.request.urlopen(urllib.request.Request(u, headers={"User-Agent": UA}), timeout=12)
    b = r.read(n) if n else r.read()
    return r.geturl(), b, time.time() - t0
for name, u in C:
    try:
        url, b, dt = get(u)
        txt = b.decode("utf-8", "ignore")
        if "#EXTM3U" not in txt[:200]: print(f"✗ {name}: не плейлист ({dt:.1f}s)"); continue
        lines = [l.strip() for l in txt.splitlines() if l.strip() and not l.startswith("#")]
        res = re.findall(r"RESOLUTION=(\d+x\d+)", txt)
        media = urllib.parse.urljoin(url, lines[0]) if lines else None
        if media and ".m3u8" in media.split("?")[0]:
            url2, b2, _ = get(media); t2 = b2.decode("utf-8","ignore")
            segs = [l.strip() for l in t2.splitlines() if l.strip() and not l.startswith("#")]
            seg = urllib.parse.urljoin(url2, segs[-1]) if segs else None
        else: seg = media
        if not seg: print(f"✗ {name}: нет кусков видео"); continue
        _, sb, sdt = get(seg, 400000)
        mbps = len(sb) * 8 / max(sdt, 0.01) / 1e6
        print(f"✓ {name}: плейлист {dt:.1f}s, кусок {len(sb)//1024} КБ за {sdt:.1f}s (~{mbps:.1f} Мбит/с){' качества: '+','.join(res) if res else ''}")
    except Exception as e:
        print(f"✗ {name}: {type(e).__name__} {str(e)[:80]}")

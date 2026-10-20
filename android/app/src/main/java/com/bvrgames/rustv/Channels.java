package com.bvrgames.rustv;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Достаёт список каналов прямо из index.html веб-приложения.
 * Так каналы ведутся в одном месте: поменял ссылку на сайте — она поменялась и в приложении на ТВ,
 * переустанавливать APK не нужно.
 */
public final class Channels {

    public static final class Channel {
        public String sec = "", name = "", shortName = "";
        public int color = 0xFF3A3A3C;
        public final List<String> sources = new ArrayList<>();
        public int logoRow = -1;
        public boolean logoDark;
        public boolean viaProxy(int i) { return proxy != null && sources.get(i).startsWith(proxy); }
        String proxy;
    }

    private static final Pattern PROXY = Pattern.compile("const\\s+PROXY\\s*=\\s*[\"']([^\"']+)[\"']");
    private static final Pattern HELPER = Pattern.compile(
            "const\\s+(\\w+)\\s*=\\s*\\(?\\s*\\w+\\s*\\)?\\s*=>\\s*viaProxy\\(\\s*\\w+\\s*,\\s*[\"']([^\"']+)[\"']\\s*\\)");
    private static final Pattern ITEM = Pattern.compile(
            "(\\w+)\\s*\\(\\s*\"([^\"]*)\"\\s*(?:,\\s*\"([^\"]*)\"\\s*)?\\)|\"([^\"]*)\"|'([^']*)'");
    private static final Pattern LOGO = Pattern.compile("\"([^\"]+)\"\\s*:\\s*\\[\\s*(\\d+)\\s*,\\s*(\\d+)\\s*\\]");
    private static final Pattern HEX = Pattern.compile("#([0-9a-fA-F]{6}|[0-9a-fA-F]{3})\\b");

    private Channels() {}

    public static List<Channel> parse(String html) {
        List<Channel> out = new ArrayList<>();
        if (html == null) return out;
        Matcher pm = PROXY.matcher(html);
        String proxy = pm.find() ? pm.group(1) : null;
        Map<String, String> helpers = new HashMap<>();
        Matcher hm = HELPER.matcher(html);
        while (hm.find()) helpers.put(hm.group(1), hm.group(2));

        int start = html.indexOf("const CHANNELS");
        if (start < 0) return out;
        start = html.indexOf('[', start);
        int end = matching(html, start, '[', ']');
        if (start < 0 || end < 0) return out;
        String block = html.substring(start + 1, end);

        Map<String, int[]> logos = new HashMap<>();
        int ls = html.indexOf("const LOGOS");
        if (ls >= 0) {
            int lb = html.indexOf('{', ls), le = lb < 0 ? -1 : matching(html, lb, '{', '}');
            if (le > 0) {
                Matcher lm = LOGO.matcher(html.substring(lb, le + 1));
                while (lm.find()) logos.put(lm.group(1), new int[]{Integer.parseInt(lm.group(2)), Integer.parseInt(lm.group(3))});
            }
        }

        int i = 0;
        while (true) {
            int ob = nextOutsideString(block, i, '{');
            if (ob < 0) break;
            int oe = matching(block, ob, '{', '}');
            if (oe < 0) break;
            Channel ch = parseObject(block.substring(ob + 1, oe), proxy, helpers);
            if (ch != null) {
                int[] lg = logos.get(ch.name);
                if (lg != null) { ch.logoRow = lg[0]; ch.logoDark = lg[1] != 0; }
                out.add(ch);
            }
            i = oe + 1;
        }
        return out;
    }

    private static Channel parseObject(String obj, String proxy, Map<String, String> helpers) {
        Channel ch = new Channel();
        ch.proxy = proxy;
        ch.sec = field(obj, "sec");
        ch.name = field(obj, "name");
        ch.shortName = field(obj, "short");
        String color = field(obj, "color");
        Matcher cm = HEX.matcher(color);
        if (cm.find()) ch.color = parseHex(cm.group(1));

        Matcher sm = Pattern.compile("\\bsources\\s*:\\s*\\[").matcher(obj);
        if (!sm.find()) return null;
        int sb = sm.end() - 1, se = matching(obj, sb, '[', ']');
        if (se < 0) return null;
        Matcher it = ITEM.matcher(obj.substring(sb + 1, se));
        while (it.find()) {
            String fn = it.group(1);
            if (fn != null) {
                if (proxy == null) continue;
                String u = it.group(2), ref = it.group(3);
                if (!"viaProxy".equals(fn)) {
                    ref = helpers.get(fn);
                    if (ref == null) continue; // неизвестная функция — пропускаем ссылку
                }
                ch.sources.add(proxy + encode(u) + (ref != null && !ref.isEmpty() ? "&ref=" + encode(ref) : ""));
            } else {
                String u = it.group(4) != null ? it.group(4) : it.group(5);
                if (u != null && (u.startsWith("http://") || u.startsWith("https://"))) ch.sources.add(u);
            }
        }
        if (ch.name.isEmpty() || ch.sources.isEmpty()) return null;
        if (ch.sec.isEmpty()) ch.sec = "Каналы";
        return ch;
    }

    private static String field(String obj, String key) {
        Matcher m = Pattern.compile("\\b" + key + "\\s*:\\s*(?:\"((?:[^\"\\\\]|\\\\.)*)\"|'((?:[^'\\\\]|\\\\.)*)')").matcher(obj);
        if (!m.find()) return "";
        return m.group(1) != null ? m.group(1) : m.group(2);
    }

    /** Индекс парной скобки; содержимое строк в кавычках не считаем. */
    private static int matching(String s, int open, char o, char c) {
        if (open < 0 || open >= s.length() || s.charAt(open) != o) return -1;
        int depth = 0;
        char q = 0;
        for (int i = open; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (q != 0) {
                if (ch == '\\') i++;
                else if (ch == q) q = 0;
                continue;
            }
            if (ch == '"' || ch == '\'' || ch == '`') q = ch;
            else if (ch == '/' && i + 1 < s.length() && s.charAt(i + 1) == '/') {
                int nl = s.indexOf('\n', i);
                if (nl < 0) return -1;
                i = nl;
            } else if (ch == o) depth++;
            else if (ch == c && --depth == 0) return i;
        }
        return -1;
    }

    private static int nextOutsideString(String s, int from, char target) {
        char q = 0;
        for (int i = from; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (q != 0) {
                if (ch == '\\') i++;
                else if (ch == q) q = 0;
                continue;
            }
            if (ch == '"' || ch == '\'' || ch == '`') q = ch;
            else if (ch == '/' && i + 1 < s.length() && s.charAt(i + 1) == '/') {
                int nl = s.indexOf('\n', i);
                if (nl < 0) return -1;
                i = nl;
            } else if (ch == target) return i;
        }
        return -1;
    }

    private static int parseHex(String h) {
        if (h.length() == 3) h = "" + h.charAt(0) + h.charAt(0) + h.charAt(1) + h.charAt(1) + h.charAt(2) + h.charAt(2);
        return 0xFF000000 | Integer.parseInt(h, 16);
    }

    /** То же, что encodeURIComponent в браузере. */
    static String encode(String s) {
        StringBuilder b = new StringBuilder();
        for (byte by : s.getBytes(StandardCharsets.UTF_8)) {
            int c = by & 0xFF;
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || "-_.!~*'()".indexOf(c) >= 0) b.append((char) c);
            else b.append('%').append(Character.toUpperCase(Character.forDigit(c >> 4, 16))).append(Character.toUpperCase(Character.forDigit(c & 15, 16)));
        }
        return b.toString();
    }
}

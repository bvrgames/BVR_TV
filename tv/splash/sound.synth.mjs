// Звук заставки Rus TV — синхронизирован с таймлайном intro.html (5.2 с)
import fs from 'fs';
const SR = 48000, DUR = 5.2, N = Math.round(SR * DUR);
const L = new Float32Array(N), R = new Float32Array(N);   // dry
const RL = new Float32Array(N), RR = new Float32Array(N); // send в реверб
const TAU = Math.PI * 2;
let seed = 7; const rnd = () => ((seed = (seed * 16807) % 2147483647) / 2147483647) * 2 - 1;
const hz = (midi) => 440 * 2 ** ((midi - 69) / 12);

function put(i, v, pan = 0, send = 0.3) {
  if (i < 0 || i >= N) return;
  const gl = Math.cos((pan + 1) * Math.PI / 4), gr = Math.sin((pan + 1) * Math.PI / 4);
  L[i] += v * gl; R[i] += v * gr; RL[i] += v * gl * send; RR[i] += v * gr * send;
}
// Простой однополюсный/двухполюсный фильтр
function svf() { let lp = 0, bp = 0; return (x, f, q = 0.7) => { const k = 2 * Math.sin(Math.PI * Math.min(f, SR / 6) / SR); lp += k * bp; const hp = x - lp - bp / q; bp += k * hp; return { lp, bp, hp }; }; }

// Колокольчик/стеклянный тон: неровные партиалы с затуханием
function bell(t0, freq, amp, pan, decay = 1.6, send = 0.6) {
  const parts = [[1, 1, 1], [2.0, 0.35, 1.6], [2.76, 0.22, 2.4], [5.4, 0.08, 4]];
  const s = Math.round(t0 * SR);
  for (let i = 0; i < SR * decay * 3; i++) { const t = i / SR; let v = 0;
    for (const [m, a, d] of parts) v += a * Math.sin(TAU * freq * m * t) * Math.exp(-t * d / decay * 3);
    put(s + i, amp * v * Math.min(1, t * 400), pan, send);
  } }
// Мягкий удар-«поп»
function thump(t0, f0, amp, decay) {
  const s = Math.round(t0 * SR); let ph = 0;
  for (let i = 0; i < SR * decay * 4; i++) { const t = i / SR;
    ph += TAU * f0 * (1 + 1.5 * Math.exp(-t * 30)) / SR;
    put(s + i, amp * Math.sin(ph) * Math.exp(-t / decay) * Math.min(1, t * 800), 0, 0.15);
  } }
// Шорох-свист: полосовой шум с движением частоты и панорамы
function whoosh(t0, dur, f0, f1, amp, p0, p1, q = 1.5) {
  const f = svf(), s = Math.round(t0 * SR), n = Math.round(dur * SR);
  for (let i = 0; i < n; i++) { const x = i / n;
    const env = Math.sin(Math.PI * x) ** 2, fc = f0 * (f1 / f0) ** x;
    put(s + i, amp * env * f(rnd(), fc, q).bp, p0 + (p1 - p0) * x, 0.45);
  } }

// 6. Главный аккорд — сочный «блум»: набухает к 2.62 (эмблема встаёт), затем раскрывается и блестит
{ const T0 = 2.62, TS = 0.4, s0 = Math.round(TS * SR);
  const blep = (p, dt) => p < dt ? (p /= dt, p + p - p * p - 1) : p > 1 - dt ? (p = (p - 1) / dt, p * p + p + p + 1) : 0;
  const notes = [33, 40, 45, 52, 57, 61, 64]; // A1 E2 A2 E3 A3 C#4 E4 — низкий киношный ля мажор
  const osc = [];
  notes.forEach((m, k) => { for (let v = 0; v < 5; v++) {
    const cents = (v - 2) * 7 + rnd() * 2;
    osc.push({ dt: hz(m) * 2 ** (cents / 1200) / SR, p: Math.abs(rnd()), pan: ((v - 2) / 2) * 0.8 + rnd() * 0.1, amp: 1 / (1 + k * 0.12) });
  } });
  const fl = svf(), fr = svf(); let sub = 0;
  for (let i = 0; i < N - s0; i++) { const t = TS + i / SR, u = t - T0;
    let env, cut;
    if (u < 0) { const x = (t - TS) / (T0 - TS); env = 0.45 * x ** 2.2; cut = 160 + 900 * x ** 2.4; }
    else { const a = Math.min(1, u / 0.07); env = 0.45 + (1 - 0.45) * a; env *= 0.55 * Math.exp(-u * 0.9) + 0.45 * Math.exp(-u * 0.25);
           cut = 700 + 1900 * Math.min(1, u / 0.18) * Math.exp(-u * 0.9) + 500 * Math.exp(-u * 0.25); }
    let l = 0, r = 0;
    for (const o of osc) { o.p += o.dt; if (o.p >= 1) o.p -= 1;
      const v = (2 * o.p - 1 - blep(o.p, o.dt)) * o.amp * (1 + 0.5 * (o.dt * SR < 120));
      l += v * Math.cos((o.pan + 1) * Math.PI / 4); r += v * Math.sin((o.pan + 1) * Math.PI / 4); }
    const vl = fl(l, cut, 0.75).lp, vr = fr(r, cut, 0.75).lp;
    sub += TAU * 55 / SR; const sb = env * (0.16 * Math.sin(sub) + 0.08 * Math.sin(sub * 0.5));
    const g = 0.032 * env, n = s0 + i;
    L[n] += vl * g + sb; R[n] += vr * g + sb; RL[n] += vl * g * 0.9; RR[n] += vr * g * 0.9;
  }
}

// Воздушный наплыв, вливается в аккорд (0.6 → 2.62 → тает)
{ const f = svf(), s = Math.round(0.6 * SR), T = 2.62 - 0.6;
  for (let i = 0; i < N - s; i++) { const t = i / SR;
    const env = t < T ? (t / T) ** 3 : Math.exp(-(t - T) * 2.2);
    const fc = t < T ? 200 + 1300 * (t / T) ** 2 : 1500 * Math.exp(-(t - T) * 1.5) + 250;
    put(s + i, 0.07 * env * f(rnd(), fc, 0.6).lp, Math.sin(t * 1.3) * 0.4, 0.8);
  } }

// Реверб (Freeverb)
function freeverb(inp, spread) {
  const combs = [1557, 1617, 1491, 1422, 1277, 1356, 1188, 1116].map(d => ({ b: new Float32Array(Math.round((d + spread) * SR / 44100)), i: 0, s: 0 }));
  const aps = [556, 441, 341, 225].map(d => ({ b: new Float32Array(Math.round((d + spread) * SR / 44100)), i: 0 }));
  const out = new Float32Array(N), fb = 0.91, damp = 0.4;
  for (let n = 0; n < N; n++) { let y = 0; const x = inp[n] * 0.015;
    for (const c of combs) { const o = c.b[c.i]; c.s = o * (1 - damp) + c.s * damp; c.b[c.i] = x + c.s * fb; c.i = (c.i + 1) % c.b.length; y += o; }
    for (const a of aps) { const o = a.b[a.i]; a.b[a.i] = y + o * 0.5; a.i = (a.i + 1) % a.b.length; y = o - y; }
    out[n] = y;
  } return out; }
const wl = freeverb(RL, 0), wr = freeverb(RR, 23);

// Сведение: финальное затухание 4.5 → 5.2, мягкий лимитер, нормализация
const outL = new Float32Array(N), outR = new Float32Array(N); let peak = 0;
for (let n = 0; n < N; n++) { const t = n / SR;
  const fade = t < 4.5 ? 1 : Math.cos((t - 4.5) / 0.7 * Math.PI / 2) ** 2;
  outL[n] = Math.tanh((L[n] + wl[n] * 2.2) * 1.2) * fade;
  outR[n] = Math.tanh((R[n] + wr[n] * 2.2) * 1.2) * fade;
  peak = Math.max(peak, Math.abs(outL[n]), Math.abs(outR[n]));
}
const g = 0.63 / peak; // ≈ −4 dBFS
const buf = Buffer.alloc(44 + N * 4);
buf.write('RIFF', 0); buf.writeUInt32LE(36 + N * 4, 4); buf.write('WAVEfmt ', 8);
buf.writeUInt32LE(16, 16); buf.writeUInt16LE(1, 20); buf.writeUInt16LE(2, 22); buf.writeUInt32LE(SR, 24);
buf.writeUInt32LE(SR * 4, 28); buf.writeUInt16LE(4, 32); buf.writeUInt16LE(16, 34); buf.write('data', 36); buf.writeUInt32LE(N * 4, 40);
for (let n = 0; n < N; n++) {
  buf.writeInt16LE(Math.round(Math.max(-1, Math.min(1, outL[n] * g)) * 32767), 44 + n * 4);
  buf.writeInt16LE(Math.round(Math.max(-1, Math.min(1, outR[n] * g)) * 32767), 46 + n * 4);
}
fs.writeFileSync(process.argv[2], buf);
console.log('peak', peak.toFixed(3), 'gain', g.toFixed(2));

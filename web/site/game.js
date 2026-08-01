// Canvas renderer for Tap Dodge Rush.
//
// Every rule below the drawing calls lives in tapdodge.js — the same GameEngine the
// Android app runs, compiled by TeaVM. This file owns pixels, pointer events, sound
// and the saved best score, exactly as GameView does on the phone.
import * as engine from "./tapdodge.js";

const canvas = document.getElementById("game");
const ctx = canvas.getContext("2d", { alpha: false });
const BEST_KEY = "tapdodge:best";

// The engine's constants are absolute pixels — PLAYER_SIZE is 80 whatever the board is —
// so the board's dimensions are not a presentation choice, they are the difficulty. Android
// passes the real view size; a hardcoded 1080x1920 here made the block a larger share of the
// width than on a 1080p phone and, worse, gave a board of aspect 1.78 against a phone's ~2.2,
// so obstacles reached the player noticeably sooner. These are a common modern phone.
const W = 1080;
const H = 2280;

let running = false;
let last = 0;
let started = false;

// Presentation only. The engine's bounds are set once at boot and never touched again:
// on a phone the browser's address bar hides and reappears as you scroll, which fires resize
// and changes 100dvh, and re-bounding the engine there resized the board mid-game.
function fit() {
  const wrap = canvas.parentElement;
  const scale = Math.min(wrap.clientWidth / W, wrap.clientHeight / H);
  canvas.style.width = `${Math.floor(W * scale)}px`;
  canvas.style.height = `${Math.floor(H * scale)}px`;
  const dpr = Math.min(window.devicePixelRatio || 1, 2);
  canvas.width = Math.floor(W * scale * dpr);
  canvas.height = Math.floor(H * scale * dpr);
  ctx.setTransform(scale * dpr, 0, 0, scale * dpr, 0, 0);
}

function pointerX(ev) {
  const r = canvas.getBoundingClientRect();
  return ((ev.clientX - r.left) / r.width) * W;
}

// The game's own sounds, the same files the Android build plays. An earlier version of this
// page synthesised square-wave blips instead, which sounded nothing like the game.
const sfx = (src, volume) => {
  const a = new Audio(src);
  a.volume = volume;
  a.preload = "auto";
  return a;
};
const scoreSound = sfx("./score.wav", 0.5);
const deathSound = sfx("./death.wav", 0.6);

// Music is 2.6MB, so it is not fetched until the first tap — which is also the gesture
// browsers require before any of this is allowed to make noise.
let bgm = null;
let muted = localStorage.getItem("tapdodge:muted") === "1";

function play(sample) {
  if (muted) return;
  try {
    sample.currentTime = 0;
    sample.play().catch(() => {});   // a rejected play is not worth breaking a frame over
  } catch { /* audio is a nicety */ }
}

function startMusic() {
  if (muted || bgm) return;
  bgm = new Audio("./bgm_loop.ogg");
  bgm.loop = true;
  bgm.volume = 0.15;                 // the same level MainActivity sets on the phone
  bgm.play().catch(() => {});
}

function setMuted(next) {
  muted = next;
  localStorage.setItem("tapdodge:muted", muted ? "1" : "0");
  if (bgm) bgm.muted = muted;
  if (!muted && !bgm) startMusic();
  const btn = document.getElementById("mute");
  if (btn) {
    btn.textContent = muted ? "♪ off" : "♪ on";
    btn.setAttribute("aria-pressed", String(muted));
  }
}

function draw(s) {
  ctx.fillStyle = s.hitFlash > 0 ? "rgb(60,0,0)" : "#000";
  ctx.fillRect(0, 0, W, H);

  ctx.textAlign = "center";
  ctx.fillStyle = "#fff";
  ctx.font = `${s.scorePop > 0 ? 78 : 64}px ui-monospace, Menlo, monospace`;
  ctx.fillText(`Score: ${s.score}`, W / 2, 90);

  ctx.font = "64px ui-monospace, Menlo, monospace";
  ctx.fillText(`Best: ${s.best}`, W / 2, 165);

  ctx.fillStyle = "#d3d3d3";
  ctx.font = "36px ui-monospace, Menlo, monospace";
  ctx.fillText(`Streak: ${s.streak}`, W / 2, 215);

  if (!s.started && !s.running) {
    ctx.fillStyle = "#fff";
    ctx.font = "64px ui-monospace, Menlo, monospace";
    ctx.fillText("TAP TO START", W / 2, H / 2);
    ctx.fillStyle = "#d3d3d3";
    ctx.font = "42px ui-monospace, Menlo, monospace";
    ctx.fillText("DRAG LEFT OR RIGHT", W / 2, H / 2 + 75);
  }

  const popup = (frames, per, text, x, y) => {
    if (frames <= 0) return;
    ctx.save();
    ctx.globalAlpha = Math.min(1, (frames * per) / 255);
    ctx.fillStyle = "#fff";
    ctx.font = "38px ui-monospace, Menlo, monospace";
    ctx.fillText(text, x, y);
    ctx.restore();
  };
  popup(s.nearMiss, 14, "NEAR MISS", s.nearMissX, s.nearMissY - 26);
  popup(s.rankUp, 8, `RANK UP: ${(s.rankUpTitle || "").toUpperCase()}`, W / 2, H / 2 - 120);
  popup(s.newBest, 6, "NEW BEST!", W / 2, H / 2 - 70);

  ctx.fillStyle = "#fff";
  ctx.fillRect(s.px - s.size, s.py - s.size, s.size * 2, s.size * 2);

  ctx.fillStyle = "#f00";
  for (const [x, y, size] of s.obstacles) {
    ctx.fillRect(x - size, y - size, size * 2, size * 2);
  }

  if (s.over) {
    ctx.fillStyle = "rgba(0,0,0,.72)";
    ctx.fillRect(0, H / 2 - 190, W, 380);
    ctx.fillStyle = "#fff";
    ctx.font = "72px ui-monospace, Menlo, monospace";
    ctx.fillText("GAME OVER", W / 2, H / 2 - 70);
    ctx.font = "54px ui-monospace, Menlo, monospace";
    ctx.fillText(`${s.score} — ${s.rank}`, W / 2, H / 2 + 10);
    ctx.fillStyle = "#d3d3d3";
    ctx.font = "40px ui-monospace, Menlo, monospace";
    ctx.fillText("TAP TO PLAY AGAIN", W / 2, H / 2 + 100);
  }
}

function frame(now) {
  // Clamped: returning from a background tab must not teleport the world forward by
  // however long it was away. The engine takes elapsed time rather than counting
  // frames, so a 120Hz display runs the same game as a 60Hz one.
  const elapsed = Math.min(100, now - last);
  last = now;

  engine.update(now, elapsed);

  if (engine.consumeScored() >= 0) play(scoreSound);
  if (engine.consumeDeath()) {
    play(deathSound);
    if (navigator.vibrate) navigator.vibrate(40);
  }

  const s = JSON.parse(engine.state());
  draw(s);

  if (s.over && running) {
    running = false;
    localStorage.setItem(BEST_KEY, String(s.best));
  }
  requestAnimationFrame(frame);
}

function begin() {
  if (running) return;
  startMusic();
  running = true;
  started = true;
  last = performance.now();
  engine.start(last);
}

function boot() {
  fit();
  engine.create(0, W, H);   // the only setBounds this page performs
  setMuted(muted);
  document.getElementById("mute").addEventListener("click", () => setMuted(!muted));
  engine.setBestScore(Number(localStorage.getItem(BEST_KEY) || 0));
  last = performance.now();
  requestAnimationFrame(frame);
}

canvas.addEventListener("pointerdown", (ev) => {
  ev.preventDefault();
  const s = JSON.parse(engine.state());
  if (!s.started || s.over) begin();
  else engine.setTargetX(pointerX(ev));
});
canvas.addEventListener("pointermove", (ev) => {
  if (ev.buttons === 0 && ev.pointerType === "mouse") return;
  ev.preventDefault();
  engine.setTargetX(pointerX(ev));
});
// Keyboard, because a portfolio visitor is probably on a desktop with no touchscreen.
addEventListener("keydown", (ev) => {
  const s = JSON.parse(engine.state());
  if (ev.key === " " || ev.key === "Enter") { if (!s.started || s.over) begin(); return; }
  const step = 90;
  if (ev.key === "ArrowLeft") engine.setTargetX(s.px - step);
  if (ev.key === "ArrowRight") engine.setTargetX(s.px + step);
});
addEventListener("resize", fit);

boot();

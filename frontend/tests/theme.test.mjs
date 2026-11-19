import test from "node:test";
import assert from "node:assert/strict";
import {
  DEFAULT_APPEARANCE,
  THEME_PRESETS,
  resolveAppearanceTokens,
} from "../src/theme-presets.ts";
import { playbackClock } from "../src/playback-clock.ts";

test("theme presets cover 4 light and 4 dark themes with valid hex previews", () => {
  const lights = THEME_PRESETS.filter((p) => p.scheme === "light");
  const darks = THEME_PRESETS.filter((p) => p.scheme === "dark");
  assert.equal(lights.length, 4);
  assert.equal(darks.length, 4);
  for (const preset of THEME_PRESETS) {
    for (const color of Object.values(preset.preview)) {
      assert.match(color, /^#[0-9a-fA-F]{6}$/);
    }
  }
});

test("resolveAppearanceTokens maps system/light/dark modes to bound presets", () => {
  const custom = {
    ...DEFAULT_APPEARANCE,
    lightPreset: "warm-vinyl",
    darkPreset: "vinyl-gold",
  };
  assert.deepEqual(resolveAppearanceTokens(custom, false), {
    resolvedScheme: "light",
    activePreset: "warm-vinyl",
  });
  assert.deepEqual(resolveAppearanceTokens(custom, true), {
    resolvedScheme: "dark",
    activePreset: "vinyl-gold",
  });
  assert.deepEqual(
    resolveAppearanceTokens({ ...custom, mode: "dark" }, false),
    {
      resolvedScheme: "dark",
      activePreset: "vinyl-gold",
    },
  );
});

test("playbackClock throttles sub-50ms jitter and resets cleanly on zero", () => {
  let calls = 0;
  const unsub = playbackClock.subscribe(() => {
    calls += 1;
  });
  playbackClock.set(0);
  const startCalls = calls;
  playbackClock.set(1.0);
  assert.equal(playbackClock.get(), 1.0);
  assert.equal(calls, startCalls + 1);

  playbackClock.set(1.02);
  assert.equal(playbackClock.get(), 1.0);
  assert.equal(calls, startCalls + 1);

  playbackClock.set(1.2);
  assert.equal(playbackClock.get(), 1.2);
  assert.equal(calls, startCalls + 2);

  playbackClock.set(0);
  assert.equal(playbackClock.get(), 0);
  assert.equal(calls, startCalls + 3);
  unsub();
});

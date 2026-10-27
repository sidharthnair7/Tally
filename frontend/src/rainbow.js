import { useEffect, useState } from 'react';
import gsap from 'gsap';
import { ScrollTrigger } from 'gsap/ScrollTrigger';
import { isLite } from './quality.js';

gsap.registerPlugin(ScrollTrigger);

// One hue drives the whole page. It drifts slowly on its own and travels most of the spectrum as you read down the
// story, so every chapter has its own light. CSS reads it as --h; the WebGL backgrounds subscribe to it.
const DRIFT = 2; // degrees a second
const JOURNEY = 250; // degrees from the top of the page to the bottom
const START = 265; // violet

const state = { drift: 0, scroll: 0, target: 0, hue: START, shown: -1 };
const listeners = new Set();
let started = false;

const linearToSrgb = c => (c <= 0.0031308 ? 12.92 * c : 1.055 * c ** (1 / 2.4) - 0.055);
const hex = v => Math.round(Math.min(1, Math.max(0, v)) * 255).toString(16).padStart(2, '0');

// OKLCH to #rrggbb, so colors stay even in brightness as the hue moves.
export function oklch(l, c, h) {
  const r = (h * Math.PI) / 180;
  const a = c * Math.cos(r);
  const b = c * Math.sin(r);
  const l_ = (l + 0.3963377774 * a + 0.2158037573 * b) ** 3;
  const m_ = (l - 0.1055613458 * a - 0.0638541728 * b) ** 3;
  const s_ = (l - 0.0894841775 * a - 1.291485548 * b) ** 3;
  return `#${[
    4.0767416621 * l_ - 3.3077115913 * m_ + 0.2309699292 * s_,
    -1.2684380046 * l_ + 2.6097574011 * m_ - 0.3413193965 * s_,
    -0.0041960863 * l_ - 0.7034186147 * m_ + 1.707614701 * s_
  ]
    .map(v => hex(linearToSrgb(v)))
    .join('')}`;
}

// Four tones for GradientASCII in light mode, from the faint edge of the wash to its strongest point. Light mode
// draws darker colors as denser characters, so the ramp deepens as it goes. The hues stay within 80 degrees of each
// other: a wider fan blends through grey and turns muddy brown in the middle. The rainbow comes from the page hue
// drifting instead.
export function asciiPalette(h) {
  return [oklch(0.86, 0.09, h), oklch(0.74, 0.15, h + 25), oklch(0.64, 0.19, h + 50), oklch(0.56, 0.21, h + 80)];
}

export function startHue() {
  if (started) return;
  started = true;
  const root = document.documentElement;
  const reduce = window.matchMedia('(prefers-reduced-motion: reduce)').matches;

  ScrollTrigger.create({
    start: 0,
    end: 'max',
    onUpdate: self => {
      state.target = self.progress;
    }
  });

  gsap.ticker.add((_, delta) => {
    const dt = Math.min(delta, 50) / 1000;
    if (!reduce) state.drift += dt * DRIFT;
    state.scroll += (state.target - state.scroll) * (1 - Math.exp(-dt * 3));
    state.hue = (START + state.drift + state.scroll * JOURNEY) % 360;
    // Changing --h restyles the whole page, so write it only when it moves a whole step: about twice a second
    // while drifting, and half as often in lite mode.
    const step = isLite() ? 2 : 1;
    const rounded = Math.round(state.hue / step) * step;
    if (rounded === state.shown) return;
    state.shown = rounded;
    root.style.setProperty('--h', rounded);
    listeners.forEach(fn => fn(rounded));
  });
}

// The page hue, in steps of `step` degrees, for components that need colors as values rather than CSS.
export function useHue(step = 2) {
  const [hue, setHue] = useState(() => Math.round(state.hue / step) * step);
  useEffect(() => {
    const fn = h => setHue(Math.round(h / step) * step);
    listeners.add(fn);
    return () => listeners.delete(fn);
  }, [step]);
  return hue;
}

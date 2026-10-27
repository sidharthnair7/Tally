import { useEffect, useState } from 'react';
import gsap from 'gsap';

// Lite mode keeps every effect but makes each one cheaper: the WebGL backgrounds render at 1x pixel ratio, gradients
// repaint less often, scroll reveals skip their blur, and glass panels drop their backdrop blur.
// It starts on for weak hardware or Save-Data, and switches on at runtime if the first seconds run slowly.
// ?lite forces it on and ?full forces it off, for testing.
const KEY = 'tally-lite';
const params = new URLSearchParams(window.location.search);

const read = () => {
  try {
    return window.localStorage.getItem(KEY) === '1';
  } catch {
    return false;
  }
};

const weak =
  (navigator.hardwareConcurrency || 8) <= 4 ||
  (navigator.deviceMemory || 8) <= 4 ||
  navigator.connection?.saveData === true;

const forced = params.has('full') ? false : params.has('lite') ? true : null;
let lite = forced ?? (weak || read());
const listeners = new Set();

const apply = () => {
  document.documentElement.toggleAttribute('data-lite', lite);
  listeners.forEach(fn => fn(lite));
};

export const isLite = () => lite;

// Watch the frame rate for a few seconds after load. Frames slower than 125 ms are the browser throttling a
// background tab, not the page being slow, so they are left out.
export function startQuality() {
  apply();
  if (forced !== null || lite) return;
  const samples = [];
  let wait = 60; // let the intro settle first
  const watch = (_, delta) => {
    if (document.visibilityState !== 'visible' || delta > 125) return;
    if (wait-- > 0) return;
    samples.push(delta);
    if (samples.length < 150) return;
    gsap.ticker.remove(watch);
    samples.sort((a, b) => a - b);
    const fps = 1000 / samples[Math.floor(samples.length / 2)];
    if (fps >= 42) return;
    lite = true;
    try {
      window.localStorage.setItem(KEY, '1');
    } catch {
      // Private mode: lite still applies for this visit.
    }
    apply();
  };
  gsap.ticker.add(watch);
}

export function useLite() {
  const [value, setValue] = useState(lite);
  useEffect(() => {
    listeners.add(setValue);
    setValue(lite);
    return () => listeners.delete(setValue);
  }, []);
  return value;
}

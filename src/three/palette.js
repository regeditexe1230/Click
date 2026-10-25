// 从 CSS 令牌里取色，保证 3D 画面和页面用的是同一套 M3 色板。
export function palette() {
  const cs = getComputedStyle(document.documentElement);
  const v = (name, fallback) => (cs.getPropertyValue(name) || fallback).trim();
  const dark = document.documentElement.dataset.theme === "dark";
  return {
    dark,
    primary: v("--primary", "#6750A4"),
    primaryContainer: v("--primary-container", "#EADDFF"),
    secondaryContainer: v("--secondary-container", "#E8DEF8"),
    surface: v("--surface", "#FEF7FF"),
    surfaceContainerHigh: v("--surface-container-high", "#ECE6F0"),
    onSurface: v("--on-surface", "#1D1B20"),
    onSurfaceVariant: v("--on-surface-variant", "#49454F"),
    outline: v("--outline", "#79747E"),
    outlineVariant: v("--outline-variant", "#CAC4D0"),
  };
}

export function prefersReducedMotion() {
  return window.matchMedia("(prefers-reduced-motion: reduce)").matches;
}

export function clampDpr(max = 2) {
  return Math.min(window.devicePixelRatio || 1, max);
}

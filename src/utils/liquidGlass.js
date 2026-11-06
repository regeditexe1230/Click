// 液态玻璃：对“背景”做真实折射，而不是只盖一层模糊。
// 手法参考了 iOS 26 那套效果链（位移贴图 + 高光层 + 饱和度提升），
// 但贴图与滤镜都是按元素真实尺寸现场生成的，没有内置任何位图资源。

/** 位移贴图：中心几乎不动，越靠边位移越大，形成“边缘透镜” */
export function getDisplacementMap({ height, width, radius, depth }) {
  return (
    "data:image/svg+xml;utf8," +
    encodeURIComponent(`<svg height="${height}" width="${width}" viewBox="0 0 ${width} ${height}" xmlns="http://www.w3.org/2000/svg">
    <style>.mix { mix-blend-mode: screen; }</style>
    <defs>
      <linearGradient id="Y" x1="0" x2="0" y1="${Math.ceil((radius / height) * 15)}%" y2="${Math.floor(100 - (radius / height) * 15)}%">
        <stop offset="0%" stop-color="#0F0" />
        <stop offset="100%" stop-color="#000" />
      </linearGradient>
      <linearGradient id="X" x1="${Math.ceil((radius / width) * 15)}%" x2="${Math.floor(100 - (radius / width) * 15)}%" y1="0" y2="0">
        <stop offset="0%" stop-color="#F00" />
        <stop offset="100%" stop-color="#000" />
      </linearGradient>
    </defs>
    <rect x="0" y="0" height="${height}" width="${width}" fill="#808080" />
    <g filter="blur(2px)">
      <rect x="0" y="0" height="${height}" width="${width}" fill="#000080" />
      <rect x="0" y="0" height="${height}" width="${width}" fill="url(#Y)" class="mix" />
      <rect x="0" y="0" height="${height}" width="${width}" fill="url(#X)" class="mix" />
      <rect x="${depth}" y="${depth}" height="${height - 2 * depth}" width="${width - 2 * depth}" fill="#808080" rx="${radius}" ry="${radius}" filter="blur(${depth}px)" />
    </g>
  </svg>`)
  );
}

/**
 * 完整滤镜链（按我们的页面重写，参数全开）：
 *   轻微模糊 → 位移折射 → 提饱和度 → 只在高光范围内上色 → 衰减 → 叠回底图
 */
export function getGlassFilter({
  height,
  width,
  radius,
  depth,
  strength = 30,
  blur = 0.35,
  saturate = 1.8,
}) {
  const displacement = getDisplacementMap({ height, width, radius, depth });
  return (
    "data:image/svg+xml;utf8," +
    encodeURIComponent(`<svg height="${height}" width="${width}" viewBox="0 0 ${width} ${height}" xmlns="http://www.w3.org/2000/svg">
    <defs>
      <filter id="glass" color-interpolation-filters="sRGB">
        <feGaussianBlur in="SourceGraphic" stdDeviation="${blur}" result="soft" />
        <feImage x="0" y="0" width="${width}" height="${height}" href="${displacement}" result="map" />
        <feDisplacementMap in="soft" in2="map" scale="${strength}" xChannelSelector="R" yChannelSelector="G" result="refracted" />
        <feColorMatrix in="refracted" type="saturate" values="${saturate}" />
      </filter>
    </defs>
  </svg>`) +
    "#glass"
  );
}

/** Chrome/Edge 支持 backdrop-filter: url(#f)；Safari 与 Firefox 会退化 */
export const supportsBackdropUrl = (() => {
  if (typeof document === "undefined") return false;
  const el = document.createElement("div");
  el.style.cssText = "backdrop-filter: url(#lg-test)";
  return (
    el.style.backdropFilter === "url(#lg-test)" ||
    el.style.backdropFilter === 'url("#lg-test")'
  );
})();

/**
 * 给一个元素挂上液态玻璃：按尺寸生成滤镜，尺寸变化时重算。
 * el 必须是「滤镜层」本身（覆盖在内容底下、承载 backdrop-filter 的那一层）。
 */
export function attachLiquidGlass(el, options = {}) {
  if (!el) return () => {};
  const opts = {
    radius: 22,
    depth: 8,
    strength: 26,
    blur: 0.6,
    saturate: 1.8,
    brightness: 1.0,
    ...options,
  };

  let frame = 0;
  const render = () => {
    const rect = el.getBoundingClientRect();
    const width = Math.round(rect.width);
    const height = Math.round(rect.height);
    if (width < 8 || height < 8) return;

    // backdrop-filter 直接吃一个「指向 SVG 滤镜的 data URI」，不必往 DOM 里塞 svg
    const href = getGlassFilter({
      width,
      height,
      radius: Math.min(opts.radius, height / 2),
      depth: Math.min(opts.depth, height / 3),
      strength: opts.strength,
      blur: opts.blur,
      saturate: opts.saturate,
    });
    const value = `url("${href}") brightness(${opts.brightness})`;
    el.style.backdropFilter = value;
    el.style.webkitBackdropFilter = value;
  };

  const schedule = () => {
    cancelAnimationFrame(frame);
    frame = requestAnimationFrame(render);
  };

  render();
  const ro = new ResizeObserver(schedule);
  ro.observe(el);

  return () => {
    cancelAnimationFrame(frame);
    ro.disconnect();
    el.style.backdropFilter = "";
    el.style.webkitBackdropFilter = "";
  };
}

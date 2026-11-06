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
 * 高光层：沿圆角描一圈“从上打光”的白边，模拟玻璃边缘的镜面反射。
 * 没有这一层，纯折射看起来只是一块被扭弯的毛玻璃。
 */
export function getSpecularMap({ height, width, radius }) {
  const inset = 1.5;
  const stroke = Math.max(1.5, Math.min(height, width) * 0.035);
  return (
    "data:image/svg+xml;utf8," +
    encodeURIComponent(`<svg height="${height}" width="${width}" viewBox="0 0 ${width} ${height}" xmlns="http://www.w3.org/2000/svg">
    <defs>
      <linearGradient id="s" x1="0.15" y1="0" x2="0.35" y2="1">
        <stop offset="0%" stop-color="#fff" stop-opacity="0.85" />
        <stop offset="38%" stop-color="#fff" stop-opacity="0.28" />
        <stop offset="72%" stop-color="#fff" stop-opacity="0.06" />
        <stop offset="100%" stop-color="#fff" stop-opacity="0.22" />
      </linearGradient>
    </defs>
    <rect x="${inset}" y="${inset}" width="${Math.max(0, width - inset * 2)}" height="${Math.max(0, height - inset * 2)}"
          rx="${Math.max(0, radius - inset)}" ry="${Math.max(0, radius - inset)}"
          fill="none" stroke="url(#s)" stroke-width="${stroke}" />
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
  strength = 26,
  blur = 0.6,
  saturate = 2.2,
  specularOpacity = 0.42,
}) {
  const displacement = getDisplacementMap({ height, width, radius, depth });
  const specular = getSpecularMap({ height, width, radius });
  return (
    "data:image/svg+xml;utf8," +
    encodeURIComponent(`<svg height="${height}" width="${width}" viewBox="0 0 ${width} ${height}" xmlns="http://www.w3.org/2000/svg">
    <defs>
      <filter id="glass" color-interpolation-filters="sRGB">
        <feGaussianBlur in="SourceGraphic" stdDeviation="${blur}" result="soft" />
        <feImage x="0" y="0" width="${width}" height="${height}" href="${displacement}" result="map" />
        <feDisplacementMap in="soft" in2="map" scale="${strength}" xChannelSelector="R" yChannelSelector="G" result="refracted" />
        <feColorMatrix in="refracted" type="saturate" values="${saturate}" result="vivid" />
        <feImage x="0" y="0" width="${width}" height="${height}" href="${specular}" result="spec" />
        <feComposite in="vivid" in2="spec" operator="in" result="specTinted" />
        <feComponentTransfer in="spec" result="specFaded">
          <feFuncA type="linear" slope="${specularOpacity}" />
        </feComponentTransfer>
        <feBlend in="specTinted" in2="refracted" mode="normal" result="withTint" />
        <feBlend in="specFaded" in2="withTint" mode="normal" />
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
    saturate: 2.2,
    specularOpacity: 0.42,
    brightness: 1.04,
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
      specularOpacity: opts.specularOpacity,
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

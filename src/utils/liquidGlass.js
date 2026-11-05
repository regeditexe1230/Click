// 液态玻璃：用 SVG 位移贴图做真实的折射（不是单纯模糊）
// 思路来自 iOS 26 那套：边缘一圈做位移、中心几乎不动，再叠一点点色散。

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
    <defs>
      <radialGradient id="LENS" cx="50%" cy="50%" r="62%">
        <stop offset="0%" stop-color="#FF1010" />
        <stop offset="55%" stop-color="#800000" />
        <stop offset="100%" stop-color="#000000" />
      </radialGradient>
    </defs>
    <rect x="0" y="0" height="${height}" width="${width}" fill="#808080" />
    <rect x="0" y="0" height="${height}" width="${width}" fill="url(#LENS)" opacity="0.07" class="mix" />
    <g filter="blur(2px)">
      <rect x="0" y="0" height="${height}" width="${width}" fill="#000080" />
      <rect x="0" y="0" height="${height}" width="${width}" fill="url(#Y)" class="mix" />
      <rect x="0" y="0" height="${height}" width="${width}" fill="url(#X)" class="mix" />
      <rect x="${depth}" y="${depth}" height="${height - 2 * depth}" width="${width - 2 * depth}" fill="#808080" rx="${radius}" ry="${radius}" filter="blur(${depth}px)" />
    </g>
  </svg>`)
  );
}

export function getDisplacementFilter({
  height,
  width,
  radius,
  depth,
  strength = 100,
  chromaticAberration = 0,
}) {
  return (
    "data:image/svg+xml;utf8," +
    encodeURIComponent(`<svg height="${height}" width="${width}" viewBox="0 0 ${width} ${height}" xmlns="http://www.w3.org/2000/svg">
    <defs>
      <filter id="displace" color-interpolation-filters="sRGB">
        <feImage x="0" y="0" height="${height}" width="${width}" href="${getDisplacementMap({ height, width, radius, depth })}" result="displacementMap" />
        <feDisplacementMap in="SourceGraphic" in2="displacementMap" scale="${strength + chromaticAberration * 2}" xChannelSelector="R" yChannelSelector="G" />
        <feColorMatrix type="matrix" values="1 0 0 0 0  0 0 0 0 0  0 0 0 0 0  0 0 0 1 0" result="displacedR" />
        <feDisplacementMap in="SourceGraphic" in2="displacementMap" scale="${strength + chromaticAberration}" xChannelSelector="R" yChannelSelector="G" />
        <feColorMatrix type="matrix" values="0 0 0 0 0  0 1 0 0 0  0 0 0 0 0  0 0 0 1 0" result="displacedG" />
        <feDisplacementMap in="SourceGraphic" in2="displacementMap" scale="${strength}" xChannelSelector="R" yChannelSelector="G" />
        <feColorMatrix type="matrix" values="0 0 0 0 0  0 0 0 0 0  0 0 1 0 0  0 0 0 1 0" result="displacedB" />
        <feBlend in="displacedR" in2="displacedG" mode="screen" />
        <feBlend in2="displacedB" mode="screen" />
      </filter>
    </defs>
  </svg>`) +
    "#displace"
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
 * 给一个元素挂上液态玻璃：生成滤镜、按尺寸重算、监听尺寸变化。
 * el 必须是「滤镜层」本身（覆盖在内容底下、承载 backdrop-filter 的那一层）。
 */
export function attachLiquidGlass(el, options = {}) {
  if (!el) return () => {};
  const opts = {
    radius: 22,
    depth: 10,
    strength: 60,
    chromaticAberration: 6,
    saturate: 1.7,
    brightness: 1.05,
    ...options,
  };

  const render = () => {
    const rect = el.getBoundingClientRect();
    const width = Math.round(rect.width);
    const height = Math.round(rect.height);
    if (width < 8 || height < 8) return;

    // backdrop-filter 直接吃一个「指向 SVG 滤镜的 data URI」，不需要往 DOM 里塞 svg
    const href = getDisplacementFilter({
      width,
      height,
      radius: opts.radius,
      depth: opts.depth,
      strength: opts.strength,
      chromaticAberration: opts.chromaticAberration,
    });
    const value = `url("${href}") saturate(${opts.saturate}) brightness(${opts.brightness})`;
    el.style.backdropFilter = value;
    el.style.webkitBackdropFilter = value;
  };

  render();
  const ro = new ResizeObserver(render);
  ro.observe(el);

  return () => {
    ro.disconnect();
    el.style.backdropFilter = "";
    el.style.webkitBackdropFilter = "";
  };
}

// 液态玻璃：直接照搬参考实现的那条滤镜链，只把元素尺寸换成我们的。
// 位移贴图与高光层是参考页那两张 640×63 的 PNG（放在 public/brand/ 下），
// 这里通过 preserveAspectRatio="none" 把它们拉伸到顶栏尺寸，滤镜仍是同一条。
export const GLASS_FILTER_ID = "header-glass";

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
 * 把内联 SVG 里的滤镜挂到元素上。
 * 元素尺寸变化时不需要重算——滤镜用的是百分比尺寸 + 拉伸，这一点和参考一致。
 */
export function attachLiquidGlass(el, { filterId = GLASS_FILTER_ID } = {}) {
  if (!el) return () => {};
  const value = `url(#${filterId})`;
  el.style.backdropFilter = value;
  el.style.webkitBackdropFilter = value;
  return () => {
    el.style.backdropFilter = "";
    el.style.webkitBackdropFilter = "";
  };
}

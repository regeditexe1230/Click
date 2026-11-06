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

/**
 * 按元素当前的像素宽度重新采样位移/高光贴图：
 * 左右各 cap 像素原样拷贝（保住圆角曲率），只把中间那段拉伸。
 * 参考实现里贴图尺寸是写死的，窗口一变窄玻璃就会歪——这里改成跟着元素走。
 */
export function syncGlassMaps(el, { filterId = GLASS_FILTER_ID, cap = 40 } = {}) {
  const root = document.getElementById(`${filterId}-svg`) || document;
  const disp = root.querySelector(`#${filterId}-displacement`);
  const spec = root.querySelector(`#${filterId}-specular`);
  if (!el || !disp || !spec) return;

  const rect = el.getBoundingClientRect();
  const w = Math.max(120, Math.round(rect.width));
  const h = Math.max(32, Math.round(rect.height));
  if (disp.dataset.w === `${w}x${h}`) return;
  disp.dataset.w = `${w}x${h}`;

  const build = (src, target) =>
    src.then((img) => {
      const cv = document.createElement("canvas");
      cv.width = w;
      cv.height = h;
      const ctx = cv.getContext("2d");
      const sw = img.naturalWidth;
      const c = Math.min(cap, Math.floor(sw / 2));
      ctx.drawImage(img, 0, 0, c, h, 0, 0, c, h);
      ctx.drawImage(img, c, 0, sw - c * 2, h, c, 0, w - c * 2, h);
      ctx.drawImage(img, sw - c, 0, c, h, w - c, 0, c, h);
      target.setAttribute("href", cv.toDataURL());
      target.setAttribute("width", String(w));
      target.setAttribute("height", String(h));
    });

  const load = (url) =>
    new Promise((res) => {
      const img = new Image();
      img.onload = () => res(img);
      img.onerror = () => res(img);
      img.src = url;
    });

  build(load("/brand/glass-displacement.png"), disp);
  build(load("/brand/glass-specular.png"), spec);
}

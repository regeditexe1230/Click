// 滚动出现：和原生版行为一致——元素进入视口就加 is-visible，只在第一次触发。
// 没有 IntersectionObserver 时直接显示，别把内容藏起来。
const supported = typeof IntersectionObserver !== "undefined";

let observer = null;

function getObserver() {
  if (!supported) return null;
  if (!observer) {
    observer = new IntersectionObserver(
      (entries) => {
        for (const entry of entries) {
          if (!entry.isIntersecting) continue;
          entry.target.classList.add("is-visible");
          observer.unobserve(entry.target);
        }
      },
      { rootMargin: "0px 0px -12% 0px", threshold: 0.08 }
    );
  }
  return observer;
}

export default {
  mounted(el) {
    el.classList.add("reveal");
    const io = getObserver();
    if (!io) {
      el.classList.add("is-visible");
      return;
    }
    io.observe(el);
  },
  unmounted(el) {
    if (observer) observer.unobserve(el);
  },
};

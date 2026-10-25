<script setup>
import { onBeforeUnmount, onMounted, ref } from "vue";

const HOLD_MS = 1000;
const MOVE_MS = 900;
const LEAVE_MS = 460;
const SESSION_KEY = "click-intro";

const emit = defineEmits(["done"]);

const host = ref(null);
const logo = ref(null);
const leaving = ref(false);

let finished = false;
let skipping = false;
let animation = null;
let holdTimer = 0;
let leaveTimer = 0;
let keyHandler = null;

function finish(instant) {
  if (finished) return;
  finished = true;
  clearTimeout(holdTimer);
  clearTimeout(leaveTimer);

  if (keyHandler) window.removeEventListener("keydown", keyHandler);

  document.documentElement.classList.remove("intro-active");
  document.documentElement.classList.add("intro-done");

  // 同一个标签页会话只播一次：刷新、返回都不再挡页面
  try {
    sessionStorage.setItem(SESSION_KEY, "1");
  } catch (err) {
    /* 隐私模式下写不进去也无所谓 */
  }

  // 先把 logo 停在终点，再淡出遮罩，最后才让页头那张静态 logo 单独留在那儿
  leaving.value = true;
  if (instant) {
    emit("done");
    return;
  }
  leaveTimer = window.setTimeout(() => emit("done"), LEAVE_MS);
}

function run() {
  const el = logo.value;
  const target = document.querySelector(".site-header .brand-mark");
  if (!el || !target || typeof el.animate !== "function") {
    finish(true);
    return;
  }

  const from = el.getBoundingClientRect();
  const to = target.getBoundingClientRect();
  if (!from.height || !to.height) {
    finish(true);
    return;
  }

  const dx = to.left + to.width / 2 - (from.left + from.width / 2);
  const dy = to.top + to.height / 2 - (from.top + from.height / 2);
  const scale = to.height / from.height;

  animation = el.animate(
    [
      { transform: "translate3d(0, 0, 0) scale(1) rotateY(0deg)", offset: 0 },
      { transform: "translate3d(0, 0, 0) scale(1.05) rotateY(0deg)", offset: 0.16 },
      {
        transform: `translate3d(${dx * 0.34}px, ${dy * 0.34}px, 0) scale(${
          1 + (scale - 1) * 0.3
        }) rotateY(-140deg)`,
        offset: 0.54,
      },
      {
        transform: `translate3d(${dx * 0.86}px, ${dy * 0.86}px, 0) scale(${
          1 + (scale - 1) * 0.86
        }) rotateY(-330deg)`,
        offset: 0.9,
      },
      { transform: `translate3d(${dx}px, ${dy}px, 0) scale(${scale}) rotateY(-360deg)`, offset: 1 },
    ],
    {
      duration: MOVE_MS,
      delay: HOLD_MS,
      easing: "cubic-bezier(0.34, 0.04, 0.12, 1)",
      fill: "forwards",
    }
  );

  animation.onfinish = () => finish(false);
}

// 跳过逻辑保留：没有按钮，但 Esc / Enter / 空格 或点头图以外的地方可以提前收场，
// 以后要接「自动跳过」「调试直通」也走这里。
function skip() {
  if (finished || skipping) return;
  skipping = true;
  if (animation) {
    try {
      animation.finish();
    } catch (err) {
      /* 动画已被取消 */
    }
  }
  finish(false);
}

onMounted(() => {
  // 每次都完整播一遍；只有系统级「减少动效」才直接放行。
  const reduce = window.matchMedia("(prefers-reduced-motion: reduce)").matches;
  if (reduce) {
    finish(true);
    return;
  }

  document.documentElement.classList.add("intro-active");

  keyHandler = (event) => {
    if (event.key === "Escape" || event.key === "Enter" || event.key === " ") skip();
  };
  window.addEventListener("keydown", keyHandler);

  // 量尺寸要等 logo 图和页头都排完版
  holdTimer = window.setTimeout(run, 120);
});

onBeforeUnmount(() => {
  clearTimeout(holdTimer);
  clearTimeout(leaveTimer);
  if (keyHandler) window.removeEventListener("keydown", keyHandler);
  document.documentElement.classList.remove("intro-active");
});
</script>

<template>
  <div
    ref="host"
    class="intro-layer"
    :class="{ 'is-leaving': leaving }"
    role="presentation"
    @click.self="skip"
  >
    <div class="intro-stage">
      <div ref="logo" class="intro-logo">
        <span class="intro-logo__face">
          <img class="intro-logo__img intro-logo__img--light" src="/brand/logo-light.png" alt="" width="557" height="768" draggable="false" />
          <img class="intro-logo__img intro-logo__img--dark" src="/brand/logo-dark.png" alt="" width="557" height="768" draggable="false" />
        </span>
        <!-- 转过半圈时露的是同一张图，不会看到镜像 logo -->
        <span class="intro-logo__face intro-logo__face--back">
          <img class="intro-logo__img intro-logo__img--light" src="/brand/logo-light.png" alt="" width="557" height="768" draggable="false" />
          <img class="intro-logo__img intro-logo__img--dark" src="/brand/logo-dark.png" alt="" width="557" height="768" draggable="false" />
        </span>
      </div>
    </div>
  </div>
</template>

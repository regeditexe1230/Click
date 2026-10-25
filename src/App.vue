<script setup>
import { computed, defineAsyncComponent, onBeforeUnmount, onMounted, ref, watch } from "vue";
import IntroLogo from "./components/IntroLogo.vue";

const NodeField = defineAsyncComponent(() => import("./components/NodeField.vue"));
const GraphScene = defineAsyncComponent(() => import("./components/GraphScene.vue"));

const BASE = import.meta.env.BASE_URL;
const MODE_KEY = "click-theme-mode";
const LEGACY_KEY = "click-theme";
const RELEASES_URL = "https://github.com/regeditexe1230/Click/releases/latest";
const RELEASES_LIST_URL = "https://github.com/regeditexe1230/Click/releases";
const REPO_URL = "https://github.com/regeditexe1230/Click";


function readMode() {
  try {
    const saved = localStorage.getItem(MODE_KEY);
    if (saved === "system" || saved === "light" || saved === "dark") return saved;
    // 旧版单键切换留下的 click-theme 不再沿用：默认就该是「跟随系统」，
    // 只有用户自己在菜单里选过才算手动设定过。
    localStorage.removeItem(LEGACY_KEY);
  } catch (err) {
    /* 隐私模式下读不到，按跟随系统处理 */
  }
  return "system";
}

const themeMode = ref(readMode());
const prefersDark = ref(
  typeof window !== "undefined" &&
    window.matchMedia("(prefers-color-scheme: dark)").matches
);
const theme = computed(() =>
  themeMode.value === "system" ? (prefersDark.value ? "dark" : "light") : themeMode.value
);



const introVisible = ref(document.documentElement.classList.contains("intro-active"));
const hero3d = ref(false);
const graph3d = ref(false);
const isWide = ref(true);
const graphHost = ref(null);
const menu = ref(null);

const isDark = computed(() => theme.value === "dark");
const year = new Date().getFullYear();

// 三类节点：点卡片展开一张真机截图，框出这一类节点在图上的位置。
// spot 是相对截图的百分比，方便换图时只调数字。
const KINDS = [
  {
    id: "event",
    dot: "dot-event",
    label: "事件",
    title: "事件 · 流程的起点",
    body:
      "一个程序里可以放几个起点，各自跑各自的流程，互不干扰。起点节点用紫色，画布和托盘里都是同一个颜色。",
    node: "node-event",
    note: "这是托盘里「开始 / 起点」整张卡抠出来的图：一个程序可以有多个起点，互不干扰",
  },
  {
    id: "action",
    dot: "dot-action",
    label: "动作",
    title: "动作 · 真正干活的那一步",
    body:
      "点击、延时、输入都算动作，参数直接写在卡片上：点哪儿、等多久、输什么，一眼看得到，不用再点进二级页面。",
    node: "node-action",
    note: "这是画布上「点击 ×1」整张卡抠出来的图：点哪里、点几次、点多久，都写在卡片上",
  },
  {
    id: "control",
    dot: "dot-control",
    label: "控制",
    title: "控制 · 让流程拐弯",
    body:
      "条件、循环这些节点决定流程怎么走；它们和动作节点混在一起，从卡片右边的圆点拖出连线就接上了。",
    node: "node-control",
    note: "这是画布上「循环 ×1」整张卡抠出来的图：重复几次、哪一步跳出，都写在卡片上",
  },
];

const openKind = ref(null);
const activeKind = computed(() => KINDS.find((k) => k.id === openKind.value) || null);

function nodeSrc(base) {
  const variant = isDark.value ? "dark" : "light";
  return BASE + "screenshots/nodes/" + base + "-" + variant + ".png";
}

function toggleKind(id) {
  openKind.value = openKind.value === id ? null : id;
}

// 深色主题下换成 App 深色模式的截图：<base>-dark.png。
// 图还没放进来时 onerror 把当前这张退回浅色版，不会留破图。
const shotFallbacks = new Set();

function shotSrc(base) {
  const dark = isDark.value && !shotFallbacks.has(base);
  return `${BASE}screenshots/${base}${dark ? "-dark" : ""}.png`;
}

function shotFallback(event) {
  const img = event.target;
  const name = img.getAttribute("src").split("/").pop() || "";
  const base = name.replace("-dark.png", "").replace(".png", "");
  if (!shotFallbacks.has(base)) {
    shotFallbacks.add(base);
    img.src = `${BASE}screenshots/${base}.png`;
  }
}

let mediaQuery = null;
let darkQuery = null;
let graphObserver = null;
let themeMeta = null;

function paintTheme(next) {
  document.documentElement.dataset.theme = next;
  document.documentElement.dataset.themeMode = themeMode.value;
  if (themeMeta && themeMeta.length) {
    themeMeta.forEach((tag) => {
      tag.setAttribute("content", next === "dark" ? "#141218" : "#FEF7FF");
    });
  }
}

function persistMode(next) {
  try {
    localStorage.setItem(MODE_KEY, next);
    localStorage.removeItem(LEGACY_KEY);
  } catch (err) {
    /* 写不了就算了，本次会话内仍然生效 */
  }
}

// 切换配色方案。App 里用 ThemeReveal 做从点击点扩散的圆形过渡，
// 网页这边用 View Transitions 的 clip-path 圆扩散对齐，不支持就直接换色。
function setMode(next, event) {
  if (next === themeMode.value) return;
  const apply = () => {
    themeMode.value = next;
    persistMode(next);
    paintTheme(next === "system" ? (prefersDark.value ? "dark" : "light") : next);
  };

  const reduce = window.matchMedia("(prefers-reduced-motion: reduce)").matches;
  if (reduce || typeof document.startViewTransition !== "function" || !event) {
    apply();
    return;
  }

  const x = event.clientX || window.innerWidth / 2;
  const y = event.clientY || 60;
  const root = document.documentElement;
  root.style.setProperty("--reveal-x", x + "px");
  root.style.setProperty("--reveal-y", y + "px");
  root.style.setProperty(
    "--reveal-r",
    Math.hypot(Math.max(x, window.innerWidth - x), Math.max(y, window.innerHeight - y)) + "px"
  );
  root.classList.add("theme-revealing");
  const transition = document.startViewTransition(apply);
  transition.finished.finally(() => root.classList.remove("theme-revealing"));
}

function closeMenu() {
  if (menu.value) menu.value.removeAttribute("open");
}

// 点一下就是切换颜色：当前是深色就变浅色，反之亦然。
// 没点过的时候处于「跟随系统」，第一次点会固定成与当前相反的那一档。
function toggleColorScheme(event) {
  setMode(isDark.value ? "light" : "dark", event);
}

function onMediaChange(event) {
  isWide.value = event.matches;
  if (event.matches) observeGraph();
}

// 跟随系统时，系统切深色/浅色要立刻跟着变，不刷新页面
function onSystemThemeChange(event) {
  prefersDark.value = event.matches;
  if (themeMode.value === "system") paintTheme(event.matches ? "dark" : "light");
}

function observeGraph() {
  if (!graphHost.value || !("IntersectionObserver" in window)) {
    graph3d.value = true;
    return;
  }
  if (graphObserver) return;
  graphObserver = new IntersectionObserver(
    (entries) => {
      if (entries.some((e) => e.isIntersecting)) {
        graph3d.value = true;
        graphObserver.disconnect();
        graphObserver = null;
      }
    },
    { rootMargin: "320px 0px" }
  );
  graphObserver.observe(graphHost.value);
}

onMounted(() => {
  themeMeta = document.querySelectorAll('meta[name="theme-color"]');
  mediaQuery = window.matchMedia("(min-width: 720px)");
  isWide.value = mediaQuery.matches;
  mediaQuery.addEventListener("change", onMediaChange);

  darkQuery = window.matchMedia("(prefers-color-scheme: dark)");
  prefersDark.value = darkQuery.matches;
  darkQuery.addEventListener("change", onSystemThemeChange);
  paintTheme(theme.value);

  // 首屏先把文字和截图画出来，3D 晚一步加载，别抢首屏
  const idle = window.requestIdleCallback || ((fn) => setTimeout(fn, 220));
  idle(() => {
    hero3d.value = true;
  });

  observeGraph();
});

onBeforeUnmount(() => {
  if (mediaQuery) mediaQuery.removeEventListener("change", onMediaChange);
  if (darkQuery) darkQuery.removeEventListener("change", onSystemThemeChange);
  if (graphObserver) graphObserver.disconnect();
});

watch(isDark, (value) => {
  const color = value ? "#141218" : "#FEF7FF";
  document.querySelectorAll('meta[name="theme-color"]').forEach((tag) => {
    tag.setAttribute("content", color);
  });
});
</script>

<template>
  <Teleport to="body">
    <IntroLogo v-if="introVisible" :theme="theme" @done="introVisible = false" />
  </Teleport>

  <a class="skip-link" href="#main">跳到主要内容</a>

  <header class="site-header">
    <div class="container header-inner">
      <a class="brand" href="#top" aria-label="Click 首页">
        <span class="brand-mark" aria-hidden="true">
          <img class="brand-logo brand-logo--light" src="/brand/logo-light.png" alt="" width="557" height="768" />
          <img class="brand-logo brand-logo--dark" src="/brand/logo-dark.png" alt="" width="557" height="768" />
        </span>
        <span>Click</span>
      </a>

      <nav class="site-nav" aria-label="主导航">
        <a href="#features">功能</a>
        <a href="#graph">节点图</a>
        <a href="#start">上手</a>
        <a href="#download">下载</a>
      </nav>

      <div class="header-actions">
        <details ref="menu" class="nav-more">
          <summary aria-label="展开导航菜单">
            <span class="nav-more-label">菜单</span>
          </summary>
          <nav class="nav-more-panel" aria-label="移动端导航">
            <a href="#features" @click="closeMenu">功能</a>
            <a href="#graph" @click="closeMenu">节点图</a>
            <a href="#start" @click="closeMenu">上手</a>
            <a href="#download" @click="closeMenu">下载</a>
          </nav>
        </details>

        <button
          class="icon-button"
          data-theme-toggle
          type="button"
          :aria-label="isDark ? '切换到浅色' : '切换到深色'"
          :title="isDark ? '切换到浅色' : '切换到深色'"
          @click="toggleColorScheme"
        >
          <svg
            v-if="isDark"
            class="icon-sun"
            viewBox="0 0 24 24"
            aria-hidden="true"
            focusable="false"
          >
            <circle cx="12" cy="12" r="4.2" />
            <path
              d="M12 2.6v2.2M12 19.2v2.2M2.6 12h2.2M19.2 12h2.2M5.3 5.3l1.6 1.6M17.1 17.1l1.6 1.6M18.7 5.3l-1.6 1.6M6.9 17.1l-1.6 1.6"
            />
          </svg>
          <svg
            v-else
            class="icon-moon"
            viewBox="0 0 24 24"
            aria-hidden="true"
            focusable="false"
          >
            <path d="M21 12.79A9 9 0 1 1 11.21 3 7 7 0 0 0 21 12.79Z" />
          </svg>
        </button>
      </div>
    </div>
  </header>

  <main id="main">
    <section class="hero" id="top">
      <NodeField v-if="hero3d && isWide" :theme="theme" />

      <div class="container hero-inner">
        <div class="hero-copy">
          <p class="eyebrow">
            <span class="eyebrow-dot" aria-hidden="true"></span>Android · 本地运行
          </p>
          <h1>把重复的操作，<br class="br-wide" />画成一条流程。</h1>
          <p class="lede">
            Click 不用写代码。把「点击」「延时」「动作」「控制」拖到画布上、连上线，
            就是一个程序；点一下播放，它按顺序替你跑完。
          </p>
          <div class="hero-actions">
            <a class="btn btn-primary" :href="RELEASES_LIST_URL" rel="noopener">
              下载 APK
              <svg viewBox="0 0 24 24" aria-hidden="true" focusable="false" class="btn-icon">
                <path d="M12 4v11M7.5 10.5 12 15l4.5-4.5M5 19h14" />
              </svg>
            </a>
            <a class="btn btn-tonal" href="#graph">看看节点图长什么样</a>
          </div>
          <ul class="hero-meta">
            <li>APK 约 4 MB</li>
            <li>Android 8.0+</li>
            <li>不联网也能跑</li>
          </ul>
        </div>

        <div class="hero-visual">
          <div class="phone">
            <div class="phone-screen">
              <img
                :src="shotSrc('graph-clean')"
                @error="shotFallback"
                width="1080"
                height="2265"
                alt="Click 的节点图画布：顶部是工具栏，画布中间放着一张「点击 ×1」节点卡，左右各有一个连线圆点；底部是事件 / 动作 / 控制托盘，托盘里放着一张「开始」起点卡。"
              />
            </div>
            <div class="phone-glow" aria-hidden="true"></div>
          </div>
          <p class="hero-caption">节点图画布 · 事件 / 动作 / 控制三类节点</p>
        </div>
      </div>
    </section>

    <section id="features" class="section">
      <div class="container">
        <div v-reveal class="section-head">
          <p class="eyebrow">功能</p>
          <h2>一个画布，把想做的操作摆出来</h2>
          <p class="section-lede">
            每个程序都是一张图。节点说明「做什么」，连线说明「先后」，
            流程长起来也不会乱成一团。
          </p>
        </div>

        <div class="grid grid-3">
          <article v-reveal class="card">
            <span class="card-icon" aria-hidden="true">
              <svg viewBox="0 0 24 24" focusable="false">
                <rect x="4" y="5" width="16" height="6" rx="2" />
                <rect x="4" y="13" width="16" height="6" rx="2" />
                <path d="M8 8h1.5M8 16h1.5" />
              </svg>
            </span>
            <h3>多程序管理</h3>
            <p>一个程序一张卡：节点数、类型色条一眼看清，编辑、复制、删除都在卡上，右下角加号新建。</p>
          </article>

          <article v-reveal class="card">
            <span class="card-icon" aria-hidden="true">
              <svg viewBox="0 0 24 24" focusable="false">
                <path
                  d="M6 4v6M6 14v6M18 4v6M18 14v6M6 10h12M9 10a3 3 0 1 0 6 0 3 3 0 0 0-6 0Z"
                />
              </svg>
            </span>
            <h3>拖拽连节点</h3>
            <p>按住卡片就能拖；从右边缘的圆点拉出连线，落到另一个节点上就接上了。</p>
          </article>

          <article v-reveal class="card">
            <span class="card-icon" aria-hidden="true">
              <svg viewBox="0 0 24 24" focusable="false">
                <path
                  d="M4 8V5.5A1.5 1.5 0 0 1 5.5 4H8M16 4h2.5A1.5 1.5 0 0 1 20 5.5V8M20 16v2.5a1.5 1.5 0 0 1-1.5 1.5H16M8 20H5.5A1.5 1.5 0 0 1 4 18.5V16"
                />
                <path d="M9 9h6v6H9z" />
              </svg>
            </span>
            <h3>框选与撤销</h3>
            <p>空白处按住约 0.7 秒出现选框，一次圈住多个节点成批移动；顶部常驻撤销、重做。</p>
          </article>

          <article v-reveal class="card">
            <span class="card-icon" aria-hidden="true">
              <svg viewBox="0 0 24 24" focusable="false">
                <rect x="4" y="4" width="16" height="9" rx="2" />
                <path d="M8 8h6M8 10.5h4M6 17h12M9 20h6" />
              </svg>
            </span>
            <h3>参数写在卡片上</h3>
            <p>点击次数、延时毫秒、输入内容都直接写在节点卡片里，不用再点进二级页面。</p>
          </article>

          <article v-reveal class="card">
            <span class="card-icon" aria-hidden="true">
              <svg viewBox="0 0 24 24" focusable="false">
                <path d="M8 5.5v13l10-6.5-10-6.5Z" />
              </svg>
            </span>
            <h3>播放与试跑</h3>
            <p>画完点播放就按连线顺序跑一遍，可以先试跑再保存；手势与自动化入口就在程序里。</p>
          </article>

          <article v-reveal class="card">
            <span class="card-icon" aria-hidden="true">
              <svg viewBox="0 0 24 24" focusable="false">
                <circle cx="12" cy="12" r="8.2" />
                <path d="M12 3.8v16.4a8.2 8.2 0 0 0 0-16.4Z" />
              </svg>
            </span>
            <h3>深色与 5 套配色</h3>
            <p>跟随系统或手动切换，紫、蓝、红、黄、绿五套种子色，夜里看着不刺眼。</p>
          </article>

          <article v-reveal class="card">
            <span class="card-icon" aria-hidden="true">
              <svg viewBox="0 0 24 24" focusable="false">
                <rect x="4.5" y="5" width="15" height="14" rx="3" />
                <path d="M12 9.5v5M9.5 12h5" />
              </svg>
            </span>
            <h3>数据只在本机</h3>
            <p>程序存成设备上的本地文件，不需要账号，也没有「上传」这一步。</p>
          </article>
        </div>
      </div>
    </section>

    <section id="screens" class="section">
      <div class="container">
        <div v-reveal class="section-head">
          <p class="eyebrow">全貌</p>
          <h2>主页、程序、设置，各管一摊</h2>
          <p class="section-lede">
            主页管权限和悬浮球的开关，程序列表管每个流程，设置管外观和更新。
            下面是 App 里的三张真实界面。
          </p>
        </div>

        <div class="shot-grid">
          <figure v-reveal class="shot">
            <div class="phone phone-sm">
              <div class="phone-screen">
                <img
                  :src="shotSrc('home-clean')"
                @error="shotFallback"
                  width="1080"
                  height="2265"
                  alt="Click 的主页：无障碍服务与悬浮窗权限两行状态，各自标着已开启；下面是执行模式（点击 / 滑动）、执行前延时、重复次数、无限循环开关，最下面是启动悬浮球与停止悬浮球两个按钮。"
                />
              </div>
              <div class="phone-glow" aria-hidden="true"></div>
            </div>
            <figcaption>
              <strong>主页</strong>无障碍服务和悬浮窗权限各自带一行状态，没开时有按钮直达开关；
              执行模式选点击或滑动，执行前延时、重复次数、无限循环在这里设死，最后用「启动悬浮球」开工。
            </figcaption>
          </figure>
          <figure v-reveal class="shot">
            <div class="phone phone-sm">
              <div class="phone-screen">
                <img
                  :src="shotSrc('programs-clean')"
                @error="shotFallback"
                  width="1080"
                  height="2265"
                  alt="Click 的程序列表：三张程序卡片，每张写着节点数量和节点类型色点，右侧是编辑、复制、删除按钮，右下角是新建按钮。"
                />
              </div>
              <div class="phone-glow" aria-hidden="true"></div>
            </div>
            <figcaption>
              <strong>程序列表</strong>一个程序一张卡，节点数和类型色条一眼看清；
              右下角的加号新建，卡上的笔、叠、垃圾桶分别是编辑、复制、删除。
            </figcaption>
          </figure>

          <figure v-reveal class="shot">
            <div class="phone phone-sm">
              <div class="phone-screen">
                <img
                  :src="shotSrc('settings-clean')"
                @error="shotFallback"
                  width="1080"
                  height="2265"
                  alt="Click 的设置页：文字、个性化、更新三组设置，个性化里有配色方案、颜色、应用背景，更新里可以开关自动检查更新。"
                />
              </div>
              <div class="phone-glow" aria-hidden="true"></div>
            </div>
            <figcaption>
              <strong>设置</strong>文字大小、配色方案、应用背景、自动检查更新；
              主题跟系统走，也可以自己定。
            </figcaption>
          </figure>
        </div>
      </div>
    </section>

    <section id="graph" class="section section-canvas">
      <div ref="graphHost" class="container">
        <div v-reveal class="section-head">
          <p class="eyebrow">节点图</p>
          <h2>三类节点，够拼出大多数流程</h2>
          <p class="section-lede">
            事件是起点，动作和控制在后面接着走；节点上直接写参数，不用再点进二级页面。
          </p>
        </div>

        <GraphScene v-if="isWide && graph3d" :theme="theme" class="graph-scene-slot" />

        <div v-else class="flow-wrap" v-reveal>
          <ol class="flow" aria-label="一条示例流程">
            <li class="chip chip-event">
              <span class="chip-kind">事件</span>
              <span class="chip-name">开始</span>
            </li>
            <li class="flow-arrow" aria-hidden="true"></li>
            <li class="chip chip-action">
              <span class="chip-kind">动作</span>
              <span class="chip-name">点击 ×1</span>
            </li>
            <li class="flow-arrow" aria-hidden="true"></li>
            <li class="chip chip-action chip-delay">
              <span class="chip-kind">动作</span>
              <span class="chip-name">延时 1000 ms</span>
            </li>
            <li class="flow-arrow" aria-hidden="true"></li>
            <li class="chip chip-control">
              <span class="chip-kind">控制</span>
              <span class="chip-name">重复 3 次</span>
            </li>
          </ol>
        </div>

        <div class="grid grid-3">
          <button
            v-for="kind in KINDS"
            :key="kind.id"
            type="button"
            class="card card-kind"
            :class="{ 'is-open': openKind === kind.id }"
            :aria-expanded="openKind === kind.id"
            aria-controls="kind-stage"
            @click="toggleKind(kind.id)"
          >
            <h3><span class="dot" :class="kind.dot" aria-hidden="true"></span>{{ kind.label }}</h3>
            <p v-if="kind.id === 'event'">流程从哪里开始。一个程序里可以有几个起点，互不干扰。</p>
            <p v-else-if="kind.id === 'action'">真正干活的节点：点击、延时、输入，参数就写在卡片上。</p>
            <p v-else>条件、循环这些让流程拐弯的节点，和动作节点拼在一起用。</p>
            <span class="kind-more" aria-hidden="true">看长什么样 →</span>
          </button>
        </div>

        <div
          id="kind-stage"
          class="kind-stage"
          :class="{ 'is-open': !!activeKind }"
          :aria-hidden="!activeKind"
          role="region"
          aria-live="polite"
        >
          <div v-if="activeKind" :key="activeKind.id" class="kind-stage__inner">
            <img class="kind-node" :src="nodeSrc(activeKind.node)" :alt="activeKind.note" />
            <div class="kind-copy">
              <h3>{{ activeKind.title }}</h3>
              <p>{{ activeKind.body }}</p>
              <p class="kind-note">{{ activeKind.note }}</p>
            </div>
          </div>
        </div>
      </div>
    </section>

    <section id="start" class="section">
      <div class="container">
        <div class="start-layout">
          <div class="start-copy">
            <div v-reveal class="section-head">
              <p class="eyebrow">上手</p>
              <h2>三步跑起来第一个程序</h2>
              <p class="section-lede">从安装到跑完第一次，通常不超过五分钟。</p>
            </div>

            <ol class="steps steps-vertical">
              <li v-reveal class="step">
                <span class="step-no" aria-hidden="true">1</span>
                <div class="step-body">
                  <h3>新建程序</h3>
                  <p>在程序页点右下角的加号，起个名字，进入空白画布。</p>
                </div>
              </li>
              <li v-reveal class="step">
                <span class="step-no" aria-hidden="true">2</span>
                <div class="step-body">
                  <h3>摆节点</h3>
                  <p>点底部托盘里的积木添加节点，按住拖到想放的位置，再从圆点连线。</p>
                </div>
              </li>
              <li v-reveal class="step">
                <span class="step-no" aria-hidden="true">3</span>
                <div class="step-body">
                  <h3>播放</h3>
                  <p>点右上角的播放键，程序按连线顺序跑；想改随时停下。</p>
                </div>
              </li>
            </ol>
          </div>

        </div>
      </div>
    </section>

    <section id="download" class="section section-download">
      <div class="container">
        <div v-reveal class="download">
          <div class="download-copy">
            <h2>下载 Click</h2>
            <p>Android 8.0 及以上 · APK 约 4 MB · 不需要 Google 服务，也不用联网。</p>
          </div>
          <div class="download-actions">
            <a class="btn btn-primary btn-lg" :href="RELEASES_URL" rel="noopener">
              前往 Releases
              <svg viewBox="0 0 24 24" aria-hidden="true" focusable="false" class="btn-icon">
                <path d="M7 17 17 7M9 7h8v8" />
              </svg>
            </a>
            <a class="link-plain" :href="REPO_URL" rel="noopener">查看源码</a>
          </div>
        </div>
      </div>
    </section>
  </main>

  <footer class="site-footer">
    <div class="container footer-inner">
      <div class="footer-brand">
        <span class="brand-mark brand-mark-sm" aria-hidden="true">
          <img class="brand-logo brand-logo--light" src="/brand/logo-light.png" alt="" width="557" height="768" />
          <img class="brand-logo brand-logo--dark" src="/brand/logo-dark.png" alt="" width="557" height="768" />
        </span>
        <span>Click · Android 可视化自动化</span>
      </div>
      <nav class="footer-nav" aria-label="页脚导航">
        <a href="#top">回到顶部</a>
        <a :href="REPO_URL" rel="noopener">GitHub</a>
      </nav>
      <p class="footer-copy">© {{ year }} Click</p>
    </div>
  </footer>
</template>

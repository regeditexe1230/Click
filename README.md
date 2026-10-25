# Click 官网

Click 是 Android 上的可视化自动化工具：把「事件 / 动作 / 控制」节点拖到画布上连成流程，点播放就按顺序执行。
这个分支只放官网源码——单页应用、可静态部署，深浅两套主题，首屏和节点图区块各有一处 three.js 动效。

**分支说明**：`click-web` 与 App 源码（`main` / `beta`）是同一仓库里的两条互不相干的线，本分支只含网页资源，App 代码不在这里。

## 技术栈

| 部分 | 选型 |
| --- | --- |
| 框架 | Vue 3（`<script setup>` + SFC），无路由、无状态管理 |
| 构建 | Vite 5，产物为纯静态 `dist/` |
| 3D | three.js（动态 import；手机宽度 < 720px 不加载） |
| 样式 | 原生 CSS，设计令牌集中在 `src/styles/tokens.css` |
| 资源 | 图标内联 SVG，字体用系统字体栈 |

## 本地运行

```bash
npm install
npm run dev      # 开发服务器
npm run build    # 产出 dist/
npm run preview  # 预览构建结果
```

部署到 GitHub Pages 等子路径时用 `GITHUB_PAGES=1 npm run build`（`vite.config.js` 会据此设置 `base`）。

## 目录

```
index.html          入口
src/App.vue         页面主体（首屏 / 功能 / 节点图 / 上手 / 下载）
src/components/     NodeField、GraphScene、IntroLogo
src/composables/    滚动出现
src/three/          3D 配色
public/brand/       logo、favicon（深浅两套）
public/screenshots/ App 真机截图（深浅各一套），nodes/ 下是抠好的透明节点卡
```

## 图片规格

| 文件 | 尺寸 | 用途 |
| --- | --- | --- |
| `screenshots/*-clean.png` / `*-clean-dark.png` | 1080 × 2203 | 手机框内使用；已裁掉系统状态栏与导航键，保留 App 自身底栏 |
| `screenshots/nodes/*.png` | 369–382 × 229–236 | 透明底节点卡，`max-width:100%` 直接引用 |

节点卡按主题取 `-light` / `-dark`，两者不可互换；截图与手机框同比例，用 `object-fit: contain`，避免二次裁切把底栏切掉。

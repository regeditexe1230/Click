import { defineConfig } from "vite";
import vue from "@vitejs/plugin-vue";

// 部署到 GitHub Pages 时用仓库名做 base（`/Click/`）；Cloudflare Pages 根路径部署保持默认。
// 用法：GITHUB_PAGES=1 npm run build
const base = process.env.GITHUB_PAGES ? "/Click/" : "/";

export default defineConfig({
  base,
  plugins: [vue()],
  build: {
    target: "es2020",
    assetsInlineLimit: 2048,
    rollupOptions: {
      output: {
        manualChunks: {
          // three.js 单独切块，首屏不下载它，滑到节点图区块再加载
          three: ["three"],
        },
      },
    },
  },
});

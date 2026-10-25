import { createApp } from "vue";
import App from "./App.vue";
import reveal from "./composables/reveal";
import "./styles/tokens.css";
import "./styles/style.css";

// 滚动出现只在有 JS 时启用（.js .reveal），脚本没跑起来时内容照常可见
document.documentElement.classList.add("js");

createApp(App).directive("reveal", reveal).mount("#app");

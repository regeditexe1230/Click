<script setup>
// 节点图区块的三维演示：四张节点卡片悬在空间里，用管道连成一条流程。
// 可以拖动旋转；静止时轻微自转。标签用 canvas 贴图，中文不会糊。
import { onBeforeUnmount, onMounted, ref, watch } from "vue";
import * as THREE from "three";
import { RoundedBoxGeometry } from "three/examples/jsm/geometries/RoundedBoxGeometry.js";
import { clampDpr, palette, prefersReducedMotion } from "../three/palette";

const props = defineProps({ theme: { type: String, default: "light" } });
const host = ref(null);

const NODES = [
  { kind: "事件", name: "开始", accent: "--primary", pos: [-5.2, 1.0, 0.5] },
  { kind: "动作", name: "点击 ×1", accent: "--seed-blue", pos: [-1.7, -0.55, 0] },
  { kind: "动作", name: "延时 1000 ms", accent: "--seed-yellow", pos: [1.8, 0.8, -0.35] },
  { kind: "控制", name: "重复 3 次", accent: "--seed-green", pos: [5.25, -0.8, 0.25] },
];

const W = 3.15;
const H = 1.66;
const D = 0.26;
const FONT_STACK =
  "-apple-system, BlinkMacSystemFont, 'PingFang SC', 'Microsoft YaHei', sans-serif";

let renderer = null;
let scene = null;
let camera = null;
let group = null;
let edgeMat = null;
let tubeMat = null;
let raf = 0;
let running = false;
let ro = null;
let io = null;
let visibilityHandler = null;
let clock = null;

const disposables = [];
const cards = []; // { mesh, canvas, texture, node, index }
const BASE_YAW = 0.17;
const BASE_PITCH = -0.1;
const view = { yaw: BASE_YAW, pitch: BASE_PITCH, tYaw: BASE_YAW, tPitch: BASE_PITCH };
const drag = { active: false, x: 0, y: 0 };

function tokenVar(name, fallback) {
  const v = getComputedStyle(document.documentElement).getPropertyValue(name);
  return (v || fallback).trim();
}

function roundRect(ctx, x, y, w, h, r) {
  ctx.beginPath();
  ctx.moveTo(x + r, y);
  ctx.arcTo(x + w, y, x + w, y + h, r);
  ctx.arcTo(x + w, y + h, x, y + h, r);
  ctx.arcTo(x, y + h, x, y, r);
  ctx.arcTo(x, y, x + w, y, r);
  ctx.closePath();
}

// 把一张节点卡片画到 canvas 上，再当贴图用（换主题时重画一次即可）
function drawCard(canvas, node, index) {
  const p = palette();
  const cw = canvas.width;
  const ch = canvas.height;
  const ctx = canvas.getContext("2d");
  ctx.clearRect(0, 0, cw, ch);

  // 底色铺满整张画布：只画圆角矩形的话，四个角是透明的，
  // 3D 盒子那层从透明角落透出来，浅色模式下就是四块发黑的角。
  ctx.fillStyle = p.surfaceContainerHigh;
  ctx.fillRect(0, 0, cw, ch);
  roundRect(ctx, 0, 0, cw, ch, 34);
  ctx.fill();

  ctx.fillStyle = tokenVar(node.accent, p.primary);
  roundRect(ctx, 16, 18, 14, ch - 36, 7);
  ctx.fill();

  ctx.textAlign = "left";
  ctx.textBaseline = "alphabetic";
  ctx.fillStyle = p.onSurfaceVariant;
  ctx.font = `500 30px ${FONT_STACK}`;
  ctx.fillText(node.kind, 52, 86);

  ctx.fillStyle = p.onSurface;
  ctx.font = `600 46px ${FONT_STACK}`;
  ctx.fillText(node.name, 52, 176);

  ctx.fillStyle = p.outline;
  ctx.font = `400 26px ${FONT_STACK}`;
  ctx.textAlign = "right";
  ctx.fillText(`第 ${index} 步`, cw - 36, 86);
}

function build() {
  const p = palette();
  scene = new THREE.Scene();
  group = new THREE.Group();
  scene.add(group);

  const geo = new RoundedBoxGeometry(W, H, D, 4, 0.16);
  disposables.push(geo);
  edgeMat = new THREE.MeshStandardMaterial({
    color: new THREE.Color(p.surfaceContainerHigh),
    roughness: 0.85,
    metalness: 0,
  });
  disposables.push(edgeMat);

  const anchors = [];
  NODES.forEach((node, index) => {
    const canvas = document.createElement("canvas");
    canvas.width = 512;
    canvas.height = 268;
    drawCard(canvas, node, index);

    const texture = new THREE.CanvasTexture(canvas);
    texture.colorSpace = THREE.SRGBColorSpace;
    texture.anisotropy = 4;
    const faceMat = new THREE.MeshStandardMaterial({ map: texture, roughness: 0.9, metalness: 0 });
    disposables.push(texture, faceMat);

    // BoxGeometry 材质顺序：+x, -x, +y, -y, +z, -z —— 只有正面（+z）贴标签
    const mesh = new THREE.Mesh(geo, [edgeMat, edgeMat, edgeMat, edgeMat, faceMat, edgeMat]);
    mesh.position.set(...node.pos);
    mesh.rotation.y = index % 2 === 0 ? 0.07 : -0.07;
    group.add(mesh);

    cards.push({ mesh, canvas, texture, node, index });
    anchors.push(mesh.position.clone());
  });

  tubeMat = new THREE.MeshStandardMaterial({
    color: new THREE.Color(p.primary),
    roughness: 0.4,
    metalness: 0.1,
    transparent: true,
    opacity: 0.5,
  });
  disposables.push(tubeMat);

  for (let i = 0; i < anchors.length - 1; i += 1) {
    const start = anchors[i].clone().add(new THREE.Vector3(W / 2 + 0.04, 0, 0));
    const end = anchors[i + 1].clone().add(new THREE.Vector3(-W / 2 - 0.04, 0, 0));
    const mid = start.clone().add(end).multiplyScalar(0.5);
    mid.y += 0.42;
    mid.z += 0.2;
    const curve = new THREE.CatmullRomCurve3([start, mid, end]);
    const tube = new THREE.TubeGeometry(curve, 28, 0.032, 8, false);
    disposables.push(tube);
    group.add(new THREE.Mesh(tube, tubeMat));
  }

  scene.add(new THREE.AmbientLight(0xffffff, 2.5));
  const key = new THREE.DirectionalLight(0xffffff, 1.7);
  key.position.set(4, 7, 9);
  scene.add(key);
  const fill = new THREE.DirectionalLight(new THREE.Color(p.primaryContainer), 1.2);
  fill.position.set(-7, -4, 6);
  scene.add(fill);

  camera = new THREE.PerspectiveCamera(40, 1, 0.1, 80);
  camera.position.set(0, 0, 14);
  camera.lookAt(0, 0, 0);

  renderer = new THREE.WebGLRenderer({ antialias: true, alpha: true, powerPreference: "low-power" });
  renderer.setPixelRatio(clampDpr(2));
  renderer.setClearAlpha(0);
  host.value.appendChild(renderer.domElement);

  bindPointer();
  resize();
}

function fit() {
  if (!renderer || !host.value || !group) return;
  const w = host.value.clientWidth;
  const h = host.value.clientHeight;
  if (!w || !h) return;
  renderer.setSize(w, h, false);
  camera.aspect = w / h;
  camera.updateProjectionMatrix();

  const visibleH = 2 * camera.position.z * Math.tan((camera.fov * Math.PI) / 360);
  const visibleW = visibleH * camera.aspect;

  // 量出整条链的真实包围盒（先把旋转归零，量的是正面投影），
  // 再按宽高分别缩放并居中，保证任何自转角度下都不出框。
  const savedRot = group.rotation.clone();
  group.rotation.set(0, 0, 0);
  group.scale.setScalar(1);
  group.position.set(0, 0, 0);
  group.updateMatrixWorld(true);

  const box = new THREE.Box3().setFromObject(group);
  const size = box.getSize(new THREE.Vector3());
  const center = box.getCenter(new THREE.Vector3());

  // 0.78 / 0.74 的系数是给拖动旋转留的余量（±18° 时投影会变大）
  const scale = Math.min(
    2.2,
    (visibleW * 0.78) / Math.max(size.x, 0.001),
    (visibleH * 0.74) / Math.max(size.y, 0.001)
  );
  const s = Math.max(0.35, scale);
  group.scale.setScalar(s);
  group.position.set(-center.x * s, -center.y * s - 0.3, -center.z * s);
  group.rotation.copy(savedRot);
  group.updateMatrixWorld(true);

  if (!running) renderOnce();
}

function resize() {
  fit();
}

function renderOnce() {
  if (renderer && scene && camera) renderer.render(scene, camera);
}

function tick() {
  if (!running) return;
  raf = requestAnimationFrame(tick);
  const dt = Math.min(clock.getDelta(), 0.05);
  const t = clock.elapsedTime;

  if (!drag.active) {
    view.tYaw = BASE_YAW + Math.sin(t * 0.28) * 0.15;
    view.tPitch = BASE_PITCH + Math.sin(t * 0.21) * 0.05;
  }
  const k = Math.min(1, dt * 7);
  view.yaw += (view.tYaw - view.yaw) * k;
  view.pitch += (view.tPitch - view.pitch) * k;
  group.rotation.y = view.yaw;
  group.rotation.x = view.pitch;
  renderer.render(scene, camera);
}

function start() {
  if (running || !renderer) return;
  if (prefersReducedMotion()) {
    renderOnce();
    return;
  }
  running = true;
  clock = clock || new THREE.Clock();
  clock.getDelta();
  tick();
}

function stop() {
  running = false;
  cancelAnimationFrame(raf);
}

function onPointerDown(e) {
  drag.active = true;
  drag.x = e.clientX;
  drag.y = e.clientY;
  if (!running) start();
  if (renderer && renderer.domElement.setPointerCapture) {
    try {
      renderer.domElement.setPointerCapture(e.pointerId);
    } catch (err) {
      /* 某些浏览器在合成事件上会拒绝，忽略即可 */
    }
  }
}

function onPointerMove(e) {
  if (!drag.active) return;
  const dx = e.clientX - drag.x;
  const dy = e.clientY - drag.y;
  drag.x = e.clientX;
  drag.y = e.clientY;
  view.tYaw = Math.max(BASE_YAW - 0.32, Math.min(BASE_YAW + 0.32, view.tYaw + dx * 0.006));
  view.tPitch = Math.max(-0.3, Math.min(0.14, view.tPitch + dy * 0.005));
  view.yaw = view.tYaw;
  view.pitch = view.tPitch;
  if (group) {
    group.rotation.y = view.yaw;
    group.rotation.x = view.pitch;
  }
  renderOnce();
}

function onPointerUp() {
  drag.active = false;
  if (prefersReducedMotion()) renderOnce();
}

const pointerHandlers = {
  pointerdown: onPointerDown,
  pointermove: onPointerMove,
  pointerup: onPointerUp,
  pointercancel: onPointerUp,
  pointerleave: onPointerUp,
};

function bindPointer() {
  const el = renderer.domElement;
  Object.entries(pointerHandlers).forEach(([type, fn]) => el.addEventListener(type, fn));
}

function unbindPointer() {
  if (!renderer) return;
  const el = renderer.domElement;
  Object.entries(pointerHandlers).forEach(([type, fn]) => el.removeEventListener(type, fn));
}

// 主题切换：重画标签、换卡片侧面与管道的颜色
function applyTheme() {
  if (!scene) return;
  const p = palette();
  if (edgeMat) edgeMat.color.set(p.surfaceContainerHigh);
  if (tubeMat) tubeMat.color.set(p.primary);
  cards.forEach(({ canvas, texture, node, index }) => {
    drawCard(canvas, node, index);
    texture.needsUpdate = true;
  });
  scene.children.forEach((child) => {
    if (child.isDirectionalLight && child.position.x < 0) child.color.set(p.primaryContainer);
  });
  renderOnce();
}

onMounted(() => {
  if (!host.value) return;
  build();
  if (!renderer) return;
  renderOnce();

  io = new IntersectionObserver(
    (entries) => {
      if (entries.some((e) => e.isIntersecting) && !document.hidden) start();
      else stop();
    },
    { threshold: 0.05 }
  );
  io.observe(host.value);

  visibilityHandler = () => (document.hidden ? stop() : start());
  document.addEventListener("visibilitychange", visibilityHandler);

  ro = new ResizeObserver(() => resize());
  ro.observe(host.value);
});

watch(
  () => props.theme,
  () => applyTheme()
);

onBeforeUnmount(() => {
  stop();
  if (io) io.disconnect();
  if (ro) ro.disconnect();
  if (visibilityHandler) document.removeEventListener("visibilitychange", visibilityHandler);
  unbindPointer();
  disposables.forEach((d) => d.dispose && d.dispose());
  if (renderer) {
    renderer.dispose();
    renderer.forceContextLoss();
    if (renderer.domElement.parentNode) renderer.domElement.parentNode.removeChild(renderer.domElement);
  }
  renderer = null;
  scene = null;
  camera = null;
  group = null;
});
</script>

<template>
  <div
    ref="host"
    class="graph-scene"
    role="img"
    aria-label="三维节点图示意：开始 → 点击 ×1 → 延时 1000 毫秒 → 重复 3 次"
  >
    <span class="graph-scene-hint" aria-hidden="true">拖动转一转</span>
  </div>
</template>

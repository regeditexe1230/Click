<script setup>
// 首屏背景：一层缓慢漂移的「节点星图」。纯装饰，aria-hidden，
// 鼠标会有轻微视差；页面不可见或滚出视口就停（不烧 GPU）。
import { onBeforeUnmount, onMounted, ref, watch } from "vue";
import * as THREE from "three";
import { clampDpr, palette, prefersReducedMotion } from "../three/palette";

const props = defineProps({ theme: { type: String, default: "light" } });
const host = ref(null);

const NODES = 44;
let renderer = null;
let scene = null;
let camera = null;
let group = null;
let raf = 0;
let running = false;
let ro = null;
let io = null;
let clock = null;
const pointer = { x: 0, y: 0, tx: 0, ty: 0 };
const disposables = [];

const rand = (a, b) => a + Math.random() * (b - a);

function build() {
  const p = palette();
  scene = new THREE.Scene();
  scene.fog = new THREE.Fog(new THREE.Color(p.surface), 10, 26);

  camera = new THREE.PerspectiveCamera(52, 1, 0.1, 60);
  camera.position.set(0, 0.4, 14.5);
  camera.lookAt(0, 0, 0);

  group = new THREE.Group();
  group.rotation.set(-0.42, 0.24, 0);
  group.position.x = 1.6;
  scene.add(group);

  const points = [];
  const nodeGeo = new THREE.BoxGeometry(1, 1, 1);
  disposables.push(nodeGeo);

  for (let i = 0; i < NODES; i += 1) {
    const size = rand(0.14, 0.4);
    const shade = Math.random();
    const color =
      shade > 0.62 ? p.primary : shade > 0.3 ? p.secondaryContainer : p.outlineVariant;
    const mat = new THREE.MeshStandardMaterial({
      color: new THREE.Color(color),
      roughness: 0.62,
      metalness: 0.04,
      transparent: true,
      opacity: shade > 0.3 ? 0.76 : 0.4,
    });
    disposables.push(mat);
    const mesh = new THREE.Mesh(nodeGeo, mat);
    mesh.scale.setScalar(size);
    mesh.position.set(rand(-6.5, 12.5), rand(-4.4, 4.6), rand(-6, 2));
    mesh.rotation.set(rand(0, Math.PI), rand(0, Math.PI), rand(0, Math.PI));
    group.add(mesh);
    points.push(mesh.position.clone());
  }

  // 连线：每个点连到最近的另一个点，做出「图」的感觉
  const linePositions = [];
  for (let i = 0; i < points.length; i += 1) {
    let best = -1;
    let bestD = Infinity;
    for (let j = 0; j < points.length; j += 1) {
      if (i === j) continue;
      const d = points[i].distanceToSquared(points[j]);
      if (d < bestD) {
        bestD = d;
        best = j;
      }
    }
    if (best >= 0 && bestD < 30) {
      const a = points[i];
      const b = points[best];
      linePositions.push(a.x, a.y, a.z, b.x, b.y, b.z);
    }
  }
  const lineGeo = new THREE.BufferGeometry();
  lineGeo.setAttribute("position", new THREE.Float32BufferAttribute(linePositions, 3));
  const lineMat = new THREE.LineBasicMaterial({
    color: new THREE.Color(p.primary),
    transparent: true,
    opacity: 0.22,
  });
  disposables.push(lineGeo, lineMat);
  group.add(new THREE.LineSegments(lineGeo, lineMat));

  scene.add(new THREE.AmbientLight(0xffffff, 2.1));
  const key = new THREE.DirectionalLight(0xffffff, 1.5);
  key.position.set(3, 6, 8);
  scene.add(key);
  const rim = new THREE.DirectionalLight(new THREE.Color(p.primaryContainer), 1.1);
  rim.position.set(-6, -3, -6);
  scene.add(rim);

  renderer = new THREE.WebGLRenderer({ antialias: true, alpha: true, powerPreference: "low-power" });
  renderer.setPixelRatio(clampDpr(1.75));
  renderer.setClearAlpha(0);
  host.value.appendChild(renderer.domElement);
  resize();
}

function resize() {
  if (!renderer || !host.value) return;
  const w = host.value.clientWidth;
  const h = host.value.clientHeight;
  if (!w || !h) return;
  renderer.setSize(w, h, false);
  camera.aspect = w / h;
  camera.updateProjectionMatrix();
  if (!running) renderOnce();
}

function renderOnce() {
  if (renderer && scene && camera) renderer.render(scene, camera);
}

function tick() {
  if (!running) return;
  raf = requestAnimationFrame(tick);
  const dt = Math.min(clock.getDelta(), 0.05);
  pointer.x += (pointer.tx - pointer.x) * 0.055;
  pointer.y += (pointer.ty - pointer.y) * 0.055;
  group.rotation.y += dt * 0.055;
  group.rotation.x = -0.42 + pointer.y * 0.1;
  group.rotation.z = pointer.x * 0.045;
  group.position.x = 1.6 + pointer.x * 0.6;
  renderer.render(scene, camera);
}

function start() {
  if (running || prefersReducedMotion()) return;
  running = true;
  clock = clock || new THREE.Clock();
  clock.getDelta();
  tick();
}

function stop() {
  running = false;
  cancelAnimationFrame(raf);
}

function applyTheme() {
  if (!scene) return;
  const p = palette();
  scene.fog.color = new THREE.Color(p.surface);
  scene.children.forEach((child) => {
    if (child.isLineSegments && child.material) child.material.color.set(p.primary);
    if (child.isDirectionalLight) child.color.set(child.position.x < 0 ? p.primaryContainer : "#ffffff");
  });
  group.children.forEach((child) => {
    if (!child.isMesh || !child.material) return;
    const shade = child.material.opacity;
    child.material.color.set(shade > 0.9 ? p.primary : p.outlineVariant);
  });
  renderOnce();
}

function onPointerMove(event) {
  pointer.tx = (event.clientX / window.innerWidth) * 2 - 1;
  pointer.ty = (event.clientY / window.innerHeight) * 2 - 1;
}

let visibilityHandler = null;

onMounted(() => {
  build();
  if (!renderer) return;
  renderOnce();
  io = new IntersectionObserver(
    (entries) => {
      const visible = entries.some((e) => e.isIntersecting);
      if (visible && !document.hidden) start();
      else stop();
    },
    { threshold: 0.02 }
  );
  io.observe(host.value);

  visibilityHandler = () => (document.hidden ? stop() : start());
  document.addEventListener("visibilitychange", visibilityHandler);
  window.addEventListener("pointermove", onPointerMove, { passive: true });

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
  window.removeEventListener("pointermove", onPointerMove);
  disposables.forEach((d) => d.dispose && d.dispose());
  if (renderer) {
    renderer.dispose();
    renderer.forceContextLoss();
    if (renderer.domElement.parentNode) renderer.domElement.parentNode.removeChild(renderer.domElement);
  }
  renderer = null;
  scene = null;
});
</script>

<template>
  <div ref="host" class="node-field" aria-hidden="true"></div>
</template>

// Cloudflare Pages Function：按访客 IP 的国家/地区给出站点语言。
// 前端只负责读结果，不自己猜 IP。
const LANG_BY_COUNTRY = {
  CN: "zh-CN",
  TW: "zh-TW",
  HK: "zh-TW",
  MO: "zh-TW",
  JP: "ja",
  KR: "ko",
};

export async function onRequestGet({ request, env }) {
  const headerCountry = request.headers.get("cf-ipcountry") || "";
  let country = (request.cf && request.cf.country) || headerCountry || "";

  // 预览环境专用测试入口：只在 env.GEO_TEST === "1" 时生效。
  // 生产环境不配这个变量，?country=xx 一律忽略，避免访客篡改自己的默认语言。
  const url = new URL(request.url);
  const override = (url.searchParams.get("country") || "").toUpperCase();
  const testEnabled = env && env.GEO_TEST === "1" && /^[A-Z]{2}$/.test(override);
  if (testEnabled) country = override;

  // 取不到国家就给 null：前端必须跳过 geo 这一档、继续用 navigator.language，
  // 否则会把 ja / ko 用户顶成英文。
  const lang = country ? LANG_BY_COUNTRY[country] || "en" : null;

  return new Response(JSON.stringify({ country, lang }), {
    headers: {
      "content-type": "application/json; charset=utf-8",
      "cache-control": "no-store",
    },
  });
}

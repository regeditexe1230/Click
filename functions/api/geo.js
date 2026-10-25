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

export async function onRequestGet({ request }) {
  const country = (request.cf && request.cf.country) || "";
  const lang = LANG_BY_COUNTRY[country] || "en";

  return new Response(JSON.stringify({ country, lang }), {
    headers: {
      "content-type": "application/json; charset=utf-8",
      "cache-control": "no-store",
    },
  });
}

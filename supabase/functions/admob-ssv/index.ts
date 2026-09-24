import { createClient } from "jsr:@supabase/supabase-js@2";

// AdMob 보상형 광고 서버 측 확인(SSV) 콜백.
// 광고를 끝까지 본 뒤 구글이 이 주소로 GET 을 보내고, 서명을 구글 공개키로 확인한 다음에만 코인을 적립한다.
// 앱(클라이언트)은 절대 코인을 직접 주지 않는다 — 적립은 credit_ad_reward(거래 id 멱등·하루 상한) 하나로만.

const SUPABASE_URL = Deno.env.get("SUPABASE_URL")!;
const SERVICE_ROLE = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!;
const supabase = createClient(SUPABASE_URL, SERVICE_ROLE, {
  auth: { persistSession: false },
});

const KEYS_URL = "https://www.gstatic.com/admob/reward/verifier-keys.json";
const UUID_RE =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

const json = (b: unknown, status = 200) =>
  new Response(JSON.stringify(b), {
    status,
    headers: { "Content-Type": "application/json" },
  });

function b64ToBytes(s: string): Uint8Array {
  s = s.replace(/-/g, "+").replace(/_/g, "/");
  while (s.length % 4) s += "=";
  const bin = atob(s);
  const out = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
  return out;
}

// DER(SEQUENCE{INTEGER r, INTEGER s}) → WebCrypto 가 받는 r||s (각 32바이트)
export function derToP1363(der: Uint8Array): Uint8Array {
  let p = 0;
  if (der[p++] !== 0x30) throw new Error("bad der");
  let len = der[p++];
  if (len & 0x80) p += len & 0x7f;
  const readInt = () => {
    if (der[p++] !== 0x02) throw new Error("bad der int");
    const l = der[p++];
    let v = der.slice(p, p + l);
    p += l;
    while (v.length > 32 && v[0] === 0) v = v.slice(1);
    if (v.length > 32) throw new Error("bad der len");
    const o = new Uint8Array(32);
    o.set(v, 32 - v.length);
    return o;
  };
  const r = readInt(), s = readInt();
  const out = new Uint8Array(64);
  out.set(r, 0);
  out.set(s, 32);
  return out;
}

// 구글 공개키 (keyId → CryptoKey), 12시간 캐시
let _keys: Map<string, CryptoKey> | null = null;
let _keysAt = 0;
async function getKey(keyId: string, force = false): Promise<CryptoKey | null> {
  if (force || !_keys || Date.now() - _keysAt > 12 * 3600_000) {
    const res = await fetch(KEYS_URL);
    if (!res.ok) throw new Error("keys fetch " + res.status);
    const body = await res.json();
    const m = new Map<string, CryptoKey>();
    for (const k of body.keys || []) {
      const key = await crypto.subtle.importKey(
        "spki",
        b64ToBytes(k.base64),
        { name: "ECDSA", namedCurve: "P-256" },
        false,
        ["verify"],
      );
      m.set(String(k.keyId), key);
    }
    _keys = m;
    _keysAt = Date.now();
  }
  return _keys.get(keyId) || null;
}

async function verify(rawQuery: string): Promise<boolean> {
  const at = rawQuery.indexOf("&signature=");
  if (at < 0) return false;
  const message = rawQuery.slice(0, at);
  const params = new URLSearchParams(rawQuery);
  const sig = params.get("signature") || "";
  const keyId = params.get("key_id") || "";
  if (!sig || !keyId) return false;
  let key = await getKey(keyId);
  // 키 교체 직후 — 모르는 key_id 로 매번 새로 받지 않게 1분에 한 번만
  if (!key && Date.now() - _keysAt > 60_000) key = await getKey(keyId, true);
  if (!key) return false;
  return crypto.subtle.verify(
    { name: "ECDSA", hash: "SHA-256" },
    key,
    derToP1363(b64ToBytes(sig)),
    new TextEncoder().encode(message),
  );
}

Deno.serve(async (req) => {
  if (req.method !== "GET") return new Response("Method Not Allowed", { status: 405 });
  const url = new URL(req.url);
  const raw = url.search.startsWith("?") ? url.search.slice(1) : url.search;

  let ok = false;
  try {
    ok = await verify(raw);
  } catch (e) {
    console.error("ssv verify error:", e);
    return json({ error: "verify failed" }, 500); // 구글이 다시 보냄
  }
  if (!ok) return json({ error: "bad signature" }, 403);

  const p = url.searchParams;
  const userId = p.get("user_id") || "";
  const txn = p.get("transaction_id") || "";
  const adUnit = p.get("ad_unit") || "";
  // AdMob 콘솔 'URL 확인' 테스트 요청 등 — 사용자 없으면 적립 없이 200
  if (!UUID_RE.test(userId) || !txn) return json({ ok: true, note: "no user" });

  try {
    // 우리 보상형 광고 단위에서 온 것만 (설정돼 있을 때)
    const { data: cfg } = await supabase
      .from("app_config")
      .select("key,value")
      .in("key", ["admob_rewarded_ios", "admob_rewarded_android"]);
    const units = (cfg || [])
      .map((r: any) => String(r.value || "").split("/").pop())
      .filter(Boolean);
    if (units.length && !units.includes(adUnit)) {
      return json({ ok: true, note: "ad unit ignored" });
    }
    const { data, error } = await supabase.rpc("credit_ad_reward", {
      p_user: userId,
      p_ref: "admob:" + txn,
      p_note: "ad_unit " + adUnit,
    });
    if (error) throw error;
    return json({ ok: true, coin: data });
  } catch (e) {
    console.error("ssv credit error:", e);
    return json({ error: String((e as any)?.message || e) }, 500);
  }
});

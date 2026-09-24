import { createClient } from "jsr:@supabase/supabase-js@2";

// RevenueCat webhook Authorization 헤더 값 — Edge Function 시크릿에서만 읽는다(코드에 두지 않음).
// 비어 있으면 모든 요청을 거절한다(500) — 시크릿을 먼저 등록한 뒤 배포할 것.
const WEBHOOK_SECRET = Deno.env.get("REVENUECAT_WEBHOOK_SECRET") || "";

const SUPABASE_URL = Deno.env.get("SUPABASE_URL")!;
const SERVICE_ROLE = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!;
const supabase = createClient(SUPABASE_URL, SERVICE_ROLE, {
  auth: { persistSession: false },
});

const UUID_RE =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

// 프리미엄을 즉시 해제해야 하는 이벤트 (구독 종료)
const EXPIRE_TYPES = new Set(["EXPIRATION"]);
// 활성 상태로 반영하는 이벤트 (구매/갱신/재구독/상품변경/유예복구 등)
const ACTIVE_TYPES = new Set([
  "INITIAL_PURCHASE",
  "RENEWAL",
  "UNCANCELLATION",
  "PRODUCT_CHANGE",
  "NON_RENEWING_PURCHASE",
  "SUBSCRIPTION_EXTENDED",
]);
// 코인(소모성 상품) — 적립/환불 이벤트
const COIN_CREDIT_TYPES = new Set(["NON_RENEWING_PURCHASE", "INITIAL_PURCHASE"]);
const COIN_REFUND_TYPES = new Set(["CANCELLATION", "REFUND"]);

const json = (b: unknown, status = 200) =>
  new Response(JSON.stringify(b), {
    status,
    headers: { "Content-Type": "application/json" },
  });

// coin_packs 에 있는 상품이면 코인 상품 (5분 캐시)
let _packs: Set<string> | null = null;
let _packsAt = 0;
async function isCoinProduct(pid: string | null): Promise<boolean> {
  if (!pid) return false;
  if (!_packs || Date.now() - _packsAt > 300_000) {
    const { data, error } = await supabase.from("coin_packs").select("product_id");
    if (error) throw error;
    _packs = new Set((data || []).map((r: any) => r.product_id));
    _packsAt = Date.now();
  }
  // Play 상품 id 는 'coins_100:base' 처럼 뒤에 붙을 수 있다
  return _packs.has(pid) || _packs.has(pid.split(":")[0]);
}

Deno.serve(async (req) => {
  if (req.method !== "POST") {
    return new Response("Method Not Allowed", { status: 405 });
  }
  if (!WEBHOOK_SECRET) {
    console.error("REVENUECAT_WEBHOOK_SECRET 미설정");
    return json({ error: "not configured" }, 500);
  }

  // 1) 인증: RevenueCat 대시보드에 등록한 Authorization 헤더 값과 일치해야 함
  const auth = req.headers.get("Authorization") || "";
  if (auth !== WEBHOOK_SECRET) return json({ error: "unauthorized" }, 401);

  let body: any;
  try {
    body = await req.json();
  } catch {
    return json({ error: "bad json" }, 400);
  }

  const ev = body?.event;
  if (!ev || !ev.type) return json({ ok: true, note: "no event" });

  const type: string = ev.type;

  // 테스트 이벤트는 그냥 200 (RevenueCat 'Send test' 버튼용)
  if (type === "TEST") return json({ ok: true, note: "test received" });

  const appUserId: string = ev.app_user_id || "";
  // app_user_id 가 supabase user_id(UUID)가 아니면 매핑 불가 → 무시(200)
  if (!UUID_RE.test(appUserId)) {
    return json({ ok: true, note: "non-uuid app_user_id ignored" });
  }

  const productId = ev.product_id || null;
  const origTxn = ev.original_transaction_id || ev.transaction_id || null;
  const storeTxn = ev.transaction_id || null;
  const store = ev.store || null;
  const environment = ev.environment || null;

  // 2) 코인 상품 — subscriptions 에는 절대 쓰지 않고 coin_ledger 에만 (거래 id 로 멱등)
  try {
    if (await isCoinProduct(productId)) {
      const ref = storeTxn || origTxn;
      if (!ref) return json({ ok: true, note: "coin event without transaction id" });
      const pid = String(productId).split(":")[0];
      if (COIN_CREDIT_TYPES.has(type)) {
        const { data, error } = await supabase.rpc("credit_coin_purchase", {
          p_user: appUserId,
          p_product: pid,
          p_ref: String(ref),
          p_note: [store, environment].filter(Boolean).join(" ") || null,
        });
        if (error) throw error;
        return json({ ok: true, type, coin: data });
      }
      if (COIN_REFUND_TYPES.has(type)) {
        const { data, error } = await supabase.rpc("refund_coin_purchase", {
          p_user: appUserId,
          p_ref: String(ref),
          p_note: ev.cancel_reason || null,
        });
        if (error) throw error;
        return json({ ok: true, type, coin: data });
      }
      return json({ ok: true, note: "coin event ignored", type });
    }
  } catch (e) {
    console.error("coin write error:", e);
    return json({ error: String((e as any)?.message || e) }, 500);
  }

  // 3) 구독
  const periodType = ev.period_type || null;
  const eventId = ev.id || null;
  const expiresAt = ev.expiration_at_ms
    ? new Date(ev.expiration_at_ms).toISOString()
    : null;
  const startedAt = ev.purchased_at_ms
    ? new Date(ev.purchased_at_ms).toISOString()
    : new Date().toISOString();
  const nowIso = new Date().toISOString();

  // status 결정
  let status = "active";
  if (EXPIRE_TYPES.has(type)) status = "expired";
  else if (ACTIVE_TYPES.has(type)) status = "active";
  // CANCELLATION 은 자동갱신만 끈 것 → 만료일까지는 프리미엄 유지. status/expires 유지.
  else if (type === "CANCELLATION") status = "active";
  else if (type === "BILLING_ISSUE") status = "active"; // 유예기간, expires_at 기준으로 판단됨

  const row: Record<string, unknown> = {
    user_id: appUserId,
    rc_app_user_id: appUserId,
    plan_type: productId || "revenuecat",
    product_id: productId,
    status,
    started_at: startedAt,
    expires_at: expiresAt,
    is_manual: false,
    source: store === "PLAY_STORE" ? "android" : "ios",
    rc_original_transaction_id: origTxn,
    store_transaction_id: storeTxn,
    store,
    environment,
    period_type: periodType,
    rc_event_id: eventId,
    rc_last_event_type: type,
    updated_at: nowIso,
  };

  try {
    // 멱등 upsert: 같은 original_transaction_id 구독은 한 row 로 관리
    if (origTxn) {
      const { data: existing, error: selErr } = await supabase
        .from("subscriptions")
        .select("id")
        .eq("rc_original_transaction_id", origTxn)
        .maybeSingle();
      if (selErr) throw selErr;

      if (existing?.id) {
        // 기존 구독 갱신 (started_at, created_at 은 유지하려고 제외)
        const upd = { ...row };
        delete (upd as any).started_at;
        const { error } = await supabase
          .from("subscriptions")
          .update(upd)
          .eq("id", existing.id);
        if (error) throw error;
      } else {
        const { error } = await supabase.from("subscriptions").insert(row);
        if (error) throw error;
      }
    } else {
      const { error } = await supabase.from("subscriptions").insert(row);
      if (error) throw error;
    }
  } catch (e) {
    console.error("subscriptions write error:", e);
    return json({ error: String((e as any)?.message || e) }, 500);
  }

  return json({ ok: true, type, user: appUserId });
});

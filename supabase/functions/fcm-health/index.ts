// 우리 캘린더 — 안드로이드 푸시(FCM) 키 점검. 함수 이름: fcm-health
// Firebase 관리자 키(시크릿 FCM_SERVICE_ACCOUNT)를 교체한 뒤, 실제 기기에 아무것도 보내지 않고
// 키가 동작하는지 확인한다: 매번 시크릿을 새로 읽어 → 구글 접근 토큰 발급 → validate_only 로
// 가짜 토큰에 보내 본다. 인증이 되면 FCM 은 '토큰이 이상함'(400 INVALID_ARGUMENT)으로 답한다.
// service_role 키로 부른 요청만 받는다. 비밀값은 응답에 넣지 않는다(키 ID 앞 12자만).
import { serve } from 'https://deno.land/std@0.168.0/http/server.ts'

function isServiceRole(req: Request): boolean {
  const h = req.headers.get('Authorization') ?? ''
  const tok = h.startsWith('Bearer ') ? h.slice(7).trim() : ''
  if (!tok) return false
  const envKey = Deno.env.get('SUPABASE_SERVICE_ROLE_KEY') ?? ''
  if (envKey && tok === envKey) return true
  try {
    let s = tok.split('.')[1].replace(/-/g, '+').replace(/_/g, '/')
    while (s.length % 4) s += '='
    return JSON.parse(atob(s))?.role === 'service_role'
  } catch (_e) { return false }
}

serve(async (req) => {
  if (!isServiceRole(req)) return json({ error: 'Forbidden' }, 403)
  const raw = Deno.env.get('FCM_SERVICE_ACCOUNT')
  if (!raw) return json({ ok: false, step: 'secret', message: 'FCM_SERVICE_ACCOUNT 없음' })
  let sa: any
  try { sa = JSON.parse(raw) } catch (_e) { return json({ ok: false, step: 'parse', message: 'JSON 형식이 아님' }) }
  const keyId = String(sa.private_key_id || '').slice(0, 12)
  const project = sa.project_id
  const account = String(sa.client_email || '').replace(/@.*/, '')
  try {
    const jose = await import('npm:jose@5')
    const pk = await jose.importPKCS8(sa.private_key, 'RS256')
    const now = Math.floor(Date.now() / 1000)
    const assertion = await new jose.SignJWT({ scope: 'https://www.googleapis.com/auth/firebase.messaging' })
      .setProtectedHeader({ alg: 'RS256', kid: sa.private_key_id })
      .setIssuer(sa.client_email).setSubject(sa.client_email)
      .setAudience('https://oauth2.googleapis.com/token')
      .setIssuedAt(now).setExpirationTime(now + 600)
      .sign(pk)
    const tr = await fetch('https://oauth2.googleapis.com/token', {
      method: 'POST',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      body: new URLSearchParams({ grant_type: 'urn:ietf:params:oauth:grant-type:jwt-bearer', assertion }),
    })
    const tj = await tr.json().catch(() => ({}))
    if (!tj.access_token) {
      return json({ ok: false, step: 'oauth', keyId, project, account, status: tr.status, error: tj.error || '', desc: String(tj.error_description || '').slice(0, 160) })
    }
    const r = await fetch(`https://fcm.googleapis.com/v1/projects/${project}/messages:send`, {
      method: 'POST',
      headers: { Authorization: `Bearer ${tj.access_token}`, 'Content-Type': 'application/json' },
      body: JSON.stringify({ validate_only: true, message: { token: 'fcm-health-check-invalid-token', notification: { title: 'check' } } }),
    })
    const body = await r.json().catch(() => ({}))
    const code = body?.error?.status || (r.ok ? 'OK' : '')
    // 400 INVALID_ARGUMENT / 404 NOT_FOUND(UNREGISTERED) = 인증 통과(가짜 토큰이라 거부된 것)
    const authOk = r.ok || r.status === 400 || r.status === 404
    return json({ ok: authOk, step: 'fcm', keyId, project, account, status: r.status, code })
  } catch (e: any) {
    return json({ ok: false, step: 'sign', keyId, project, account, message: String(e?.message ?? e).slice(0, 160) })
  }
})

function json(obj: unknown, status = 200): Response {
  return new Response(JSON.stringify(obj), { status, headers: { 'Content-Type': 'application/json' } })
}

// 우리 캘린더 — 관리자 알림 발송(공지/이벤트) + 진단
// 함수 이름: admin-broadcast
// 관리자(ADMIN_ID)만 호출 가능. 대상: 'all'(모든 실제 멤버) | 'me'(관리자 본인) | {room_id} | {user_ids:[...]}
// FCM(네이티브 앱) + Web Push 동시 발송. 응답에 진단 정보(saPresent·fcmTotal·fcmSent·sampleErrors) 포함.
//   → saPresent=false 면 FCM_SERVICE_ACCOUNT 시크릿 미설정(= 안드로이드 미발송 근본원인).

import { serve } from 'https://deno.land/std@0.168.0/http/server.ts'
import { createClient } from 'https://esm.sh/@supabase/supabase-js@2'
import webpush from 'npm:web-push@3.6.7'

const corsHeaders = {
  'Access-Control-Allow-Origin': '*',
  'Access-Control-Allow-Headers': 'authorization, x-client-info, apikey, content-type',
  'Access-Control-Allow-Methods': 'POST, OPTIONS',
}

const ADMIN_ID = '3bc13a8b-618d-4d47-abea-1eec4d682ec0'

const VAPID_PUBLIC = Deno.env.get('VAPID_PUBLIC_KEY')!
const VAPID_PRIVATE = Deno.env.get('VAPID_PRIVATE_KEY')!
const VAPID_SUBJECT = Deno.env.get('VAPID_SUBJECT') ?? 'mailto:lsung179@gmail.com'
try { webpush.setVapidDetails(VAPID_SUBJECT, VAPID_PUBLIC, VAPID_PRIVATE) } catch (_e) { /* noop */ }

let _fcmAccessToken: { token: string; exp: number } | null = null
async function getFcmAccessToken(sa: any): Promise<string> {
  const now = Math.floor(Date.now() / 1000)
  if (_fcmAccessToken && _fcmAccessToken.exp - 60 > now) return _fcmAccessToken.token
  const jose = await import('npm:jose@5')
  const privateKey = await jose.importPKCS8(sa.private_key, 'RS256')
  const assertion = await new jose.SignJWT({ scope: 'https://www.googleapis.com/auth/firebase.messaging' })
    .setProtectedHeader({ alg: 'RS256' })
    .setIssuer(sa.client_email).setSubject(sa.client_email)
    .setAudience('https://oauth2.googleapis.com/token')
    .setIssuedAt(now).setExpirationTime(now + 3600)
    .sign(privateKey)
  const res = await fetch('https://oauth2.googleapis.com/token', {
    method: 'POST', headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams({ grant_type: 'urn:ietf:params:oauth:grant-type:jwt-bearer', assertion }),
  })
  const tok = await res.json()
  if (!tok.access_token) throw new Error('FCM access token 발급 실패: ' + JSON.stringify(tok))
  _fcmAccessToken = { token: tok.access_token, exp: now + (tok.expires_in || 3600) }
  return tok.access_token
}

serve(async (req) => {
  if (req.method === 'OPTIONS') return new Response('ok', { headers: corsHeaders })
  try {
    const authHeader = req.headers.get('Authorization') ?? ''
    if (!authHeader.startsWith('Bearer ')) return json({ error: 'Unauthorized' }, 401)

    const supabaseAuth = createClient(
      Deno.env.get('SUPABASE_URL')!, Deno.env.get('SUPABASE_ANON_KEY')!,
      { global: { headers: { Authorization: authHeader } } }
    )
    const { data: { user }, error: authErr } = await supabaseAuth.auth.getUser()
    if (authErr || !user) return json({ error: 'Unauthorized' }, 401)
    if (user.id !== ADMIN_ID) return json({ error: 'Forbidden: admin only' }, 403)

    const { title, body, url, tag, target, user_ids, room_id, kind, inApp } = await req.json()
    if (!title || !body) return json({ error: 'title, body 필수' }, 400)

    const admin = createClient(Deno.env.get('SUPABASE_URL')!, Deno.env.get('SUPABASE_SERVICE_ROLE_KEY')!)

    // ── 대상 user_id 목록 결정 ──
    let targetIds: string[] = []
    if (target === 'me') {
      targetIds = [ADMIN_ID]
    } else if (Array.isArray(user_ids) && user_ids.length) {
      targetIds = [...new Set(user_ids.filter((x: any) => typeof x === 'string'))]
    } else if (room_id) {
      const { data: mems } = await admin.from('members').select('user_id, is_virtual').eq('room_id', room_id)
      targetIds = [...new Set((mems ?? []).filter((m: any) => m.user_id && m.is_virtual !== true).map((m: any) => m.user_id))]
    } else {
      // 'all' — 모든 실제 멤버(가상 제외)
      const { data: mems } = await admin.from('members').select('user_id, is_virtual')
      targetIds = [...new Set((mems ?? []).filter((m: any) => m.user_id && m.is_virtual !== true).map((m: any) => m.user_id))]
    }
    if (!targetIds.length) return json({ ok: true, message: '대상 사용자 없음', targetUsers: 0 })

    // ── FCM 서비스계정 ──
    const saRaw = Deno.env.get('FCM_SERVICE_ACCOUNT')
    let sa: any = null
    if (saRaw) { try { sa = JSON.parse(saRaw) } catch (_e) { sa = null } }
    const saPresent = !!sa

    // ── 인앱 알림 row(옵션, 기본 저장) ──
    if (inApp !== false) {
      const rows = targetIds.map((uid) => ({
        user_id: uid, title, body, type: 'admin',
        room_id: room_id || null, actor_user_id: ADMIN_ID, read: false,
      }))
      try { await admin.from('notifications').insert(rows) } catch (_e) { /* notifications 스키마 상이 시 무시 */ }
    }

    // ── 토큰/구독 조회 ──
    let fcmTokens: any[] = []
    const fcmUsers = new Set<string>()
    if (sa) {
      const { data: tokens } = await admin.from('fcm_tokens').select('id, token, user_id').in('user_id', targetIds)
      fcmTokens = tokens ?? []
      for (const t of fcmTokens) fcmUsers.add(t.user_id)
    }
    const { data: subs } = await admin.from('push_subscriptions')
      .select('id, user_id, endpoint, p256dh, auth').in('user_id', targetIds)

    const payloadStr = JSON.stringify({ title, body, url: url || '/', tag: tag || 'admin-broadcast' })
    const sampleErrors: any[] = []

    // ── Web Push (FCM 있는 유저는 스킵) ──
    let webSent = 0, webTotal = 0
    for (const sub of subs ?? []) {
      if (fcmUsers.has(sub.user_id)) continue
      webTotal++
      try {
        await webpush.sendNotification({ endpoint: sub.endpoint, keys: { p256dh: sub.p256dh, auth: sub.auth } },
          payloadStr, { urgency: 'high', TTL: 86400 })
        webSent++
      } catch (e: any) {
        const sc = e?.statusCode ?? 0
        if (sc === 410 || sc === 404) { try { await admin.from('push_subscriptions').delete().eq('id', sub.id) } catch (_) {} }
        else if (sampleErrors.length < 5) sampleErrors.push({ kind: 'web', status: sc, msg: String(e?.message || '').slice(0, 120) })
      }
    }

    // ── FCM ──
    let fcmSent = 0
    const fcmTotal = fcmTokens.length
    if (sa && fcmTokens.length) {
      let accessToken = ''
      try { accessToken = await getFcmAccessToken(sa) }
      catch (e: any) { sampleErrors.push({ kind: 'fcm-auth', msg: String(e?.message || e).slice(0, 200) }) }
      if (accessToken) {
        for (const row of fcmTokens) {
          try {
            const r = await fetch(`https://fcm.googleapis.com/v1/projects/${sa.project_id}/messages:send`, {
              method: 'POST',
              headers: { Authorization: `Bearer ${accessToken}`, 'Content-Type': 'application/json' },
              body: JSON.stringify({
                message: {
                  token: row.token,
                  notification: { title, body },
                  data: { url: String(url || '/'), tag: String(tag || 'admin-broadcast') },
                  android: { priority: 'high', notification: { default_sound: true, channel_id: 'uri_high', notification_priority: 'PRIORITY_HIGH' } },
                },
              }),
            })
            if (r.ok) {
              fcmSent++
              await admin.from('fcm_tokens').update({ last_used_at: new Date().toISOString() }).eq('id', row.id)
            } else {
              const errBody = await r.text()
              if (sampleErrors.length < 5) sampleErrors.push({ kind: 'fcm', status: r.status, body: errBody.slice(0, 200) })
              if (r.status === 404 || errBody.includes('UNREGISTERED') || errBody.includes('NOT_FOUND') || errBody.includes('INVALID_ARGUMENT')) {
                await admin.from('fcm_tokens').delete().eq('id', row.id)
              }
            }
          } catch (e: any) {
            if (sampleErrors.length < 5) sampleErrors.push({ kind: 'fcm-exc', msg: String(e?.message || e).slice(0, 160) })
          }
        }
      }
    }

    return json({
      ok: true,
      targetUsers: targetIds.length,
      saPresent,               // ★ false 면 FCM 시크릿 미설정
      fcmTotal, fcmSent,       // 안드로이드 앱 발송 결과
      webTotal, webSent,       // 웹/PWA 발송 결과
      sampleErrors,            // 실패 샘플(최대 5)
    })
  } catch (e: any) {
    return json({ error: e?.message ?? String(e) }, 500)
  }
})

function json(obj: unknown, status = 200): Response {
  return new Response(JSON.stringify(obj), { status, headers: { ...corsHeaders, 'Content-Type': 'application/json' } })
}

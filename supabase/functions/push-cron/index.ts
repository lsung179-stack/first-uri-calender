// 우리 캘린더 V2 — 매일 오전 다이제스트 + 1시간 전 리마인더 + 빈 방 안내 + 생일 축하
// 함수 이름: push-cron
// 발송 규칙(중복 방지): 한 유저가 앱(기기 토큰)을 가지고 있으면 그 유저의 웹푸시는
//   건너뛴다(앱 알림으로 대체). 앱이 없는 유저만 웹푸시.
// 헤드업: FCM 메시지에 고중요도 채널(uri_high) + PRIORITY_HIGH 지정.
// 카테고리 설정(v13): profiles.notif_prefs[key]===false 인 유저는 해당 모드 알림(인앱 row+푸시 모두) 스킵.
//   daily→'daily', reminder→'reminder', empty-rooms→'nudge', birthday→'birthday'.
// v14: 여러 방에 '복사한 일정'(같은 multi_group_id)은 한 유저에게 알림이 중복으로 가지 않도록
//   daily/reminder에서 유저별 multi_group_id 1회만 발송(reminder_sent_at은 복사본 전체에 기록).
// v15(2026-08-19): 플랫폼 분기 — fcm_tokens.platform 이 'ios' 인 토큰은 FCM 이 아니라
//   push-apns 함수를 통해 APNs(api.push.apple.com)로 직접 보낸다. iOS 를 FCM 으로 보내려면
//   Firebase 콘솔에 iOS 앱 등록 + APNs 키 업로드가 선행돼야 해서 계속 막혀 있었기 때문.
//   모든 발송이 pushToUser() 하나를 지나가므로 분기도 그 함수 안에만 있다.
// [2026-09-26 보안] pg_cron(Vault 의 service_role 키)만 부를 수 있다 — verify_jwt=true + role 확인.
//   예전엔 인증이 전혀 없어 누구나 {mode:'daily'} 등을 보내 전 사용자에게 알림을 반복 발송시킬 수 있었다.
// v20(2026-10-10): 같은 일정 판단에 '제목·시간이 같음'도 더한다 — 묶음 표시(multi_group_id) 없이
//   여러 방에 따로 만든 같은 일정이 아침 다이제스트에 '창게스탄, 창게스탄'처럼 두 번 나오던 것(사용자 제보).
//   daily(목록·개수)와 reminder(1시간 전 알림) 모두 유저별로 sameEventKeys() 중 하나라도 겹치면 1회만.
// v21(2026-10-21): 반복 일정을 넣는다 — 예전엔 .eq('date', today) 라 반복 일정은 처음 시작한 날에만 아침 요약·
//   1시간 전 알림에 나오고, 반복 일정만 있는 방은 '오늘 일정 없어요' 안내까지 받았다(사용자 이탈 점검).
//   eventsOn() = 오늘 날짜 줄 + 오늘 이전에 시작한 반복 줄 중 오늘 발생하는 것(recur.ts — 앱과 같은 규칙, 실데이터 224개·2년치 대조 일치).
//   1시간 전 알림은 reminder_sent_at 이 '오늘(KST)'이면 보낸 것으로 본다(반복 줄은 한 줄로 날마다 보내야 해서).

import { serve } from 'https://deno.land/std@0.168.0/http/server.ts'
import { createClient } from 'https://esm.sh/@supabase/supabase-js@2'
import webpush from 'npm:web-push@3.6.7'
import KoreanLunarCalendar from 'npm:korean-lunar-calendar@0.4.0'
import { occursOn } from './recur.ts'

const VAPID_PUBLIC = Deno.env.get('VAPID_PUBLIC_KEY')!
const VAPID_PRIVATE = Deno.env.get('VAPID_PRIVATE_KEY')!
const VAPID_SUBJECT = Deno.env.get('VAPID_SUBJECT') ?? 'mailto:lsung179@gmail.com'

webpush.setVapidDetails(VAPID_SUBJECT, VAPID_PUBLIC, VAPID_PRIVATE)

// [SEC-GUARD 2026-09-26] 서버 내부 전용 — service_role 키로 부른 요청만 받는다.
// (게이트웨이 verify_jwt=true 가 서명을 확인한 뒤이므로 role 클레임을 믿을 수 있다)
function isServiceRole(req: Request): boolean {
  const h = req.headers.get('Authorization') ?? ''
  const tok = h.startsWith('Bearer ') ? h.slice(7).trim() : ''
  if (!tok) return false
  const envKey = Deno.env.get('SUPABASE_SERVICE_ROLE_KEY') ?? ''
  if (envKey && tok === envKey) return true
  try {
    let s = tok.split('.')[1].replace(/-/g, '+').replace(/_/g, '/')
    while (s.length % 4) s += '='
    const p = JSON.parse(atob(s))
    return p?.role === 'service_role'
  } catch (_e) { return false }
}
// [/SEC-GUARD]

// v20: 한 유저에게 '같은 일정'으로 볼 열쇠들 — 하나라도 겹치면 알림·다이제스트에 1회만.
//   ① 여러 방 복사본(같은 multi_group_id)  ② 제목(앞뒤·중복 공백 정리)과 시간이 같은 일정
function sameEventKeys(ev: any): string[] {
  const title = String(ev.title ?? '').trim().replace(/\s+/g, ' ')
  const keys = ['t:' + title + '|' + (ev.time || '')]
  if (ev.multi_group_id) keys.push('m:' + ev.multi_group_id)
  return keys
}

let _fcmAccessToken: { token: string; exp: number } | null = null

async function getFcmAccessToken(sa: any): Promise<string> {
  const now = Math.floor(Date.now() / 1000)
  if (_fcmAccessToken && _fcmAccessToken.exp - 60 > now) return _fcmAccessToken.token
  const jose = await import('npm:jose@5')
  const privateKey = await jose.importPKCS8(sa.private_key, 'RS256')
  const assertion = await new jose.SignJWT({ scope: 'https://www.googleapis.com/auth/firebase.messaging' })
    .setProtectedHeader({ alg: 'RS256' })
    .setIssuer(sa.client_email)
    .setSubject(sa.client_email)
    .setAudience('https://oauth2.googleapis.com/token')
    .setIssuedAt(now)
    .setExpirationTime(now + 3600)
    .sign(privateKey)
  const res = await fetch('https://oauth2.googleapis.com/token', {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams({
      grant_type: 'urn:ietf:params:oauth:grant-type:jwt-bearer',
      assertion,
    }),
  })
  const tok = await res.json()
  if (!tok.access_token) throw new Error('FCM access token 발급 실패: ' + JSON.stringify(tok))
  _fcmAccessToken = { token: tok.access_token, exp: now + (tok.expires_in || 3600) }
  return tok.access_token
}

let _fcmSA: any | null | undefined = undefined
function getFcmSA(): any | null {
  if (_fcmSA !== undefined) return _fcmSA
  const raw = Deno.env.get('FCM_SERVICE_ACCOUNT')
  if (!raw) { _fcmSA = null; return null }
  try { _fcmSA = JSON.parse(raw) } catch (e) { console.warn('FCM_SERVICE_ACCOUNT 파싱 실패:', (e as any)?.message); _fcmSA = null }
  return _fcmSA
}

// iOS 토큰은 push-apns 함수에 넘겨 APNs로 직접 보낸다(죽은 토큰 정리도 그쪽에서).
async function sendApns(items: any[]): Promise<number> {
  if (!items.length) return 0
  try {
    const res = await fetch(`${Deno.env.get('SUPABASE_URL')}/functions/v1/push-apns`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        Authorization: `Bearer ${Deno.env.get('SUPABASE_SERVICE_ROLE_KEY')}`,
      },
      body: JSON.stringify({ items }),
    })
    const j = await res.json()
    if (j && j.configured === false) console.warn('APNs 미설정:', j.message)
    return Number(j?.sent ?? 0)
  } catch (e: any) {
    console.warn('APNs 발송 실패:', e?.message ?? e)
    return 0
  }
}

serve(async (req) => {
  if (!isServiceRole(req)) return json({ error: 'Forbidden' }, 403)
  try {
    const body = await req.json().catch(() => ({}))
    const mode = body.mode || 'daily'

    const supa = createClient(
      Deno.env.get('SUPABASE_URL')!,
      Deno.env.get('SUPABASE_SERVICE_ROLE_KEY')!
    )

    if (mode === 'daily') return await handleDaily(supa)
    if (mode === 'reminder') return await handleReminder(supa)
    if (mode === 'empty-rooms') return await handleEmptyRooms(supa)
    if (mode === 'birthday') return await handleBirthday(supa)
    return json({ error: 'invalid mode (use daily, reminder, empty-rooms or birthday)' }, 400)
  } catch (e) {
    console.error('push-cron error:', e)
    return json({ error: (e as any)?.message ?? 'unknown' }, 500)
  }
})

// 카테고리 설정이 '꺼짐(false)'인 유저 집합. 조회 실패 시 빈 집합(=전체 발송, 기존 동작).
async function prefOffSet(supa: any, userIds: string[], key: string): Promise<Set<string>> {
  const off = new Set<string>()
  if (!userIds.length) return off
  try {
    const { data } = await supa
      .from('profiles')
      .select('id, notif_prefs')
      .in('id', userIds)
    for (const p of data ?? []) {
      if (p.notif_prefs && p.notif_prefs[key] === false) off.add(p.id)
    }
  } catch (_e) { /* noop */ }
  return off
}

async function handleDaily(supa: any) {
  const today = kstDateStr()

  const events = await eventsOn(supa, today, 'id, user_id, title, time, room_id, multi_group_id')

  if (!events || events.length === 0) {
    return json({ mode: 'daily', message: 'no events today', sent: 0 })
  }

  const filtered = await filterByNotifEnabled(supa, events)
  if (filtered.length === 0) {
    return json({ mode: 'daily', message: 'all users muted', sent: 0 })
  }

  const byUser: Record<string, any[]> = {}
  const seen: Record<string, Set<string>> = {}   // 유저별 같은 일정(복사본·같은 제목과 시간) 중복 제거
  for (const ev of filtered) {
    if (!byUser[ev.user_id]) { byUser[ev.user_id] = []; seen[ev.user_id] = new Set<string>() }
    const keys = sameEventKeys(ev)
    if (keys.some((k) => seen[ev.user_id].has(k))) continue  // 같은 일정 → 1회만 집계
    for (const k of keys) seen[ev.user_id].add(k)
    byUser[ev.user_id].push(ev)
  }

  const off = await prefOffSet(supa, Object.keys(byUser), 'daily')

  let totalSent = 0
  let totalFcm = 0
  for (const userId of Object.keys(byUser)) {
    if (off.has(userId)) continue
    const evs = byUser[userId]
    const count = evs.length
    evs.sort((a: any, b: any) => (a.time || 'zz').localeCompare(b.time || 'zz'))
    const titles = evs.slice(0, 3).map((e: any) => e.title).join(', ')
    const more = count > 3 ? ` 외 ${count - 3}개` : ''
    const firstRoomId = evs[0].room_id

    await supa.from('notifications').insert({
      user_id: userId,
      title: `오늘 일정 ${count}개`,
      body: titles + more,
      type: 'digest',
      room_id: firstRoomId,
      event_date: today,
      actor_user_id: userId,
      read: false,
    })

    const result = await pushToUser(supa, userId, {
      title: `오늘 일정 ${count}개`,
      body: titles + more,
      url: `/?room=${firstRoomId}&date=${today}`,
      tag: `daily-${today}`,
    })
    totalSent += result.sent
    totalFcm += result.fcmSent
  }

  return json({ mode: 'daily', users: Object.keys(byUser).length, prefOff: off.size, sent: totalSent, fcmSent: totalFcm })
}

async function handleEmptyRooms(supa: any) {
  const today = kstDateStr()

  const todayEvents = await eventsOn(supa, today, 'id, room_id')
  const roomsWithEvents = new Set<string>((todayEvents ?? []).map((e: any) => e.room_id))

  const { data: mems } = await supa
    .from('members')
    .select('user_id, room_id, notifications_enabled, is_virtual')
  if (!mems || mems.length === 0) {
    return json({ mode: 'empty-rooms', message: 'no members', sent: 0 })
  }

  const allRoomIds = [...new Set(mems.map((m: any) => m.room_id))]
  const { data: rooms } = await supa
    .from('rooms')
    .select('id, name')
    .in('id', allRoomIds)
  const roomName: Record<string, string> = {}
  for (const r of rooms ?? []) roomName[r.id] = r.name || '방'

  const emptyByUser: Record<string, string[]> = {}
  for (const m of mems) {
    if (m.is_virtual === true) continue
    if (!m.user_id) continue
    if (m.notifications_enabled === false) continue
    if (roomsWithEvents.has(m.room_id)) continue
    if (!emptyByUser[m.user_id]) emptyByUser[m.user_id] = []
    emptyByUser[m.user_id].push(m.room_id)
  }

  const off = await prefOffSet(supa, Object.keys(emptyByUser), 'nudge')

  let totalSent = 0
  let totalFcm = 0
  let userCount = 0
  for (const userId of Object.keys(emptyByUser)) {
    if (off.has(userId)) continue
    const roomIds = emptyByUser[userId]
    if (!roomIds || roomIds.length === 0) continue
    userCount++
    const names = roomIds.map((id) => roomName[id] || '방')
    const firstRoomId = roomIds[0]

    const title = '오늘 일정을 등록해볼까요?'
    let bodyMsg: string
    if (names.length === 1) {
      bodyMsg = `오늘 '${names[0]}'에 등록된 일정이 없어요. 소중한 하루, 일정을 남겨보세요 📅`
    } else {
      const shown = names.slice(0, 2).map((n) => `'${n}'`).join(', ')
      const more = names.length > 2 ? ` 외 ${names.length - 2}곳` : ''
      bodyMsg = `오늘 ${shown}${more}에 등록된 일정이 없어요. 일정을 등록해보세요 📅`
    }

    await supa.from('notifications').insert({
      user_id: userId,
      title,
      body: bodyMsg,
      type: 'empty',
      room_id: firstRoomId,
      event_date: today,
      actor_user_id: userId,
      read: false,
    })

    const result = await pushToUser(supa, userId, {
      title,
      body: bodyMsg,
      url: `/?room=${firstRoomId}&date=${today}`,
      tag: `empty-${today}`,
    })
    totalSent += result.sent
    totalFcm += result.fcmSent
  }

  return json({ mode: 'empty-rooms', users: userCount, prefOff: off.size, sent: totalSent, fcmSent: totalFcm })
}

async function handleBirthday(supa: any) {
  const today = kstDateStr()          // YYYY-MM-DD (KST)
  const mmdd = today.slice(5)         // MM-DD

  const { data: bdayUsers } = await supa
    .from('profiles')
    .select('id, name')
    .eq('birthday', mmdd)
  if (!bdayUsers || bdayUsers.length === 0) {
    return json({ mode: 'birthday', message: 'no birthdays today', sent: 0 })
  }
  const bdayIds = bdayUsers.map((u: any) => u.id)
  const nameById: Record<string, string> = {}
  for (const u of bdayUsers) nameById[u.id] = u.name || '멤버'

  const { data: bdayMems } = await supa
    .from('members')
    .select('user_id, room_id')
    .in('user_id', bdayIds)
  const roomsOfBday: Record<string, Set<string>> = {}
  const firstRoomOf: Record<string, string> = {}
  const allRoomIds = new Set<string>()
  for (const m of bdayMems ?? []) {
    if (!m.room_id) continue
    allRoomIds.add(m.room_id)
    if (!roomsOfBday[m.room_id]) roomsOfBday[m.room_id] = new Set<string>()
    roomsOfBday[m.room_id].add(m.user_id)
    if (!firstRoomOf[m.user_id]) firstRoomOf[m.user_id] = m.room_id
  }

  let totalSent = 0
  let totalFcm = 0
  let recipCount = 0

  if (allRoomIds.size > 0) {
    const { data: roomMems } = await supa
      .from('members')
      .select('user_id, room_id, notifications_enabled, is_virtual')
      .in('room_id', [...allRoomIds])
    const recipMap: Record<string, { names: Set<string>; room: string }> = {}
    for (const m of roomMems ?? []) {
      if (m.is_virtual === true || !m.user_id) continue
      if (m.notifications_enabled === false) continue
      const bset = roomsOfBday[m.room_id]
      if (!bset) continue
      for (const bId of bset) {
        if (bId === m.user_id) continue
        if (!recipMap[m.user_id]) recipMap[m.user_id] = { names: new Set<string>(), room: m.room_id }
        recipMap[m.user_id].names.add(nameById[bId] || '멤버')
      }
    }
    const offRecip = await prefOffSet(supa, Object.keys(recipMap), 'birthday')
    for (const rid of Object.keys(recipMap)) {
      if (offRecip.has(rid)) continue
      const { names, room } = recipMap[rid]
      const arr = [...names]
      const nameStr = arr.length === 1
        ? arr[0]
        : arr.slice(0, 2).join(', ') + (arr.length > 2 ? ` 외 ${arr.length - 2}명` : '')
      const title = `🎂 ${nameStr}님의 생일이에요`
      const body = `오늘은 ${nameStr}님의 생일! 축하 메시지를 남겨보세요 🎉`
      await supa.from('notifications').insert({
        user_id: rid, title, body, type: 'birthday', room_id: room,
        event_date: today, actor_user_id: rid, read: false,
      })
      const r = await pushToUser(supa, rid, { title, body, url: `/?room=${room}`, tag: `bday-${today}` })
      totalSent += r.sent; totalFcm += r.fcmSent; recipCount++
    }
  }

  const offSelf = await prefOffSet(supa, bdayIds, 'birthday')
  for (const b of bdayUsers) {
    if (offSelf.has(b.id)) continue
    const title = '🎂 생일 축하해요!'
    const body = '오늘은 특별한 날이에요. 우리 캘린더가 함께 축하할게요 🎉'
    const room = firstRoomOf[b.id]
    if (room) {
      await supa.from('notifications').insert({
        user_id: b.id, title, body, type: 'birthday_self', room_id: room,
        event_date: today, actor_user_id: b.id, read: false,
      })
    }
    const r = await pushToUser(supa, b.id, { title, body, url: `/`, tag: `bday-self-${today}` })
    totalSent += r.sent; totalFcm += r.fcmSent
  }

  return json({ mode: 'birthday', birthdays: bdayUsers.length, recipients: recipCount, sent: totalSent, fcmSent: totalFcm })
}

async function handleReminder(supa: any) {
  const today = kstDateStr()
  const nowKstMin = kstMinutesOfDay()

  // 오늘 이미 보낸 것(reminder_sent_at 이 오늘 KST)은 뺀다 — 반복 줄은 한 줄이라 날마다 다시 보낼 수 있어야 한다
  const events = (await eventsOn(supa, today, 'id, user_id, title, time, date, room_id, multi_group_id, reminder_sent_at', true))
    .filter((e: any) => kstDateOf(e.reminder_sent_at) !== today)

  if (!events || events.length === 0) {
    return json({ mode: 'reminder', message: 'nothing to remind', sent: 0 })
  }

  const windowStart = nowKstMin + 55
  const windowEnd = nowKstMin + 65

  const targets: any[] = []
  for (const ev of events) {
    const t = String(ev.time)
    const [h, m] = t.split(':').map((s: string) => parseInt(s, 10))
    if (isNaN(h) || isNaN(m)) continue
    const startMin = h * 60 + m
    if (startMin >= windowStart && startMin <= windowEnd) {
      targets.push(ev)
    }
  }

  if (targets.length === 0) {
    return json({ mode: 'reminder', message: 'no events in window', sent: 0 })
  }

  const filtered = await filterByNotifEnabled(supa, targets)
  const off = await prefOffSet(supa, [...new Set(filtered.map((e: any) => e.user_id))], 'reminder')

  let totalSent = 0
  let totalFcm = 0
  let reminded = 0

  // 같은 일정(여러 방 복사본 = 같은 multi_group_id, 또는 같은 제목·시간)은 한 유저에게 알림 1회만.
  // (reminder_sent_at은 아래에서 처리한 일정 전체에 기록 → 건너뛴 쪽이 다음 실행에 재알림되지 않음)
  const seen = new Set<string>()
  for (const ev of filtered) {
    if (off.has(ev.user_id)) continue
    const keys = sameEventKeys(ev).map((k) => ev.user_id + '|' + k)
    if (keys.some((k) => seen.has(k))) continue
    for (const k of keys) seen.add(k)
    reminded++

    await supa.from('notifications').insert({
      user_id: ev.user_id,
      title: '1시간 후 일정',
      body: `${ev.title} · ${ev.time}`,
      type: 'reminder',
      room_id: ev.room_id,
      event_date: ev.date,
      actor_user_id: ev.user_id,
      read: false,
    })

    const result = await pushToUser(supa, ev.user_id, {
      title: '1시간 후 일정',
      body: `${ev.title} · ${ev.time}`,
      url: `/?room=${ev.room_id}&date=${ev.date}`,
      tag: `reminder-${ev.multi_group_id || ev.id}`,
    })
    totalSent += result.sent
    totalFcm += result.fcmSent
  }

  const allProcessedIds = targets.map((t: any) => t.id)
  if (allProcessedIds.length > 0) {
    await supa
      .from('events')
      .update({ reminder_sent_at: new Date().toISOString() })
      .in('id', allProcessedIds)
  }

  return json({ mode: 'reminder', processed: allProcessedIds.length, reminded, prefOff: off.size, sent: totalSent, fcmSent: totalFcm })
}

async function filterByNotifEnabled(supa: any, events: any[]): Promise<any[]> {
  if (events.length === 0) return []
  const userIds = [...new Set(events.map(e => e.user_id))]
  const roomIds = [...new Set(events.map(e => e.room_id))]
  const { data: mems } = await supa
    .from('members')
    .select('user_id, room_id, notifications_enabled')
    .in('user_id', userIds)
    .in('room_id', roomIds)

  const enabled = new Set<string>()
  for (const m of mems ?? []) {
    if (m.notifications_enabled !== false) {
      enabled.add(`${m.user_id}|${m.room_id}`)
    }
  }

  return events.filter(ev => enabled.has(`${ev.user_id}|${ev.room_id}`))
}

// 반환 fcmSent = '앱으로 보낸 수'(안드로이드 FCM + iOS APNs 합계). 호출부 집계 이름은 그대로 둔다.
async function pushToUser(supa: any, userId: string, payload: any): Promise<{ sent: number; fcmSent: number }> {
  const payloadStr = JSON.stringify(payload)
  const sa = getFcmSA()

  // 기기 토큰 조회 — platform 으로 iOS(APNs) / 그 외(FCM) 를 가른다.
  // ⚠️ 예전엔 FCM 시크릿(sa)이 없으면 조회 자체를 건너뛰었는데, 그러면 iOS 도 같이 막힌다.
  const { data: allTokens } = await supa
    .from('fcm_tokens')
    .select('id, token, platform')
    .eq('user_id', userId)
  const deviceTokens = allTokens ?? []
  const iosTokens = deviceTokens.filter((t: any) => t.platform === 'ios')
  const fcmTokens = deviceTokens.filter((t: any) => t.platform !== 'ios')
  const hasApp = deviceTokens.length > 0     // 앱이 있으면 웹푸시는 건너뜀(이중 알림 방지)

  let sent = 0
  if (!hasApp) {
    const { data: subs } = await supa
      .from('push_subscriptions')
      .select('id, endpoint, p256dh, auth')
      .eq('user_id', userId)

    const toDelete: number[] = []
    for (const s of subs ?? []) {
      try {
        await webpush.sendNotification(
          { endpoint: s.endpoint, keys: { p256dh: s.p256dh, auth: s.auth } },
          payloadStr,
          { urgency: 'high', TTL: 86400 }
        )
        sent++
      } catch (e: any) {
        if (e?.statusCode === 410 || e?.statusCode === 404) {
          toDelete.push(s.id)
        } else {
          console.warn('push fail user=', userId, 'endpoint=', s.endpoint, e?.statusCode)
        }
      }
    }
    if (toDelete.length > 0) {
      await supa.from('push_subscriptions').delete().in('id', toDelete)
    }
  }

  // ── iOS: APNs 직접 발송 ──
  const apnsSent = await sendApns(iosTokens.map((t: any) => ({
    id: t.id,
    token: t.token,
    title: payload.title || '우리 캘린더',
    body: payload.body || '',
    url: payload.url || '/',
    tag: payload.tag || 'wuri-calendar',
  })))

  // ── Android: FCM 발송 ──
  let fcmSent = 0
  try {
    if (sa && fcmTokens.length > 0) {
      const accessToken = await getFcmAccessToken(sa)
      for (const row of fcmTokens) {
        try {
          const r = await fetch(
            `https://fcm.googleapis.com/v1/projects/${sa.project_id}/messages:send`,
            {
              method: 'POST',
              headers: {
                Authorization: `Bearer ${accessToken}`,
                'Content-Type': 'application/json',
              },
              body: JSON.stringify({
                message: {
                  token: row.token,
                  notification: { title: payload.title || '우리 캘린더', body: payload.body || '' },
                  data: { url: String(payload.url || '/'), tag: String(payload.tag || 'wuri-calendar') },
                  android: {
                    priority: 'high',
                    notification: {
                      default_sound: true,
                      channel_id: 'uri_high',
                      notification_priority: 'PRIORITY_HIGH',
                    },
                  },
                },
              }),
            }
          )
          if (r.ok) {
            fcmSent++
            await supa.from('fcm_tokens').update({ last_used_at: new Date().toISOString() }).eq('id', row.id)
          } else {
            const errBody = await r.text()
            if (
              r.status === 404 ||
              errBody.includes('UNREGISTERED') ||
              errBody.includes('NOT_FOUND') ||
              errBody.includes('INVALID_ARGUMENT')
            ) {
              await supa.from('fcm_tokens').delete().eq('id', row.id)
            }
            console.warn('FCM 발송 실패:', r.status, errBody.slice(0, 200))
          }
        } catch (e: any) {
          console.warn('FCM 단건 발송 예외:', e?.message ?? e)
        }
      }
    }
  } catch (e: any) {
    console.warn('FCM 발송 블록 스킵:', e?.message ?? e)
  }

  return { sent, fcmSent: fcmSent + apnsSent }
}

// ── 오늘 보이는 일정(반복 포함) ──
const REPEAT_COLS = 'repeat_type, recurring_yearly, repeat_weekdays, repeat_monthly_mode, repeat_nth_week, repeat_until, repeat_interval, repeat_count, repeat_lunar_month, repeat_lunar_day, repeat_except, range_group_id'

function lunarToSolar(ly: number, lm: number, ld: number) {
  try {
    const c = new (KoreanLunarCalendar as any)()
    if (!c.setLunarDate(ly, lm, ld, false)) return null
    const s = c.getSolarCalendar()
    return s && s.year ? { year: s.year, month: s.month, day: s.day } : null
  } catch (_e) { return null }
}

// 한 번에 1000줄 제한을 넘어 끝까지(id 순)
async function fetchAll(build: () => any): Promise<any[]> {
  const out: any[] = []
  for (let i = 0; i < 50; i++) {
    const { data, error } = await build().order('id').range(i * 1000, i * 1000 + 999)
    if (error) throw error
    out.push(...(data ?? []))
    if (!data || data.length < 1000) break
  }
  return out
}

// day 에 보이는 일정 — 그날 줄(건너뛴 날 제외) + 그 전에 시작한 반복 줄의 그날 발생(date 를 day 로 바꿔 돌려준다).
// 기간+반복(같은 range_group_id 여러 줄)은 사이클 시작일에만 — 이어지는 날마다 알리지 않는다.
async function eventsOn(supa: any, day: string, cols: string, onlyTimed = false): Promise<any[]> {
  const sel = `${cols}, ${REPEAT_COLS}`
  const sameDay = await fetchAll(() => {
    let q = supa.from('events').select(sel).eq('date', day)
    if (onlyTimed) q = q.not('time', 'is', null)
    return q
  })
  const reps = await fetchAll(() => {
    let q = supa.from('events').select(sel).lt('date', day)
      .or('repeat_type.in.(daily,weekly,monthly,yearly,lunar),recurring_yearly.eq.true')
    if (onlyTimed) q = q.not('time', 'is', null)
    return q
  })
  const span: Record<string, number> = {}
  const gids = [...new Set(reps.map((e: any) => e.range_group_id).filter(Boolean))]
  if (gids.length) {
    const rows = await fetchAll(() => supa.from('events').select('id, range_group_id').in('range_group_id', gids))
    for (const r of rows) span[r.range_group_id] = (span[r.range_group_id] ?? 0) + 1
  }
  const out = sameDay.filter((e: any) => occursOn(e, day, lunarToSolar))
  for (const e of reps) {
    const sd = e.range_group_id ? Math.max(1, span[e.range_group_id] ?? 1) : 1
    if (occursOn(e, day, lunarToSolar, sd)) out.push({ ...e, date: day, origin_date: e.date })
  }
  return out
}

function kstDateOf(ts: string | null | undefined): string | null {
  if (!ts) return null
  const t = new Date(ts).getTime()
  if (isNaN(t)) return null
  return new Date(t + 9 * 60 * 60 * 1000).toISOString().slice(0, 10)
}

function kstDateStr() {
  const now = new Date()
  const kst = new Date(now.getTime() + 9 * 60 * 60 * 1000)
  return kst.toISOString().slice(0, 10)
}
function kstMinutesOfDay() {
  const now = new Date()
  const kst = new Date(now.getTime() + 9 * 60 * 60 * 1000)
  return kst.getUTCHours() * 60 + kst.getUTCMinutes()
}

function json(body: any, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

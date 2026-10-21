// 반복 일정이 그날 있는지 — 앱(index.html _originMatchesDate·_repeatIntervalCountOkRaw, Flutter recurrence.dart)과 같은 규칙.
// 서버(push-cron)가 아침 요약·1시간 전 알림·빈 방 안내에 반복 일정을 넣으려고 쓴다(2026-10-21 — 예전엔 시작일만).
// ⚠️ 주 시작 요일은 사용자 기기 설정이라 서버는 모른다 → 일요일(0) 기준. 'N주마다'·'N째 주'만 영향(현재 그런 반복은 0~1개).
// 외부 의존 없음(음력 변환은 넘겨받는다) — node --experimental-strip-types 로 시험한다.

export type LunarToSolar = (ly: number, lm: number, ld: number) => { year: number; month: number; day: number } | null

export const REPEAT_TYPES = ['daily', 'weekly', 'monthly', 'yearly', 'lunar']
const WS = 0

const pad = (n: number) => String(n).padStart(2, '0')
const key = (dt: Date) => `${dt.getFullYear()}-${pad(dt.getMonth() + 1)}-${pad(dt.getDate())}`
const parse = (k: string) => { const [y, m, d] = k.split('-').map(Number); return new Date(y, m - 1, d) }

export function repeatTypeOf(ev: any): string {
  return ev.repeat_type && ev.repeat_type !== 'none' ? ev.repeat_type : (ev.recurring_yearly ? 'yearly' : 'none')
}

function cycleUnit(rt: string): string | null {
  if (rt === 'daily') return 'day'
  if (rt === 'weekly') return 'week'
  if (rt === 'monthly') return 'month'
  if (rt === 'yearly' || rt === 'lunar') return 'year'
  return null
}

function cycleIndex(origin: string, d: string, unit: string): number {
  if (unit === 'day') return Math.round((parse(d).getTime() - parse(origin).getTime()) / 86400000)
  if (unit === 'week') {
    const wk = (s: string) => { const dt = parse(s); dt.setDate(dt.getDate() - ((dt.getDay() - WS + 7) % 7)); return dt }
    return Math.round((wk(d).getTime() - wk(origin).getTime()) / 86400000 / 7)
  }
  const o = parse(origin), dt = parse(d)
  if (unit === 'month') return (dt.getFullYear() - o.getFullYear()) * 12 + (dt.getMonth() - o.getMonth())
  return dt.getFullYear() - o.getFullYear()
}

function intervalCountOk(origin: string, rt: string, interval: any, count: any, d: string): boolean {
  const unit = cycleUnit(rt)
  if (!unit) return true
  const iv = interval && interval > 1 ? interval : 1
  const idx = cycleIndex(origin, d, unit)
  if (idx < 0) return false
  if (iv > 1 && idx % iv !== 0) return false
  if (count) { if (Math.floor(idx / iv) + 1 > count) return false }
  return true
}

function lastRowOwnerMonth(dt: Date): { y: number; m: number } | null {
  const col = (dt.getDay() - WS + 7) % 7
  const rs = new Date(dt.getFullYear(), dt.getMonth(), dt.getDate() - col)
  const re = new Date(rs.getFullYear(), rs.getMonth(), rs.getDate() + 6)
  for (const c of [{ y: rs.getFullYear(), m: rs.getMonth() }, { y: re.getFullYear(), m: re.getMonth() }]) {
    const last = new Date(c.y, c.m, new Date(c.y, c.m + 1, 0).getDate())
    if (last >= rs && last <= re) return c
  }
  return null
}

function calendarRowOfDate(y: number, m0: number, dn: number): number {
  const firstCol = (new Date(y, m0, 1).getDay() - WS + 7) % 7
  return Math.floor((dn - 1 + firstCol) / 7) + 1
}

/** 반복 origin 이 날짜 d(origin 다음 날 이후)에 새 발생을 만드나 — _originMatchesDate. spanDays>1 = 기간+반복(사이클 시작 요일만) */
export function originMatchesDate(ev: any, d: string, lunarToSolar: LunarToSolar | null, spanDays = 1): boolean {
  if (d <= ev.date) return false
  const rt = repeatTypeOf(ev)
  let matched = false
  let cycleDate: string | null = null
  const wds: number[] = Array.isArray(ev.repeat_weekdays) ? ev.repeat_weekdays : []
  if (rt === 'daily') matched = true
  else if (rt === 'yearly') matched = d.slice(5) === String(ev.date).slice(5)
  else if (rt === 'monthly') {
    const mode = ev.repeat_monthly_mode || 'dom'
    if (mode === 'nth') {
      if (!wds.length || !ev.repeat_nth_week) return false
      const dt = parse(d)
      if (spanDays > 1) { if (dt.getDay() !== wds[0]) return false } else if (!wds.includes(dt.getDay())) return false
      const y = dt.getFullYear(), m = dt.getMonth(), dn = dt.getDate()
      if (ev.repeat_nth_week === -1) {
        const owner = lastRowOwnerMonth(dt)
        matched = !!owner
        if (owner && (owner.y !== y || owner.m !== m)) {
          cycleDate = `${owner.y}-${pad(owner.m + 1)}-${pad(new Date(owner.y, owner.m + 1, 0).getDate())}`
        }
      } else matched = calendarRowOfDate(y, m, dn) === ev.repeat_nth_week
    } else if (mode === 'last') {
      const dt = parse(d)
      matched = dt.getDate() === new Date(dt.getFullYear(), dt.getMonth() + 1, 0).getDate()
    } else matched = d.slice(8, 10) === String(ev.date).slice(8, 10)
  } else if (rt === 'weekly') {
    if (!wds.length) return false
    const dow = parse(d).getDay()
    matched = spanDays > 1 ? dow === wds[0] : wds.includes(dow)
  } else if (rt === 'lunar') {
    if (!ev.repeat_lunar_month || !ev.repeat_lunar_day || !lunarToSolar) return false
    const y = parseInt(d.slice(0, 4), 10)
    matched = [y, y - 1].some((ly) => {
      const s = lunarToSolar(ly, ev.repeat_lunar_month, ev.repeat_lunar_day)
      return !!(s && `${s.year}-${pad(s.month)}-${pad(s.day)}` === d)
    })
  }
  if (!matched) return false
  return intervalCountOk(String(ev.date), rt, ev.repeat_interval, ev.repeat_count, cycleDate || d)
}

/** 이 일정 행이 날짜 day 에 보이나(알림용) — 시작일 자체 또는 반복 발생, 건너뛴 날·종료일 반영.
 *  기간+반복은 사이클 시작일에만(이어지는 날마다 알리지 않는다). */
export function occursOn(ev: any, day: string, lunarToSolar: LunarToSolar | null, spanDays = 1): boolean {
  const except: string[] = Array.isArray(ev.repeat_except) ? ev.repeat_except : []
  if (except.includes(day)) return false
  if (ev.repeat_until && day > ev.repeat_until) return false
  if (ev.date === day) return true
  if (repeatTypeOf(ev) === 'none') return false
  return originMatchesDate(ev, day, lunarToSolar, spanDays)
}

export const dateKey = key

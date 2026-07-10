/* ═══════════════════════════════════════════════════════════════════
   우리 캘린더 — 위젯 데이터 브릿지 v2 (index.html 이식용)
   ⚠️ 아직 index.html에 병합 안 함 — 위젯 도입(빌드) 시점에 이식.
   iOS/Android 네이티브에서만 동작. App Group / SharedPreferences에
   "모든 방 · 멤버 · 한 달치 일정 · 할일"을 JSON으로 기록 → 위젯이 읽음.

   설계:
   - 위젯은 방을 바꿀 수 있어야 하므로(방 선택), 현재 방만이 아니라
     내가 속한 '모든 방'의 데이터를 한 번에 내보낸다.
   - 앱은 현재 방 일정만 메모리에 있으므로, 브릿지가 Supabase에서
     전체 방의 [오늘-7 ~ 오늘+40] 창(월 그리드+2주+다가오는 커버)을 직접 조회.
   - 색은 앱의 colorHex()로 hex 변환해 내보냄 → Swift/Kotlin은 hex만 쓰면 됨.

   내보내는 JSON 스키마(위젯이 읽는 계약):
   {
     updatedAt: 1720000000000,
     currentRoomId: "r1",
     rooms: [{
       id, name, seal,                 // seal = 'color:sym' or null (방 아이콘)
       members: [{userId, name, color}],   // color = hex
       events:  [{date, title, time, color, userId}],  // color = hex, date=YYYY-MM-DD, time="HH:MM" or ""
       todos:   [{id, date, title, time, color, done}]
     }]
   }

   이식 시 필요한 전역(모두 index.html에 이미 있음):
   isNativeApp, supa, _currentUser, _currentRoom, _myRooms, _mySealByRoom,
   colorHex, _todayKeyStr, window.Capacitor.Plugins.Preferences
   ═══════════════════════════════════════════════════════════════════ */

const WIDGET_GROUP = 'group.com.lsung.uricalendar';
const WIDGET_KEY = 'widget.data';
let _wgSyncTimer = null;

/* 일정 변경 후 호출 — 700ms 디바운스로 몰아서 1회 기록 */
function syncWidgetData() {
  try {
    if (typeof isNativeApp !== 'function' || !isNativeApp()) return; // 네이티브만
    clearTimeout(_wgSyncTimer);
    _wgSyncTimer = setTimeout(_wgDoSync, 700);
  } catch (_) {}
}

/* 날짜 유틸 (YYYY-MM-DD) */
function _wgShift(base, days) {
  const d = new Date(base.getFullYear(), base.getMonth(), base.getDate() + days);
  const p = n => String(n).padStart(2, '0');
  return d.getFullYear() + '-' + p(d.getMonth() + 1) + '-' + p(d.getDate());
}

/* 실제 내보내기 — 위젯 페이로드 생성 후 Preferences에 기록 */
async function _wgDoSync() {
  try {
    const P = window.Capacitor && window.Capacitor.Plugins && window.Capacitor.Plugins.Preferences;
    if (!P || !_currentUser) return;
    const payload = await buildWidgetPayload();
    if (!payload) return;
    if (P.configure) { try { await P.configure({ group: WIDGET_GROUP }); } catch (_) {} }
    await P.set({ key: WIDGET_KEY, value: JSON.stringify(payload) });
    // 위젯 즉시 갱신(플러그인 있을 때만 — 없으면 자정/주기 갱신에 맡김)
    const W = window.Capacitor && window.Capacitor.Plugins && window.Capacitor.Plugins.WidgetsBridge;
    if (W && W.reloadAllTimelines) { try { W.reloadAllTimelines(); } catch (_) {} }
  } catch (_) { /* 위젯 실패가 앱에 영향 주지 않게 무시 */ }
}

/* 위젯 페이로드 빌드 — 내가 속한 모든 방의 창(window) 데이터를 조회해 조립.
   테스트를 위해 순수 함수에 가깝게(전역 의존만) 분리. */
async function buildWidgetPayload() {
  const rooms = (typeof _myRooms !== 'undefined' && _myRooms) ? _myRooms : [];
  if (!rooms.length) {
    return { updatedAt: Date.now(), currentRoomId: null, rooms: [] };
  }
  const roomIds = rooms.map(r => r.id);
  const today = new Date();
  const from = _wgShift(today, -7);   // 지난주 일부(진행 중 기간 일정 커버)
  const to = _wgShift(today, 40);     // 월 그리드 + 2주 + 다가오는 커버

  // 1) 창 내 일정 (모든 방)
  let events = [];
  try {
    const { data } = await supa.from('events')
      .select('room_id,date,title,time,color,user_id')
      .in('room_id', roomIds).gte('date', from).lte('date', to);
    events = data || [];
  } catch (_) {}

  // 2) 멤버 (모든 방) + 프로필 이름
  let members = [];
  try {
    const { data } = await supa.from('members')
      .select('room_id,user_id,color,is_virtual,virtual_name')
      .in('room_id', roomIds);
    members = data || [];
  } catch (_) {}
  const realIds = [...new Set(members.filter(m => m.user_id && !m.is_virtual).map(m => m.user_id))];
  let profById = {};
  if (realIds.length) {
    try {
      const { data } = await supa.from('profiles').select('id,name,email').in('id', realIds);
      (data || []).forEach(p => { profById[p.id] = p; });
    } catch (_) {}
  }

  // 3) 창 내 할일 (모든 방)
  let todos = [];
  try {
    const { data } = await supa.from('todos')
      .select('id,room_id,start_date,title,time,color,done')
      .in('room_id', roomIds).gte('start_date', from).lte('start_date', to);
    todos = data || [];
  } catch (_) {}

  const hex = (c) => (typeof colorHex === 'function' ? colorHex(c || 'c-vintage') : (c || '#8b3a2a'));
  const sealMap = (typeof _mySealByRoom !== 'undefined' && _mySealByRoom) ? _mySealByRoom : {};

  const outRooms = rooms.map(r => {
    const rMembers = members.filter(m => m.room_id === r.id).map(m => {
      const name = m.is_virtual
        ? (m.virtual_name || '멤버')
        : ((profById[m.user_id] && profById[m.user_id].name)
           || (profById[m.user_id] && profById[m.user_id].email ? profById[m.user_id].email.split('@')[0] : '멤버'));
      return { userId: m.user_id || null, name, color: hex(m.color) };
    });
    const rEvents = events.filter(e => e.room_id === r.id).map(e => ({
      date: e.date,
      title: String(e.title || '').slice(0, 60),
      time: String(e.time || '').trim(),
      color: hex(e.color),
      userId: e.user_id || null,
    }));
    const rTodos = todos.filter(t => t.room_id === r.id).map(t => ({
      id: String(t.id),
      date: t.start_date,
      title: String(t.title || '').slice(0, 60),
      time: String(t.time || '').trim(),
      color: hex(t.color),
      done: !!t.done,
    }));
    return { id: r.id, name: r.name || '방', seal: sealMap[r.id] || null,
             members: rMembers, events: rEvents, todos: rTodos };
  });

  return {
    updatedAt: Date.now(),
    currentRoomId: (typeof _currentRoom !== 'undefined' && _currentRoom) ? _currentRoom.id : (outRooms[0] ? outRooms[0].id : null),
    rooms: outRooms,
  };
}

// 테스트/이식용 노출
if (typeof window !== 'undefined') {
  window.syncWidgetData = syncWidgetData;
  window.buildWidgetPayload = buildWidgetPayload;
}

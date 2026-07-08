/* ═══════════════════════════════════════════════════════════════════
   우리 캘린더 — 위젯 데이터 브릿지 (index.html에 이식할 스니펫)
   ⚠️ 아직 index.html에 병합하지 않음 — 위젯 도입 시점에 이식.
   iOS 네이티브에서만 동작. App Group UserDefaults에 7일치 일정 JSON을 기록해
   WidgetKit 익스텐션(widget/ios/UriCalendarWidget.swift)이 읽게 한다.

   전제(빌드 스크립트): npm i @capacitor/preferences
   선택(즉시 갱신): npm i capacitor-widgetsbridge-plugin  — 없으면 자정 자동 갱신만.

   이식 시 TODO:
   1. 날짜키 헬퍼: 아래 _wgDateKey를 index.html의 기존 날짜키 함수로 교체 가능(형식 YYYY-MM-DD 동일하면 그대로 둬도 됨)
   2. 일정 조회: getDayEvents(key) 부분을 index.html의 실제 일별 일정 함수(반복 일정 확장 포함)로 연결
      (renderMonthly가 쓰는 일별 캐시 함수 — 코드 16번 참고)
   3. 색상: _wgColorHex의 색상키→hex 매핑을 앱의 색상 팔레트와 맞춤
   4. 호출 지점: loadEvents 완료 후 / 일정 저장·수정·삭제 후 / enterRoom 후 → syncWidgetData()
   ═══════════════════════════════════════════════════════════════════ */

function _wgDateKey(d){
  const p=n=>String(n).padStart(2,'0');
  return d.getFullYear()+'-'+p(d.getMonth()+1)+'-'+p(d.getDate());
}

/* 색상키 → hex (앱 팔레트 근사값 — 이식 시 실제 값으로) */
function _wgColorHex(colorKey){
  const MAP={ 'c-vintage':'#8b3a2a', 'c-navy':'#5d7291', 'c-sage':'#7e8b6f', 'c-gold':'#b3873e', 'c-rose':'#b06a77', 'c-plum':'#7d5b7f' };
  return MAP[colorKey]||'#8b3a2a';
}

let _wgSyncTimer=null;
/* 일정 변경 후 호출 — 500ms 디바운스로 몰아서 1회 기록 */
function syncWidgetData(){
  try{
    if(typeof isNativeApp!=='function'||!isNativeApp()) return;   // 네이티브만
    if(typeof isAndroidNative==='function'&&isAndroidNative()) return; // iOS만 (Android 위젯은 추후)
    clearTimeout(_wgSyncTimer);
    _wgSyncTimer=setTimeout(_wgDoSync, 500);
  }catch(_){}
}

async function _wgDoSync(){
  try{
    const P=window.Capacitor&&window.Capacitor.Plugins&&window.Capacitor.Plugins.Preferences;
    if(!P) return;   // Preferences 플러그인 없는 빌드 — 조용히 무시
    if(P.configure) await P.configure({ group:'group.com.lsung.uricalendar' });

    const days=[];
    const now=new Date();
    for(let i=0;i<7;i++){
      const d=new Date(now.getFullYear(),now.getMonth(),now.getDate()+i);
      const key=_wgDateKey(d);
      // TODO(이식): 반복 일정 포함 일별 조회 함수로 교체
      const raw=(typeof getDayEvents==='function')?getDayEvents(key):((window._events&&window._events[key])||[]);
      const evs=(raw||[]).slice(0,6).map(e=>({
        title:String(e.title||'').slice(0,40),
        time:e.time||null,
        color:_wgColorHex(e.color),
      }));
      if(i<2||evs.length) days.push({ date:key, label:i===0?'오늘':(i===1?'내일':null), events:evs });
    }

    await P.set({ key:'widget.events', value: JSON.stringify({
      updatedAt:new Date().toISOString(),
      roomName:(window._currentRoom&&window._currentRoom.name)||'',
      days,
    })});

    // 위젯 즉시 갱신 (플러그인 있을 때만 — 없으면 자정 타임라인 갱신에 맡김)
    const W=window.Capacitor&&window.Capacitor.Plugins&&window.Capacitor.Plugins.WidgetsBridge;
    if(W&&W.reloadAllTimelines) W.reloadAllTimelines();
  }catch(_){/* 위젯 실패가 앱에 영향 주지 않게 무시 */}
}

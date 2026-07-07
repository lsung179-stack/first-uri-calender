# 우리 캘린더 (Our Calendar) — 프로젝트 인수인계 노트

> 이 파일은 새 세션이 시작될 때 자동으로 읽힙니다. 매번 설명하지 않아도 됩니다.
> 새 대화를 열면 먼저 이 파일 + 관련 코드/DB를 확인한 뒤 작업하세요.

## ⚠️ 유지보수 규칙 (Claude에게 — 반드시 지킬 것)
**이 저장소의 파일(특히 `index.html`)이나 DB(RPC/마이그레이션)를 변경할 때마다, 같은 커밋에서 이 `CLAUDE.md`도 갱신한다.**
- 무엇을 바꿨는지 아래 “이미 완료된 수정” 목록에 한 줄로 추가(또는 기존 항목 수정).
- 프리미엄/광고/결제 로직, 함수명, 계정, DB 스키마, 워크플로우가 바뀌면 해당 섹션도 함께 고친다.
- 날짜/버전 기준이 바뀌면 “이미 완료된 수정”의 날짜 표기도 갱신한다.
- 즉 코드 변경과 이 문서를 **항상 함께 커밋**한다 (문서가 뒤처지지 않게).

## 프로젝트 개요
- 한국어 공유 캘린더 앱. **단일 `index.html` SPA**를 Capacitor 8(WebView)로 감싼 구조. 모든 JS/CSS/HTML이 이 한 파일 안에 있음.
- 백엔드: **Supabase** (Auth, Postgres, RPC). 구독 결제: **RevenueCat** (@revenuecat/purchases-capacitor). 광고: **AdMob** (@capacitor-community/admob) — 하단 배너 1개.
- 빌드: **Codemagic**이 매 빌드마다 네이티브 프로젝트 재생성 → 실기기(iOS + Android) 설치.

## 배포 워크플로우 (중요)
- 개발자는 보통 **`index.html`을 GitHub에 직접 업로드**(“Add files via upload”)하는 방식으로 작업. 그래서 **로컬 최신본이 저장소(main)보다 앞서 있을 수 있음** — 새 세션은 반드시 현재 배포/로컬 버전이 무엇인지 확인할 것.
- 클라이언트 코드 변경은 **Codemagic 재빌드** 후에야 기기에 반영됨.
- **DB(RPC/마이그레이션) 변경은 재빌드와 무관하게 즉시 라이브.**
- ⚠️ 광고(AdMob)는 **네이티브 앱에서만** 동작. 웹/PWA에서는 절대 안 뜸(정상). 광고 확인은 반드시 네이티브 앱에서. 앱 설정 화면 하단 푸터에 `네이티브`/`웹` 및 `광고:<_admobDebug>` 문자열이 표시됨.

## Supabase
- 프로젝트 id: **`bgqzkkaslqchbovzrkao`**
- 핵심 테이블: `public.subscriptions` (user_id, status, expires_at, plan_type, is_manual)
- `public.members` (room_id, user_id, color, notifications_enabled, role, is_virtual, virtual_*, **`sort_order` int**=방목록 사용자별 순서). RLS `members_update_self`(user_id=auth.uid())로 본인 행 수정 가능.
- `public.rooms` (id, name, code, owner_id, **`mascot` text**=방 마스코트 'charKey:exprKey' 예:'gomdol:honey', null이면 기본 첫 글자 씰). RLS UPDATE는 `owner_id=auth.uid()`(방장)만 — 그래서 마스코트는 SECURITY DEFINER RPC `set_room_mascot(p_room_id,p_mascot)`(방 멤버면 누구나, 형식가드 `^[a-z]+:[a-z]+$` 또는 null)로 변경. 마이그레이션 `add_room_mascot`.
- ~~`public.anniversaries`~~ — **방별 공유 D-day/기념일 기능은 제거됨**(사용자 요청, "느낌 없음"). 클라이언트 코드(스트립/시트/JS/CSS) 전부 삭제. 단 DB 테이블 `anniversaries`(마이그레이션 `create_anniversaries`)는 **그대로 남아있음**(빈 테이블, 미사용). 나중에 재도입하거나 정리할 때 참고.
- 관리자 RPC: `admin_users_detailed()` — 회원별 활동지표(SECURITY DEFINER, 관리자만).
- 관리자 RPC: `admin_growth_stats()` — WAU/MAU·활성화율·7일 리텐션·구독전환·공유방 비율(대시보드 성장 섹션).
- Edge Functions: `push-cron`(mode: daily/reminder/empty-rooms/**birthday**), `send-push`, `revenuecat-webhook`. pg_cron: daily 08:00·empty-rooms 08:05·**birthday 09:00(KST=0 0 UTC)**·reminder 5분마다·오래된 notifications 정리. 생일=`profiles.birthday`('MM-DD') 매칭, 같은 방 멤버+본인에게 축하 푸시.
- 핵심 RPC: `my_premium_status()` — SECURITY DEFINER. `status='active' AND expires_at>now()` 인 구독이 있을 때만 `is_premium=true` 반환.
  - ⚠️ 과거 버그: 집계함수(max/array_agg/bool_or)를 GROUP BY 없이 써서 구독 0건이어도 항상 1행+상수 `is_premium:true`를 반환 → **모든 유저가 프리미엄으로 오판**. `HAVING count(*)>0` 추가로 수정 완료(라이브).

## 계정
- 관리자: `lsung179@naver.com` — `ANN_ADMIN_USER_ID = '3bc13a8b-618d-4d47-abea-1eec4d682ec0'`. 실구독 보유. 앱에선 `_adminPlanOverride`(무료/유료 플랜 체험 토글)로 프리미엄 표시를 제어(실구독과 무관).
- 무료 테스트: `lsung179@gmail.com` — `64c6502a-6365-40cb-ad05-49ec8cb6439e`. 구독 없음 → 무료여야 정상(광고O, 유료기능X).

## 프리미엄 / 광고 로직 지도 (index.html 함수명)
- 상태: `_isPremium`(= `_serverPremium`), `_serverPremium`/`_serverPremiumInfo`(서버 기준), `_rcPremiumActive`(기기 RevenueCat 기준 — 판정엔 미사용).
- `_recomputePremium()` → `_isPremium = _serverPremium` (**서버가 유일한 진실**). 기기 엔타이틀먼트로 프리미엄 부여 금지.
- `loadPremiumStatus()` → RPC `my_premium_status` 호출 → `_serverPremium` 설정.
- `isEffectivePremium()` → 관리자는 `_adminPlanOverride`, 일반 유저는 `_isPremium`.
- 결제: `onPremiumSubscribe()` → `_applyRcEntitlement()`(구매 직후 낙관 반영 후 서버 재동기화). **구매 복원 `onRestorePurchases()`는 `_applyRcEntitlement` 안 씀** — `loadPremiumStatus()` 후 `_serverPremium`일 때만 프리미엄(기기 Apple ID 구독 신뢰 금지 → 계정 간 프리미엄 누수 방지).
- `syncPremiumFromRevenueCat()` — **자동 호출 금지**(기기 구독을 직접 읽어 누수 위험). 정의만 두고 호출하지 않음.
- 광고: `showAdBannerIfNeeded()`, `hideAdBanner()`, `_anySubSheetOpen()`(열린 시트 있으면 광고 숨김), `_elVisible(el)`(실제 보이는 요소만 카운트 — 잔여 `.on` 오판 방지), `_admobDebug`(설정 화면 푸터에 노출).

## 이미 완료된 수정 (2026-07 기준)
1. ✅ **RPC `my_premium_status()` 수정** — `HAVING count(*)>0` 추가. DB 라이브. (무료 유저가 프리미엄으로 뜨던 근본 원인)
2. ✅ **광고 게이팅** — `_anySubSheetOpen()`이 `_elVisible()`로 실제 보이는 시트만 카운트(닫힌 시트 잔여 `.on`이 광고를 영구히 숨기던 버그).
3. ✅ **구매 복원 누수** — `onRestorePurchases()` 서버 단일 기준. 기기 엔타이틀먼트로 프리미엄 안 켜짐.
4. ✅ **멤버 없을 때 캘린더가 위로 올라가던 현상** — 노치 여백(`env(safe-area-inset-top)`)을 조건부로 표시되던 `.cal-member-tabs`에서 항상 존재하는 부모 `.screen.cal`로 이동.
5. ✅ 관리자 버튼 `전체 이용자 프리미엄`(globalTrialBtn)·`프리미엄 개별 관리`(premMgmtBtn) UI 삭제(충돌 우려). **(2026-07 추가 정리)** 남아있던 죽은 코드 완전 제거 — index.html의 `premMgmtOverlay/Card` 모달 마크업 + `loadGlobalTrialState/toggleGlobalTrial/openPremiumManage/premSearchUsers/premSetOverride` 등 함수 일체 삭제. 이 override 방식(`premium_overrides` 테이블·`app_config.global_premium_trial`)은 **애초에 무효**였음 — `my_premium_status()`가 `subscriptions`만 읽어서 override를 반영하지 않음. DB의 RPC(`admin_set_premium_override`/`set_global_premium_trial`)는 그대로 두되 미사용. ⚠️ **진짜 수동부여는 admin.html의 '수동 구독 부여'(`admin_grant_subscription` RPC → `subscriptions`에 `is_manual=true` 행 insert)이며 정상 작동함.** 이건 유지. 급하면 SQL로 직접 부여 가능: `insert into subscriptions(user_id,plan_type,status,started_at,expires_at,is_manual,manual_reason,granted_by,granted_at,source) values(<uid>,'manual','active',now(),now()+interval '1 year',true,'사유',<admin_uid>,now(),'manual');`
6. ✅ **7일 무료체험 노출(전환)** — 전 플랜 첫 결제 무료체험이 UI에 안 보이던 문제. `showUpgradeModal`(잠금 안내: "무료로 체험하기"), `openPremiumSheet`/CTA 버튼("7일 무료로 시작하기"), `_updateTrialCta()`(선택 플랜 가격 기준 "첫 7일 무료·이후 자동결제" 캡션 `#premTrialNote`), `renderTrialBanner`("첫 7일 무료")에 반영.
7. ✅ **혼자 방 초대 넛지(바이럴)** — 실멤버 1명인 방 캘린더 상단(`#calInviteNudge`)에 초대 배너 표시. `renderInviteNudge()`가 `_myMembers` 실멤버<2일 때 노출, `shareKakao()` 호출. `renderMemberTabs()` 시작부에서 호출. 배너에 ✕ 닫기 버튼(`dismissInviteNudge`) — 닫으면 그 방 id를 `localStorage['uricalv2.inviteNudgeDismissed']`에 저장해 다시 안 띄움(방별 기억). ✕ 클릭 시 `vConfirm`('다시 보지 않기'/'취소') 확인 후 저장(실수 방지).
8. ✅ **프리미엄 시트 탭바 숨김 버그** — 일정추가(탭바 숨김)→프리미엄 시트 진입 후 닫으면 하단 탭바가 안 돌아오던 문제. `openPremiumSheet`에 `hideTabbar()`, `closePremiumSheet`에 `showTabbar()` 추가(다른 풀스크린 시트와 대칭). 겸사겸사 프리미엄 안내 모달(`showUpgradeModal`의 `vConfirm`) 타이틀의 ✨ 제거.
9. ✅ **관리자 페이지 자동 로그인(세션 전달)** — `openAdminFullPage()`가 admin.html로 세션 토큰(`#at=&rt=`)을 안 실어 보내, 외부 브라우저/새 탭에서 세션이 없어 비번 폼으로 빠지던 버그. 관리자 계정이 **카카오/애플 소셜 로그인(비번 없음, `has_password:false`)**이라 비번 폼으로는 진입 불가였음. `getSession()` 토큰을 URL 해시로 실어 보내 admin이 `setSession`으로 자동 로그인하도록 수정.
10. ✅ **관리자 회원관리 상세화** — DB에 `admin_users_detailed()`(SECURITY DEFINER, 관리자 가드) RPC 추가: 유저별 최근활동(last_sign_in/이벤트/가입 GREATEST)·일정수·방수·구독·푸시토큰 유무. admin.html 회원관리를 **카드 대시보드**로 개편: 상단 KPI 타일(전체·7일/2주 활성·휴면·구독), 필터칩(+구독자), 회원 카드(활동색 아바타 링·이름·최근활동·📅일정·🏠방·가입경로·💎구독/휴면/🔔push 배지), 활동많은순·최근일정순 정렬, KPI에 7일 신규 추가. 카드=좌측 활동색 바+상태라벨(활발/보통/뜸함/휴면)+3칸 그리드(일정/방/최근접속)+'🗓 마지막 일정' 줄(last_event_at). '휴면(30일+ 미활동)'=앱삭제/이탈 추정(정확한 삭제 신호 없음). ※ '최근 활동'은 last_activity(=GREATEST(last_sign_in,last_event,가입)) 기준 — last_sign_in만 쓰면 로그인 유지 유저가 오래전 접속으로 오표시됨. 🔔=fcm_tokens OR push_subscriptions(알림 허용). 필터에 '최근 가입순' 있음.
11. ✅ **관리자 성장 대시보드** — `admin_growth_stats()` RPC + 대시보드에 '성장·리텐션·전환' 섹션(WAU/MAU, 활성화율, 7일 리텐션, 구독 전환율, 공유방 비율). Edge Function `push-cron`/`send-push`로 리텐션 푸시는 이미 가동 중.
12. ✅ **생일 축하 푸시** — `push-cron`에 `handleBirthday()`+`mode='birthday'` 추가(재배포 v12). 오늘 생일(`profiles.birthday`='MM-DD')인 유저와 같은 방 멤버에게 '🎂 OO님의 생일이에요', 본인에게 '🎂 생일 축하해요'. pg_cron `push-birthday`(매일 09:00 KST) 추가. Edge Function은 git 아닌 Supabase에 있음(수정 시 원본 백업 후 재배포).
13. ✅ **페이월 문구 전환 최적화(#2)** — 잠금 안내(`showUpgradeModal`) 문구를 '~는 프리미엄 전용/구독하면'(제한·결제 프레임)에서 '혜택+하고 싶은 것'(사진·테마·컬러/캐릭터팩·글꼴·할일) 프레임으로 교체. 모달이 이미 '무료로 체험하기' 버튼+7일 무료 문구를 붙이므로 전환 유도 강화.
14. ✅ **컬러 팩 카드 전체 클릭** — `renderStoreColors()`에서 기존엔 `.pack-btn` 버튼에만 onclick이 있어 버튼을 정확히 눌러야 안내모달이 떴음. 카드 전체(`.theme-card`)에 `onclick`(`_cardClick`: 잠금=`onUnlockColorClick`, 보유=`onLockColorClick`)+`cursor:pointer` 부여, 버튼에서는 onclick 제거(카드로 버블링 → 단일 발화, 중복 없음). 상자 아무 곳이나 눌러도 프리미엄 안내모달이 뜸.
15. ✅ **🔒 프리미엄 버튼 여백** — 자물쇠 이모지와 '프리미엄' 텍스트가 붙어 보이던 문제. 이모지를 `<span class="pack-btn-lock">🔒</span>`로 감싸고 `.pack-btn-lock{margin-inline-end:9px}`(카드 잠금태그는 5px) CSS 추가. 폰트 무관하게 고정 여백. 적용 대상: 컬러/캐릭터 팩 버튼·캐릭터 상세시트 큰 버튼(`pack-btn-lg`)·텍스트 테마 버튼·카드 잠금태그(`theme-card-lock-tag`). ⚠️ 최초엔 5px로 넣었으나 원래 있던 공백(~4px)과 거의 같아 티가 안 나서 **9px로 상향**(눈에 띄게).
16. ✅ **일정 드래그 부드럽게(성능+집기/놓기 애니메이션)** — 드래그 이동 끊김 개선 + iOS식 집기/놓기 감각. (1) 고스트를 `left/top`(리플로우) → **`transform:translate3d`(GPU 합성)** 이동. (2) `pointermove`마다 하던 고스트 이동+`elementFromPoint` 히트테스트를 **`requestAnimationFrame` 배칭(프레임당 1회)** 으로 — 최신 좌표만 `_evPtrX/Y`에 저장, `_evDragFrame()`에서 처리. (3) 고스트를 **outer(위치, `_evGhostTransform`=translate3d만, 드래그 중 트랜지션 없음→손가락 1:1)** + **inner(`.ev-drag-ghost-inner`: 시각 pill·스케일·애니메이션)** 로 분리. 집는 순간 `@keyframes evGhostPop`(작게→오버슈트 1.2→안정 1.08, 스프링 cubic-bezier)로 **튀어나오는 느낌**, 놓는 순간 `_evSettleGhost()`가 `.dropping` 클래스로 목표 칸(또는 원위치)으로 미끄러지며 **축소+페이드로 쏙 들어가는 느낌**. 원본 바(`.ev-drag-src`)도 opacity .28+scale .94로 부드럽게 가라앉음. `prefers-reduced-motion` 존중. 관련: `_evMakeGhost()`, `_evSettleGhost()`, `_evDragFrame()`, `_finish()`(rAF 취소+settle 호출). 그리드 드래그 + 날짜시트 롱프레스 드래그 모두 적용.
17. ✅ **드래그 이동 후 새로고침 깜빡임 제거(낙관적 업데이트)** — 다른 날짜로 옮길 때 `loadEvents()`가 `_events={}`로 비우고 빈 캘린더를 그린 뒤 네트워크로 재로드해서 **전체 새로고침되는 깜빡임**이 있었음. `_evMove`를 낙관적 업데이트로 변경: 로컬 `_events` 맵에서 해당 일정을 od→nd로 즉시 이동(`_evLocalMove`)+`renderMonthly()`로 바로 반영하고, **DB 업데이트는 백그라운드**로. 실패 시에만 `loadEvents()`로 서버 기준 롤백(부분 실패는 성공분을 원복해 그룹 일관성 유지). `_tdMove`(할 일)도 동일 패턴 — todo 객체(=`_todos` 참조) 필드만 바꾸고 `safeRender()`, 실패 시 `prev`로 즉시 원복. 드래그 대상은 실제 DB row(반복·가상 제외)라 반복 인덱스는 무관.
18. ✅ **드래그 중 텍스트 선택 방지** — 일정을 롱프레스로 옮길 때 드래그 시작(220ms 홀드) 전에 iOS가 텍스트 선택을 잡아 파란 선택 핸들이 캘린더에 남던 문제. 기존 `user-select:none`은 `body.ev-dragging`(드래그 시작 후)에만 적용돼 홀드 구간이 무방비였음. `.cal-grid,.cal-grid *`에 `-webkit-user-select:none/user-select:none/-webkit-touch-callout:none`을 **항상** 적용(그리드엔 입력창이 없어 안전).
19. ✅ **멀티방 일정 드래그 이동 전파** — 여러 방에 동시 추가한 일정(`multi_group_id` 공유)을 드래그로 옮기면 **현재 방 복사본만** 이동하던 문제. `_evMove`에서 `anchorRow.multi_group_id`가 있으면, 현재 방 group의 각 distinct 날짜 이동(od→nd)마다 `update({date:nd}).eq('multi_group_id',mgid).eq('user_id',_currentUser.id).eq('date',od)`로 **모든 방의 내 복사본을 한 번에 이동**(단일일·기간·여러날 모두 날짜별로 커버). 사용자 선택으로 **모달 없이 자동 전체 이동**(수정은 기존 `askMultiRoomScope` 모달 유지). 낙관적 로컬 반영은 현재 방만(다른 방은 재진입 시 반영). 그리드 드래그+날짜시트 드래그 둘 다 `_evMove` 공유라 함께 적용. ⚠️ 참고: **수정 경로는 이미 멀티방 지원**(`saveEditedEvent` Case 4 등에서 `multi_group_id` 있으면 `askMultiRoomScope`로 전체/방선택/이방만) — 사용자 요청에 따라 그대로 둠. 편집 진입점은 날짜시트(dpCard)의 '수정' 버튼 하나뿐(`openEditEvent`). `multi_group_id`는 방 간 연결, `range_group_id`=연속 기간(방별 독립), `is_multi`=불연속 여러 날.
20. ✅ **멀티방 할일도 동일 적용** — `todos`도 `multi_group_id`로 방 간 연결(추가 시 `_doSaveTodo`가 현재+다른 방에 stamp). (a) **수정**: 이미 `askMultiRoomScope` 모달 지원(line ~17898) — 그대로 유지. (b) **드래그**(`_tdMove`): `todo.multi_group_id` 있으면 `update(upd).eq('multi_group_id').eq('user_id').eq('start_date',prev.start_date)`로 **모든 방 복사본을 함께 이동**(모달 없이 자동). (c) **삭제**(`deleteTodo`): `multi_group_id` 있으면 이벤트처럼 `askMultiRoomScope(...,true)` 모달(전체/이 방만). ⚠️ **함정**: todos의 `multi_group_id`는 '여러 날' 할일(추가 시 날짜별 개별 `single` 행으로 저장, 같은 mgid 공유)도 묶으므로, 드래그·전체삭제 시 **반드시 `start_date`로도 필터**해야 함(안 그러면 다른 날짜 행까지 같은 날로 뭉개지거나 통째 삭제됨). range/single은 방당 1행이라 start_date 필터로 방별 형제가 정확히 매칭.
21. ✅ **할 일 복사(다른 방으로)** — 일정 복사(`copyEventToRooms`/`_doCopyEvent`)와 동일한 흐름의 `copyTodoToRooms`/`_doCopyTodo` 추가. 할일 상세 액션(날짜시트 dp-actions + 우리할일 카드)에 **복사** 버튼(함께 할일은 제외). `_roomPickMode='copytodo'`로 방 선택 모달 재사용(`confirmRoomPick`/`cancelRoomPick`에 분기 추가). 복사본은 원본과 같은 `multi_group_id`로 묶음(원본에 없으면 새로 부여 후 원본에도 update → 이후 수정/이동/삭제 동기화). 완료상태·member_keys는 초기화. `window.copyTodoToRooms` 노출.
22. ✅ **할일 드래그 게이트 확장(여러날 허용)** — 할일 드래그가 '여러날(multi)' 타입에서 안 되던 문제. `canTdDrag`(`__todoCellHTML`)가 `single`/`range`만 허용해 multi는 `data-tddrag="0"`으로 렌더 → 드래그 시작 자체가 안 됨. 이동 로직(`_tdMove`/`_tdGroupDates`)은 이미 multi 지원하므로 게이트에 `todo_type==='multi'` 추가. 반복 할일은 단일 DB행 이동이 애매해 계속 제외. (단일·비반복 기간 할일은 원래 정상 드래그됨.)
23. 🚨 **[중대] 할일 드래그/복사가 아예 안 되던 근본 원인 = IIFE 스코프 경계** — index.html은 **line 16786 `(function(){ 'use strict'; ... })();` IIFE 안에 할일 시스템 전체**(`_todos`, `safeRender`, `loadTodos`, `__getTodos`, `deleteTodo`, `renderTodoScreen` 등)를 담고 있음. 그런데 드래그/복사 헬퍼(`_tdFindById`·`_tdMove`·`_tdGroupDates`(16262~)·`copyTodoToRooms`·`_doCopyTodo`(10159~))는 **이 IIFE 바깥(메인 스코프)**에 있어서 안쪽 `_todos`·`safeRender`에 접근 불가 → `_tdFindById`가 항상 `null` 반환 → **할일 드래그가 시작조차 안 됨**(이벤트는 `_events`/`_evFindRowById`가 같은 바깥 스코프라 정상). 복사도 `(_todos||[])`가 `[]`라 "할 일을 찾을 수 없어요". **수정**: 바깥 헬퍼들이 `window.__getTodos()`(할일 목록)·`window.safeRender`·`window.loadTodos`(window 노출된 안쪽 함수)로 접근하게 변경. Playwright(headless Chromium)로 실제 렌더+드래그 시뮬레이션해 `_tdFindById` false→true, 드래그 활성화·`_tdMove` 이동까지 검증. ⚠️ **앞으로 할일 관련 함수를 추가할 땐 반드시 16786 IIFE 안쪽에 두거나, 바깥이면 `window.` 노출 함수로 접근할 것.** (`renderMonthly`·`invalidateDayEventsCache`·`shiftDateKey`·`_events`·`supa`·`_currentUser`·`toast`는 바깥 스코프라 직접 호출 OK.)
24. ✅ **기간 할일 전체 하이라이트 + 목록(날짜시트) 드래그** — (a) 기간/여러날 할일을 드래그로 집으면 누른 조각만 활성화되던 문제. `_start`(todo)에서 같은 `data-tdid`의 모든 `.cal-tditem`/`.cal-tdline` 조각에 `ev-drag-src` 부여(이벤트 group 방식과 동일) → 전체 범위가 들림. (b) 일정처럼 **날짜시트 목록(dpList)에서도 꾹 눌러 이동**: 시트 할일 `dp-item`에 `data-tddrag/data-tdid/data-tddate` 부여(비반복 단일·기간·여러날), `setupDateSheetDrag` pointerdown이 할일도 감지, `_beginSheetTdDrag()`(=`_beginSheetEvDrag`의 할일판)가 시트 닫고 `_evDrag`를 `kind:'todo'`로 세팅해 캘린더 드래그로 이어받음. Playwright로 srcBars 전체 하이라이트·`_beginSheetTdDrag` 세팅 검증.
25. ✅ **방목록 순서 편집(드래그 핸들+드래그)** — '내 방' 옆 **'순서 편집' 버튼**(`toggleRoomEdit`, `_roomEditMode`) → 켜면 각 카드에 **드래그 핸들(≡ SVG, `.r-card-grip`)** 이 나타나고(`›` 화살표는 숨김) 드래그로 재정렬, 버튼은 '완료'로 바뀜. ⚠️ **흔들림 애니메이션은 처음엔 iOS식 `@keyframes rWiggle`였으나 사용자가 어지럽다고 해서 제거**하고 핸들+살짝 뜬 그림자(`box-shadow`)로 대체. **편집 모드에서만** 드래그 가능(홀드 불필요—`_roomPD`가 `_roomEditMode` 아니면 무시, `_roomPM`에서 4px 이동 시 즉시 `_roomDragStart`). 순서는 **사용자별**이라 `members.sort_order`(int)에 저장(마이그레이션 `add_members_sort_order`, 기기 간 동기화). `loadRooms`가 `members.sort_order`도 조회해 오름차순(없으면 created_at 최신순 폴백, 안정정렬) 정렬 + 진입 시 `_roomEditMode=false`. `renderRooms` 카드에 `data-roomid`+섹션행에 편집버튼(방≥2)+`room-edit` 클래스 토글. 드래그: 집은 카드 `translateY`(흔들림 정지=`body.room-reordering .r-card{animation:none}`—translate 정상적용 위함), 나머지 `translateY(±h)` 자리양보, 드롭 시 `_applyRoomReorder`→`_myRooms` 재배열+낙관재렌더+`_saveRoomOrder`(sort_order 0..n 백그라운드). 편집 모드 중 카드 click(방입장) 차단+드래그 직후 1회 차단(`_roomSuppressClick`). 편집 모드는 `.room-edit .r-card{touch-action:none}`으로 드래그가 스크롤 대신 동작. 리스너는 `#roomsContent` 컨테이너 위임. ⚠️ 전부 **바깥 스코프**(<16786). Playwright로 편집OFF=드래그무시·흔들림없음, 편집ON=rWiggle+[A,B,C]→[B,C,A]+sort_order 저장 검증.
26. ❌ **D-day/기념일 (방별 공유) — 만들었다가 제거함** — 캘린더 상단 슬림 스트립(`#calDdayBar`)+관리/추가시트로 방별 공유 D-day를 구현했으나(PR #43·#44·#45), 사용자가 "느낌 없음"으로 **전체 삭제 요청**. `index.html`을 D-day 도입 직전 커밋(`9ee82f6`, 코드 25) 상태로 되돌려 클라이언트 코드(`#calDdayBar`, `#ddayListOverlay/Card`, `#ddayEditOverlay/Card`, `_ddays`·`loadDdays`·`renderDdayStrip`·`openDdayEdit`·`renderDdayCal` 등 JS, `.cal-dday-strip`·`.dday-*` CSS) 전부 제거. ⚠️ **주의**: 삭제한 건 '방별 공유 D-day'만이며, **기존 무관 기능은 유지**됨 — 할일 마감 D-day 배지(`.td-dday`/`getDdayClass`/`getDdayLabel`), 캘린더 셀의 공휴일·절기·기념일 라벨, 다음 생일까지 D-day. DB 테이블 `anniversaries`는 빈 채로 남겨둠(위 Supabase 섹션 참고). 재도입 시 git에서 PR #43~#45 diff 복원 가능.
27. ✅ **방 마스코트 (넷플릭스식, 프리미엄)** — 방 목록 씰(첫 글자)을 **방별 고유 캐릭터**로 지정. 사진 업로드가 아니라 **앱 기존 캐릭터팩 재활용**(`svgEmoji`/`CHAR_META`/`CHAR_EXPRESSIONS` — 모찌·곰돌·달콩 각 8표정=24종). **전부 프리미엄 게이팅**: `openRoomMascot()`이 `isEffectivePremium()` 아니면 `showUpgradeModal({feature})`로 페이월. **표시는 방 공유**(멤버 모두 동일, 무료 멤버도 봄 → 전환 유도) — `renderRooms` 씰이 `r.mascot` 있으면 `svgEmoji`, 없으면 첫 글자(`.r-card-seal.has-mascot`). **진입**: 방 설정(`openRoomSettings`) '방 정보'에 '방 마스코트' 행 → 넷플릭스식 선택 시트(`#roomMascotCard`: 큰 미리보기 + 캐릭터별 표정 그리드). 값 `_mascotSel`, 저장은 RPC `set_room_mascot`(방 멤버 검증, `rooms.mascot`에 'gomdol:honey' 저장), 낙관적 `_currentRoom.mascot`+`_myRooms` 갱신 후 `renderRooms` 재렌더. '기본으로 되돌리기'=`clearRoomMascot`(null). 함수 전부 **전역 스코프**(`openRoomSettings` 앞 ~12504, <16786). Playwright로 무료게이트·24종렌더·선택·저장(rpc)·목록씰·기본복원 6종 검증. ⚠️ 캐릭터팩은 원래 프리미엄 스토어 상품이라 방 마스코트도 프리미엄 유지(사용자 결정).
- 저장소 `main`의 `index.html`은 최신 배포본과 동기화됨 (**build 1.1.2 / 코드 27**, 위 1~7번 전부 반영). 단 개발자가 이후 로컬에서 다시 수정·업로드하면 main보다 앞설 수 있으니, 새 세션은 항상 현재 배포/로컬 버전을 확인할 것.
- 옛 버전 기반 브랜치/PR(예: `claude/calendar-ads-free-users-l7jsbt`의 PR #2)은 **머지 금지** — 삭제한 관리자 버튼 부활 + 최신 리팩터 덮어쓰기 위험.
- 배포 버전 확인: 앱 설정 화면 푸터의 `build ...` 문자열(런타임에 JS가 세팅, HTML 정적값과 다를 수 있음).

## 새 세션 체크리스트
1. 이 `CLAUDE.md` 읽기.
2. 작업 대상이 프리미엄/광고면: `my_premium_status()` 정의 + `subscriptions` 조회로 DB 진실 먼저 확인(추측 금지).
3. 클라이언트 코드는 `main`의 `index.html` 기준으로 작업(현재 최신 동기화됨 — 단 개발자 로컬이 더 앞설 수 있으니 확인).
4. 광고 관련이면 네이티브 여부와 `_admobDebug` 값부터 확인.

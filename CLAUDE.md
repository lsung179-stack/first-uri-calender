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
- 관리자 RPC: `admin_users_detailed()` — 회원별 활동지표(SECURITY DEFINER, 관리자만).
- 관리자 RPC: `admin_growth_stats()` — WAU/MAU·활성화율·7일 리텐션·구독전환·공유방 비율(대시보드 성장 섹션).
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
5. ✅ 관리자 버튼 `전체 이용자 프리미엄`(globalTrialBtn)·`프리미엄 개별 관리`(premMgmtBtn) UI 삭제(충돌 우려). 함수/모달 마크업은 잔존(무해).
6. ✅ **7일 무료체험 노출(전환)** — 전 플랜 첫 결제 무료체험이 UI에 안 보이던 문제. `showUpgradeModal`(잠금 안내: "무료로 체험하기"), `openPremiumSheet`/CTA 버튼("7일 무료로 시작하기"), `_updateTrialCta()`(선택 플랜 가격 기준 "첫 7일 무료·이후 자동결제" 캡션 `#premTrialNote`), `renderTrialBanner`("첫 7일 무료")에 반영.
7. ✅ **혼자 방 초대 넛지(바이럴)** — 실멤버 1명인 방 캘린더 상단(`#calInviteNudge`)에 초대 배너 표시. `renderInviteNudge()`가 `_myMembers` 실멤버<2일 때 노출, `shareKakao()` 호출. `renderMemberTabs()` 시작부에서 호출. 배너에 ✕ 닫기 버튼(`dismissInviteNudge`) — 닫으면 그 방 id를 `localStorage['uricalv2.inviteNudgeDismissed']`에 저장해 다시 안 띄움(방별 기억). ✕ 클릭 시 `vConfirm`('다시 보지 않기'/'취소') 확인 후 저장(실수 방지).
8. ✅ **프리미엄 시트 탭바 숨김 버그** — 일정추가(탭바 숨김)→프리미엄 시트 진입 후 닫으면 하단 탭바가 안 돌아오던 문제. `openPremiumSheet`에 `hideTabbar()`, `closePremiumSheet`에 `showTabbar()` 추가(다른 풀스크린 시트와 대칭). 겸사겸사 프리미엄 안내 모달(`showUpgradeModal`의 `vConfirm`) 타이틀의 ✨ 제거.
9. ✅ **관리자 페이지 자동 로그인(세션 전달)** — `openAdminFullPage()`가 admin.html로 세션 토큰(`#at=&rt=`)을 안 실어 보내, 외부 브라우저/새 탭에서 세션이 없어 비번 폼으로 빠지던 버그. 관리자 계정이 **카카오/애플 소셜 로그인(비번 없음, `has_password:false`)**이라 비번 폼으로는 진입 불가였음. `getSession()` 토큰을 URL 해시로 실어 보내 admin이 `setSession`으로 자동 로그인하도록 수정.
10. ✅ **관리자 회원관리 상세화** — DB에 `admin_users_detailed()`(SECURITY DEFINER, 관리자 가드) RPC 추가: 유저별 최근활동(last_sign_in/이벤트/가입 GREATEST)·일정수·방수·구독·푸시토큰 유무. admin.html 회원관리를 **카드 대시보드**로 개편: 상단 KPI 타일(전체·7일/2주 활성·휴면·구독), 필터칩(+구독자), 회원 카드(활동색 아바타 링·이름·최근활동·📅일정·🏠방·가입경로·💎구독/휴면/🔔push 배지), 활동많은순·최근일정순 정렬, KPI에 7일 신규 추가. 카드=좌측 활동색 바+상태라벨(활발/보통/뜸함/휴면)+3칸 그리드(일정/방/최근접속)+'🗓 마지막 일정' 줄(last_event_at). '휴면(30일+ 미활동)'=앱삭제/이탈 추정(정확한 삭제 신호 없음). ※ '최근 활동'은 last_activity(=GREATEST(last_sign_in,last_event,가입)) 기준 — last_sign_in만 쓰면 로그인 유지 유저가 오래전 접속으로 오표시됨. 🔔=fcm_tokens OR push_subscriptions(알림 허용). 필터에 '최근 가입순' 있음.
11. ✅ **관리자 성장 대시보드** — `admin_growth_stats()` RPC + 대시보드에 '성장·리텐션·전환' 섹션(WAU/MAU, 활성화율, 7일 리텐션, 구독 전환율, 공유방 비율). Edge Function `push-cron`/`send-push`로 리텐션 푸시는 이미 가동 중.

## 주의사항 / 함정
- 저장소 `main`의 `index.html`은 최신 배포본과 동기화됨 (**build 1.1.2 / 코드 26**, 위 1~7번 전부 반영). 단 개발자가 이후 로컬에서 다시 수정·업로드하면 main보다 앞설 수 있으니, 새 세션은 항상 현재 배포/로컬 버전을 확인할 것.
- 옛 버전 기반 브랜치/PR(예: `claude/calendar-ads-free-users-l7jsbt`의 PR #2)은 **머지 금지** — 삭제한 관리자 버튼 부활 + 최신 리팩터 덮어쓰기 위험.
- 배포 버전 확인: 앱 설정 화면 푸터의 `build ...` 문자열(런타임에 JS가 세팅, HTML 정적값과 다를 수 있음).

## 새 세션 체크리스트
1. 이 `CLAUDE.md` 읽기.
2. 작업 대상이 프리미엄/광고면: `my_premium_status()` 정의 + `subscriptions` 조회로 DB 진실 먼저 확인(추측 금지).
3. 클라이언트 코드는 `main`의 `index.html` 기준으로 작업(현재 최신 동기화됨 — 단 개발자 로컬이 더 앞설 수 있으니 확인).
4. 광고 관련이면 네이티브 여부와 `_admobDebug` 값부터 확인.

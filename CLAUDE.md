# 우리 캘린더 (Our Calendar) — 프로젝트 인수인계 노트

> 이 파일은 새 세션이 시작될 때 자동으로 읽힙니다. 매번 설명하지 않아도 됩니다.
> 새 대화를 열면 먼저 이 파일 + 관련 코드/DB를 확인한 뒤 작업하세요.
>
> **지금까지 고친 것들의 상세 기록은 [`CLAUDE-LOG.md`](./CLAUDE-LOG.md) 에 있습니다.**
> 이 파일은 "항상 필요한 것"(규칙·구조·현재 상태), 로그 파일은 "찾아볼 때만 필요한 것"(작업 이력)입니다.
> 과거에 같은 곳을 건드린 적이 있는지, 왜 그렇게 돼 있는지 궁금하면 로그 파일을 검색하세요.

## ⚠️ 유지보수 규칙 (Claude에게 — 반드시 지킬 것)
**이 저장소의 파일(특히 `index.html`)이나 DB(RPC/마이그레이션)를 변경할 때마다, 같은 커밋에서 이 `CLAUDE.md`도 갱신한다.**
- 무엇을 바꿨는지 **`CLAUDE-LOG.md` 맨 아래에 새 항목으로 추가**(또는 기존 항목 수정) — 이 파일이 아니라 로그 파일에 쓴다.
- 프리미엄/광고/결제 로직, 함수명, 계정, DB 스키마, 워크플로우가 바뀌면 해당 섹션도 함께 고친다.
- 날짜/버전 기준이 바뀌면 `CLAUDE-LOG.md`의 날짜 표기도 갱신한다.
- 즉 코드 변경과 문서(`CLAUDE.md`/`CLAUDE-LOG.md`)를 **항상 함께 커밋**한다 (문서가 뒤처지지 않게).
- 🎨 **컬러 팩 신규 제작 시 중복 색상 금지** (사용자 지시, 2026-07-22). 새 컬러 팩을 만들 때 팩 안에서든 기존 팩(`STORE_COLORS` 전체)과든 **너무 똑같은(거의 동일한) 색상이 있으면 다르게 조정**할 것 — 제작 전에 기존 전 팩 hex 값과 대조해 사실상 같은 색이면 명도/채도/색조를 바꿔 구분되게 한다.
- 🖼️ **테마 배경씬(bgScene·카드)은 테마마다 완전히 다른 컨셉으로** (사용자 지시, 2026-07-25). 캐릭터 테마 배경을 만들 때 "동물이 뒤돌아 자연 속을 걸어가는" 같은 **똑같은 구도를 재탕 금지** — 각 테마마다 시점·구도·분위기·시간대·활동을 다르게(예: 토끼=달밤 올려다보기, 곰=굴속 낮잠, 원숭이=덩굴 그네, 쥐=땅속집 단면, 닭=새벽 헛간). 아이콘은 이미 테마별 오브젝트가 달라 OK, **배경만 이 규칙 적용**. 여우비만 원조(뒤돌아 걷기) 유지.
- 🧩 **위젯 수정은 iOS와 Android(갤럭시)를 반드시 동시에 수정한다** (사용자 지시, 2026-07-20). 위젯 관련 변경(새 기능·버그수정·UI 조정 등)은 항상 **`widget/ios/UriCalendarWidget.swift`(SwiftUI)** 와 **`widget/android/*`(RemoteViews Java + res)** 둘 다에 반영하고, appstore repo(`www/`·`widget/`)에도 동기한다. 한쪽만 고치면 안 됨. Android는 `/tmp/awstub`로 stub 컴파일 검증, iOS는 오프라인이라 브레이스 균형만 확인.


## 프로젝트 개요
- 한국어 공유 캘린더 앱. **단일 `index.html` SPA**를 Capacitor 8(WebView)로 감싼 구조. 모든 JS/CSS/HTML이 이 한 파일 안에 있음.
- 백엔드: **Supabase** (Auth, Postgres, RPC). 구독 결제: **RevenueCat** (@revenuecat/purchases-capacitor). 광고: **AdMob** (@capacitor-community/admob) — 하단 배너 1개.
- 빌드: **Codemagic**이 매 빌드마다 네이티브 프로젝트 재생성 → 실기기(iOS + Android) 설치.


## 배포 워크플로우 (중요)
- 개발자는 보통 **`index.html`을 GitHub에 직접 업로드**(“Add files via upload”)하는 방식으로 작업. 그래서 **로컬 최신본이 저장소(main)보다 앞서 있을 수 있음** — 새 세션은 반드시 현재 배포/로컬 버전이 무엇인지 확인할 것.
- 클라이언트 코드 변경은 **Codemagic 재빌드** 후에야 기기에 반영됨.
- **DB(RPC/마이그레이션) 변경은 재빌드와 무관하게 즉시 라이브.**
- ⚠️ 광고(AdMob)는 **네이티브 앱에서만** 동작. 웹/PWA에서는 절대 안 뜸(정상). 광고 확인은 반드시 네이티브 앱에서. 앱 설정 화면 하단 푸터에 `네이티브`/`웹` 및 `광고:<_admobDebug>` 문자열이 표시됨.
- 🚦 **[매우 중요] 배포 게이트 = `deploy_version` (WebView/index.html 변경이 실제 사용자에게 보이려면 이걸 올려야 함)**: 이 앱은 서비스워커(`sw.js`) + **제어된 배포(gated rollout)** 를 씀. index.html을 새로 빌드/번들해도, **Supabase `app_config` 테이블의 `deploy_version`(값=`'YYYY-MM-DD HH:MM:SS'` KST) 타임스탬프를 올리지 않으면 일반 사용자는 옛 SW 캐시 index.html을 계속 봄**(=옛날 파일 적용됨 증상). 동작: 앱 시작 시 `checkDeployVersionOnStart()`+SW 발견 시 `_checkAndApplySWUpdate()`가 서버 `deploy_version` vs 로컬 `localStorage['uricalv2.deployVersion']` 비교 → **다르면** 캐시 비우고 새 SW 활성화+새로고침, **같으면** 옛 버전 유지. ⚠️ **관리자(`isAdmin()`, naver 계정)는 게이트 우회**해 항상 최신 → "관리자는 최신인데 일반계정은 옛날" 증상의 원인. **배포 방법 2가지**: (a) 앱 관리자 메뉴 **'배포하기' 버튼**(`openDeploySheet` → `deploy_version`=현재 KST 시각 저장), (b) SQL 직접: `update app_config set value=to_char(now() at time zone 'Asia/Seoul','YYYY-MM-DD HH24:MI:SS'), updated_at=now() where key='deploy_version';`. ⚠️ **위젯(네이티브 RemoteViews/WidgetKit)은 이 게이트와 무관** — 위젯 변경은 AAB/IPA 재빌드+설치로만 반영(deploy_version 불필요). ⚠️ **2026-08-11 `deploy_version`을 `2026-08-11 18:30:22`(KST)로 재갱신함**(iOS 네이티브 푸시 플랫폼별 플러그인 분기 수정 직후, 사용자가 바로 확인하려 재갱신 — JS 전용이라 IPA 재빌드 없이 SW 업데이트로 즉시 반영됨) — 이 시점까지 누적된 index.html 변경(방 프로필 리셋 3종·씰-테마 동시저장 버그·일정 수정/복사/삭제 방 멤버 전체 허용·마케팅 버전 1.1.7 승격·월헤더/공휴일라벨 큰글씨 대응·반복 종료일[초안]+리디자인+드롭다운 재교체+커스텀 드롭다운+상자 기준 앵커링·시간 피커 시작/종료 라벨+키보드 동시노출 방지·iOS 네이티브 푸시 라우팅[부분수정]+플랫폼별 플러그인 분기 등)이 PWA/WebView/네이티브 앱 사용자에게 즉시 반영됨. ⚠️ **단, iOS 푸시 실제 전달은 GoogleService-Info.plist 커밋 + 다음 iOS 빌드(IPA) 전까지는 여전히 안 됨**(JS 라우팅만으론 부족, 위 항목 참고). 네이티브 위젯 자체(RemoteViews/WidgetKit UI)는 이 게이트와 무관 — AAB/IPA 재빌드+설치 별도 필요.

- **⚠️⚠️⚠️ [상시 규칙] 빌드 반영 필수 = appstore 동기화**: Codemagic은 이 repo가 아니라 **`lsung179-stack/appstore`의 `www/index.html`** 을 번들해 iOS·Android를 빌드함 — 이 repo `index.html`을 고쳐도 appstore www에 복사 안 하면 **빌드에 절대 안 나옴**(2026-07-21 이모지 변경이 빌드에 안 뜨는 사고 실제 발생). **사용자 지시(2026-07-21): 앞으로 이 repo `index.html`을 변경할 때마다 반드시 appstore도 함께 동기화**. 방법: `first-uri-calender/index.html` → `appstore/www/index.html` 전체 복사 후 **appstore의 `main` + `claude/ios-widget` 두 브랜치 모두 commit+push**(add_repo로 appstore 추가, `/workspace/appstore`). 빌드번호 변경 시 appstore `codemagic.yaml`의 iOS floor(`NEXT -lt N`)·Android floor(`VC -lt N`)도 함께 수정. 2026-07-21 build32 동기화 완료(appstore main 8897eb1, ios-widget 84710bd).
- 옛 버전 기반 브랜치/PR(예: `claude/calendar-ads-free-users-l7jsbt`의 PR #2)은 **머지 금지** — 삭제한 관리자 버튼 부활 + 최신 리팩터 덮어쓰기 위험.
- 배포 버전 확인: 앱 설정 화면 푸터의 `build ...` 문자열(런타임에 JS가 세팅, HTML 정적값과 다를 수 있음).

## 현재 상태 (2026-09-24 기준)
- **마케팅 버전 `1.2.2` / 앱 내 표시 빌드 `1.2.2 (84)`** — index.html buildInfo 두 곳(정적 `id="buildInfo"` + 런타임 `const _base='build …'`)에 같은 문자열이 박혀 있다. appstore `codemagic.yaml`(두 브랜치)의 `APP_VERSION`·`ANDROID_VERSION_NAME` 도 `1.2.2`, 빌드번호 하한은 iOS `41`·Android `86`.
- 🪙 **1.2.2 = 코인 첫 출시 빌드** — App Store Connect '제출 초안'에 소모품 `coins_30/100/300` 3개가 담겨 있어 **이 앱 버전을 같은 초안에 추가해 함께 심사 제출**해야 한다(첫 소모품 규칙). ✅ 2026-09-24 양 스토어 제출 완료, `coin_public`='true' 켬.
- ⚠️ **표시 빌드번호(83)와 실제 스토어 번호는 별개** — 스토어 번호는 codemagic 이 자동 산정한다(iOS=TestFlight 최신+1, Android=`50+BUILD_NUMBER`). 하한은 "이보다 작아지지 않게" 하는 바닥일 뿐.
- **마지막 실제 스토어 출시는 `1.1.8`** — 출시노트 범위 기준선은 이 버전이다(그 이후 누적분을 씀).
- 🚨 **iOS 앱스토어 앱은 `deploy_version` 으로 갱신되지 않는다** — `capacitor.config.json` 에 `server.url` 이 없어 **IPA 안에 번들된 `www/index.html` 을 그대로 실행**한다. 서비스워커·캐시 비우기·`location.reload()` 모두 같은 번들 파일을 다시 읽을 뿐이다. 즉 `deploy_version` 은 **PWA·브라우저·Android TWA 전용**이고, iOS 앱에 반영하려면 **IPA 재빌드+배포가 유일한 방법**이다(확인법: 그 기기 설정 화면 푸터의 `build …` 문자열).
- ⚠️ 관리자 계정은 `checkDeployVersionOnStart` 가 `isAdmin()` 이면 즉시 리턴해 **배포 게이트를 우회**한다(새로고침만으로 최신). "관리자는 최신인데 일반 계정은 옛날" 증상의 원인.
- 🌙 **테마 '별밤 캠핑'(`camp`, 2026-09-23 출시)은 앱 유일의 어두운 팔레트**다 — 크림/흰색을 하드코딩한 새 UI 면을 만들면 `body[data-theme="camp"]` 보정 블록(아이콘 CSS 바로 아래)에도 추가해야 글씨가 안 사라진다. '심플 블랙&화이트'(`mono`)는 같은 날 실수로 내렸다가 **2026-09-25 복귀**(사용자가 지우라던 건 mono 출시 배너였음 — 배너는 계속 없음, 테마는 판매 중).
- 🚀 **네이티브(Flutter) 전환 준비 중** — 설계는 [`docs/NATIVE-MIGRATION.md`](./docs/NATIVE-MIGRATION.md). 바꾸면 안 되는 값(iOS `com.lsung.uricalendar`, **Android 패키지 `app.vercel.first_uri_calender.twa`**, App Group, 상품·광고 ID, 앱 링크, 위젯 데이터 키)과 1단계 '다리 역할 업데이트'가 정리돼 있다. **다리 업데이트는 구현 완료(1.2.3 에 포함)** — `index.html` `_migrSaveSession`(Preferences `migr.session`)·`_migrSavePrefs`(`migr.prefs`)·`_checkMinAppVersion`(`app_config.min_app_version`/`min_app_version_force`, 기본 '0'/'false'). 결정: 새 저장소 `uri-calendar-app`, 2.0 목표 2027년 1월 중순, 네이티브 광고 첫 위치 = **설정 탭 목록 중간('꾸미기'와 '기타' 사이, 사용자 지정)** — 설정 화면에선 하단 배너 숨김, 2.0부터, 앱 오프닝 광고 1차 제외. 전환 기간 `index.html` 은 버그 수정 위주.
- 로컬 `/home/user/appstore` 체크아웃은 origin 보다 뒤처져 있을 수 있다(지금도 그렇다) — 작업 전 `git fetch` 로 원격 기준을 확인할 것.

## Supabase
- 프로젝트 id: **`bgqzkkaslqchbovzrkao`**
- 핵심 테이블: `public.subscriptions` (user_id, status, expires_at, plan_type, is_manual)
- `public.members` (room_id, user_id, color, notifications_enabled, role, is_virtual, virtual_*, **`sort_order` int**=방목록 사용자별 순서, **`seal` text**=개인별 방 프로필 'color:sym', 마이그레이션 `add_members_seal`), **`palette` text[]**=이 방에서 내 일정·할 일 색상 칸에 보일 색 묶음('default'=기본 12색, 그 외=컬러 팩 key, null=전부 — `_myPaletteByRoom`/`_colorPagesForRoom`, 마이그레이션 `add_members_palette`). RLS `members_update_self`(user_id=auth.uid())로 본인 행 수정 가능.
- `public.rooms` (id, name, code, owner_id, ~~`mascot` text~~). ⚠️ **방 프로필은 2026-07에 방 공유(rooms.mascot)→개인별(members.seal)로 전환** — `rooms.mascot` 컬럼과 RPC `set_room_mascot`은 **미사용 잔존**(마이그레이션 `add_room_mascot`). 클라이언트는 members.seal만 읽고 씀.
- ~~`public.anniversaries`~~ — **방별 공유 D-day/기념일 기능은 제거됨**(사용자 요청, "느낌 없음"). 클라이언트 코드(스트립/시트/JS/CSS) 전부 삭제. 단 DB 테이블 `anniversaries`(마이그레이션 `create_anniversaries`)는 **그대로 남아있음**(빈 테이블, 미사용). 나중에 재도입하거나 정리할 때 참고.
- `public.announcements` (title, body, image_url, show_on_open, **`blocks` jsonb**=글·사진 순서 `[{t:'text',v}|{t:'img',u}]`, 사진 있을 때만 — 2026-09-25 블로그형 공지. 옛 앱 호환 위해 body(글만)·image_url(첫 사진)도 함께 저장, 마이그레이션 `add_announcements_blocks`).
- 관리자 RPC(코인·구매 탭, 2026-09-26): `admin_coin_stats()`·`admin_coin_ledger_page(kind,search,limit,offset)`·`admin_user_coin_detail(uid)` — `admin.html` '🪙 코인·구매' 탭. 새 품목을 팔면 `admin.html` `ITEM_NAME`, 충전 가격이 바뀌면 `COIN_PACK.won` 도 갱신.
- 🔒 **보안 규칙(2026-09-26 점검)**: 관리자 함수의 관리자 확인은 반드시 `auth.uid() is distinct from '<관리자 id>'`(`<>` 는 로그아웃 상태에서 통과됨) + `revoke execute … from public, anon`. 방 데이터 읽기는 방 멤버만(`*_select_v2` using true 는 삭제됨). 방 입장 insert 는 `lookup_room_by_code` 로 코드를 먼저 조회한 사용자만 가능(`app_private.room_join_grants`, 24시간) — 새 입장 경로를 만들면 같은 순서를 지킬 것. 상세는 CLAUDE-LOG.md 2026-09-26 보안 항목.
- 관리자 RPC: `admin_users_detailed()` — 회원별 활동지표(SECURITY DEFINER, 관리자만).
- 관리자 RPC: `admin_growth_stats()` — WAU/MAU·활성화율·7일 리텐션·구독전환·공유방 비율 + **초대 지표**(invite_joins_7d/30d, invite_conv_num/den=30일 신규 중 가입 7일 내 타인 방 입장, cancel_pending=해지예약)(대시보드 성장 섹션).
- 관리자 RPC: `admin_ads_cohorts()` — 최근 8주 주간 가입 코호트: 가입→7일 생존(일정·last_sign_in 기준, 7일 경과분만 분모)→초대 합류→체험(TRIAL)→유료(NORMAL)→해지예약. 체험/유료 판별=subscriptions.period_type, SANDBOX 제외(마이그레이션 `add_invite_metrics_and_ads_cohorts`).
- Edge Functions: `push-cron`(mode: daily/reminder/empty-rooms/**birthday**, **v14**), `send-push`(**v8**, body `kind` 선택 파라미터), `revenuecat-webhook`. **⚠️ push-cron v14(2026-07-25, 사용자 "복사한 일정 알림 중복 방지")**: 미리알림·데일리 다이제스트가 각 `events` 행 소유자(`ev.user_id`)에게 발송되는데, 일정을 여러 방에 복사하면 같은 `multi_group_id` 행이 방마다 생겨 복사한 사람에게 **N번 중복 알림**이 가던 문제 → `handleReminder`/`handleDaily`에서 **유저별 `multi_group_id` 1회만 발송**(Set dedup). reminder는 `reminder_sent_at`을 복사본 전체(`targets`)에 찍어 스킵된 복사본이 다음 실행에 재알림 안 되게, 응답에 `reminded`(실발송 수) 추가. 런타임 검증: reminder 200 OK. multi_group_id 없는 일반 일정은 기존대로 개별 발송(무영향). pg_cron: daily 08:00·empty-rooms 08:05·**birthday 09:00(KST=0 0 UTC)**·reminder 5분마다·오래된 notifications 정리. 생일=`profiles.birthday`('MM-DD') 매칭, 같은 방 멤버+본인에게 축하 푸시. **알림 카테고리 설정**: `profiles.notif_prefs` jsonb(마이그레이션 `add_profiles_notif_prefs`) — send-push는 kind로, push-cron은 모드별 키(daily/nudge/reminder/birthday)로 false인 수신자 제외(코드 33 참고).
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
- **주간 보기(2026-09-26)**: `setCalView('month'|'week')`(기억 `uricalv2.calView`), `renderWeekList`/`_calApplyView`/`_wkEnsure`, 주 시작 = `getWeekStartPref()`(위젯 payload `weekStart` 도 같은 값). 할 일 완료한 사람 = `todos.done_by` → `_todoDoneByChipHtml`(멤버 2명 이상 방만). 위젯: 새 큰 위젯 '이번 주'(iOS `UriWeek`, Android `WeekWidgetProvider`).
- 광고: `showAdBannerIfNeeded()`, `hideAdBanner()`, `_anySubSheetOpen()`(열린 시트 있으면 광고 숨김), `_elVisible(el)`(실제 보이는 요소만 카운트 — 잔여 `.on` 오판 방지), `_admobDebug`(설정 화면 푸터에 노출).
- **코인(2026-09-23 도입, 2026-09-24 공개 — `coin_public`='true', 1.2.2 심사 제출과 함께)**: 잔액=`coin_ledger` 합계(`my_coin_balance()`), 가격=`shop_items`, 충전상품=`coin_packs`(`coins_30/100/300`, **스토어 가격 확정 C안 2026-09-24: 30코인 ₩1,500 · 110코인(100+10) ₩3,900 · 350코인(300+50) ₩9,900** — 중간 묶음=월 구독료와 같게 맞춰 코인이 구독을 깎아먹지 않게. 가격은 스토어에서만 정하고 앱은 스토어 가격 문자열을 그대로 표시), 구매=RPC **`buy_item(type,key)` 하나로만**(서버가 잔액·차감·`user_unlocks` 소유를 한 트랜잭션에서). 소유 판정 `_ownsItem(type,key)`(= `_dbUnlocks.skin/color/char`), 품목 타입 `skin`(30)·`color`/`char`/`sticker`(20)·`font`/`dh`(10). 게이트: 테마 **`_canUseSkin(k)`**, 날짜 강조 진입 **`_canDh()`**, 스티커 **`_canSticker()`** (모두 구독 OR 산 것) — 새 유료 게이트를 만들 땐 `isEffectivePremium()` 만 보지 말고 소유도 볼 것. 코인 아이콘 = **현행 앱 로고를 금 테두리 동전으로 옮긴 것**(크림 면 + 벽돌색 이중 링 + 'OUR' + 점 밑줄; `.coin-ico` 작은 판 / `.coin-ico-lg`·`-xl` 큰 판, 원본 `brand/coin*.svg`). **앱 로고는 현행 유지**(2026-09-23 재디자인 시안 전부 불채택). **스티커엔 공짜 묶음이 없다** — 다이컷 4묶음(`campnight`/`sky`/`celebrate`/`daily`, `STICKER_BASE_PACKS`)과 캐릭터 묶음 모두 구독 또는 코인 구매. **구독이 끝나면 붙인 스티커는 숨김**(코인으로 산 묶음 것만 보임, `_rsVisible` — 데이터는 지우지 않아 재구독하면 그대로 복원, 2026-09-25). 잠긴 버튼 `_lockedBtnHTML`, 구매 제안 `offerItem`, 충전 시트 `openCoinShop`. **공개 스위치 = `app_config.coin_public`**(`'true'`면 모두에게, 아니면 관리자만 — `_coinVisible()` = `_coinPublic || isAdmin()`). 충전 적립은 웹훅이 service_role RPC **`credit_coin_purchase`**/**`refund_coin_purchase`**(거래 id 로 멱등)로 한다 — 웹훅 v3 소스는 `supabase/functions/revenuecat-webhook/index.ts`(비밀값은 시크릿 `REVENUECAT_WEBHOOK_SECRET` 에서만). ⚠️ 켜기 전 필수: (1) App Store Connect·Play Console 에 소모성 상품 `coins_30/100/300` 등록 + RevenueCat 에 추가, (2) ✅ 2026-09-24 시크릿 등록 + 웹훅 코인 버전 배포 완료(Supabase 버전 4, 코드 내 기본 비밀값 제거) · 스토어 상품 3종 App Store Connect(제출 초안에 담김, 새 앱 버전과 함께 심사)·Play(활성)·RevenueCat(Consumable, Entitlement 미연결) 등록 완료. ✅ 1.2.2 양 스토어 제출 + `coin_public`='true'(2026-09-24 15:14 UTC). 남은 것: 출시 후 실기기 결제·광고 보상 1회씩 확인. 관리자 테스트 코인: `select admin_grant_coins('<uid>',100,'메모');`.
- **보상형 광고 → 코인(2026-09-24)**: 코인 충전 시트의 '광고 보고 코인 받기'(`onWatchRewardAd`, 코인 공개·광고 단위 설정 시 보임 — **구독자에게도 보인다**(2026-09-24 사용자 지시: 스스로 누르는 광고라 '광고 제거'와 무관, 모은 코인은 구독 끝난 뒤 사용). 배너는 구독자에게 계속 숨김). **앱은 코인을 절대 직접 주지 않는다** — `prepareRewardVideoAd({ssv:{userId}})` → 끝까지 보면 구글이 Edge Function **`admob-ssv`**(verify_jwt=false, 구글 공개키로 ECDSA 서명 확인)로 GET → service_role RPC **`credit_ad_reward`**(ref=`admob:<transaction_id>` 멱등, **KST 하루 상한**) → `coin_ledger` kind **`'ad'`**. 앱은 잔액이 늘 때까지 확인만. 표시용 `my_ad_reward_status()`. 설정은 전부 `app_config`(재빌드 불필요): `admob_rewarded_ios`(`…/2472669914`)/`admob_rewarded_android`(`…/3775063169`)(비어 있으면 버튼 숨김), `ad_reward_coins`(1), `ad_reward_daily_cap`(10). ⚠️ iOS 플러그인은 보상 없이 닫으면 `showRewardVideoAd` Promise 를 끝내지 않으므로 결과는 리스너(`onRewardedVideoAdDismissed`/`Reward`/`FailedToShow`)로 받는다. **미리 받기·재시도(2026-09-25)**: 코인 충전 창을 열 때·한 편 끝난 뒤 `_preloadReward()` 로 다음 광고를 받아 두고(50분·같은 계정일 때만 사용), 없으면 누를 때 최대 3번(0·2·5초, 한 번 15초 제한) 받는다. 불러오기 실패 이유는 `onRewardedVideoAdFailedToLoad` 로 받아 `audit_log`(`ad_reward_load_fail`)·`_admobDebug` 에 남기고 관리자 토스트에 `[코드 메시지]` 표시(코드 3 = no fill). **설정 탭 네이티브 광고(2.0 전용)**: 광고 단위 `app_config.admob_native_ios`(`…/2271992382`)·`admob_native_android`(`…/1135004686`)(2026-09-25, '네이티브 광고 고급형', 서드파티 미디에이션 체크 안 함) — 지금 웹뷰 앱은 읽지 않는다(목록 사이 광고 불가), Flutter 2.0 이 '스토어' 행 아래·'기타' 위에 쓴다(명세 06 끝 절). 배너는 iOS 도 `ADAPTIVE_BANNER`.


## 📝 출시 자료 자동 작성 규칙 (사용자 지시 2026-07-25, 2026-08-22 개정)
- **사용자가 "빌드한다/빌드할게/새 빌드" 등 빌드 의사를 밝히면, 요청 없이도 자동으로 아래 5종을 함께 작성해 준다** (한국어, 사용자 대상 문구, 기술용어 금지):
  1. **출시명(릴리스명)**: `버전 — 한 줄 요약` (예: `1.1.8 — 반복 일정 대개편 & 날짜 강조`)
  2. **프로모션 텍스트**(App Store, ≤170자): 앱 핵심 가치 + 이번 하이라이트 한 문장.
  3. **이 버전에서 업그레이드된 사항**: **큰 항목만 간결하게**(사용자 지시 2026-08-22 "너무 자세히는 안 해도 돼"). 카테고리 5~7개 × 한 줄씩, 세부 나열 금지.
  4. **출시노트/What's New**(양 스토어 공용): **이모지 없이**(사용자 지시 2026-09-24 "이모지는 빼고 작성해줘") 짧은 문장 불릿 3~6개, 사용자 체감 변경만. **Play Store는 언어당 500자 제한이라 반드시 500자 이내**.
  5. **앱 내 공지**: 앱 공지사항에 올릴 글. 친근한 인사말 + 달라진 점 몇 줄 + 맺음말. **짧게**(길게 늘어놓지 말 것).
- **범위 = 직전 빌드 이후 누적된 사용자 체감 변경**(2026-08-22 사용자 지시로 '마지막 실제 출시 이후'에서 변경). 직전 빌드가 언제·어느 버전이었는지는 이 문서의 버전 승격 항목(buildInfo 숫자)으로 판단한다.
- `CLAUDE-LOG.md` 에서 **사용자 눈에 보이는 것만** 골라 씀(내부 리팩터·성능 세부·deploy_version·검증 방법 등은 제외하거나 "속도 개선/안정성 향상"으로 뭉뚱그림).
- ⚠️ 위젯 관련 변경은 "위젯도 함께 좋아졌어요"처럼 별도 묶음으로 — 위젯은 재빌드가 있어야 반영되므로 이번 빌드의 핵심 셀링포인트가 되기 쉽다.
- **영문판도 함께 작성**(사용자 지시 2026-08-22) — App Store 에 올라가는 것만: 출시명 / Promotional Text(≤170자) / What's New. 앱 내 공지·Play 전용 문구는 한국어만. 번역투 말고 영어권에서 자연스럽게 읽히는 문장으로.
- 작성한 자료는 채팅으로 전달(파일 저장 안 함). 매 빌드마다 새로 씀.
- 🔢 **[상시] 빌드할 때마다 마케팅 버전을 하나씩 올린다** (사용자 지시 2026-09-11 "빌드할때마다 한개씩 계속 올려줘") — 재빌드든 새 기능이든 구분 없이 **마지막 자리 +1**(1.2.1 → 1.2.2 → …). 함께 올릴 것: appstore `codemagic.yaml` **두 브랜치**의 `APP_VERSION`·`ANDROID_VERSION_NAME` 기본값, 빌드번호 하한(iOS `NEXT -lt N`·Android `VC -lt N`) 각 +1, index.html buildInfo **두 곳**(정적·런타임)의 `build X.Y.Z (N)` — (N)도 +1. 출시명·영문 Release name에도 새 버전을 쓴다.

## 새 세션 체크리스트
1. 이 `CLAUDE.md` 읽기. 손대려는 기능에 과거 이력이 있는지는 `grep "키워드" CLAUDE-LOG.md` 로 찾는다(로그는 736KB라 통째로 읽지 말 것).
2. 작업 대상이 프리미엄/광고면: `my_premium_status()` 정의 + `subscriptions` 조회로 DB 진실 먼저 확인(추측 금지).
3. 클라이언트 코드는 `main`의 `index.html` 기준으로 작업(현재 최신 동기화됨 — 단 개발자 로컬이 더 앞설 수 있으니 확인).
4. 광고 관련이면 네이티브 여부와 `_admobDebug` 값부터 확인.

# 우리 캘린더 — 네이티브(Flutter) 전환 설계

> 작성 2026-09-24 · 상태: **결정 완료(11장) · 1단계 다리 업데이트 구현됨 — 1.2.3 빌드로 출시 예정**
> 이 문서는 전환 작업의 기준선이다. 결정이 바뀌면 이 문서를 먼저 고친다.

## 0. 한 줄 요약

지금의 "index.html 하나를 웹뷰로 감싼 앱"을 **Flutter 앱 하나(iOS·Android 공용)** 로 다시 만든다.
서버(Supabase·RevenueCat·AdMob)는 그대로 쓰고, **같은 앱 ID로 업데이트 출시**해서 계정·일정·구독·코인·리뷰를 그대로 잇는다.

- **목표**: 광고 수익 한도 올리기(네이티브 광고·미디에이션·앱 오프닝 광고), 앱 체감 품질, 이후 기능 확장성.
- **하지 않는 것**: 서버 구조 변경, 기능 재설계(1차는 "지금 앱과 똑같이"), 웹/PWA 버전 유지(사용자 0명).

## 1. 지금 앱의 규모 (2026-09-24 기준)

| 항목 | 수량 |
|---|---|
| `index.html` | 약 29,000줄 · 함수 약 1,240개 |
| 화면 | 8개 (시작·이메일·인증번호·비밀번호 재설정·방 목록·캘린더·우리 할 일·설정) + 사진 갤러리 |
| 시트/팝업 | 약 50개 |
| DB 테이블(앱에서 직접 사용) | 22개 — `events`(101곳), `todos`(40), `members`(25), `profiles`(18), `date_highlights`(14) 등 |
| RPC | `my_premium_status` `my_coin_balance` `buy_item` `my_ad_reward_status` `lookup_room_by_code` `delete_shared_event_rows` `delete_my_user_data` |
| Edge Function(앱이 부름) | `send-push` (나머지는 서버끼리: `push-cron` `revenuecat-webhook` `admob-ssv`) |
| Storage 버킷 | `event-photos`, `calendar-pdfs`, 공개 `theme-assets`(bg·ic·camp 이미지 약 460개) |
| 네이티브 기능 | AdMob · RevenueCat · 푸시(FCM) · Apple 로그인 · 앱 링크 · 브라우저 · Preferences · 위젯 브리지 |
| 위젯 | iOS SwiftUI 1파일 · Android RemoteViews Java 12파일 — **이미 네이티브, 그대로 재사용** |

관리자 대시보드는 이미 별도 `admin.html`(웹)로 있어서 **옮기지 않는다.**

## 2. 기술 선택 — Flutter

| 역할 | 지금 | Flutter |
|---|---|---|
| 서버 | supabase-js | `supabase_flutter` (공식) |
| 구독·코인 결제 | @revenuecat/purchases-capacitor | `purchases_flutter` (공식) |
| 광고 | @capacitor-community/admob | `google_mobile_ads` (구글 공식 — 네이티브 광고·앱 오프닝·미디에이션 지원) |
| 푸시 | FCM + @capacitor/push-notifications | `firebase_messaging` |
| 로그인 | Supabase OAuth(Apple·Google·Kakao) + 이메일/인증번호 | `sign_in_with_apple` · Supabase OAuth(브라우저) · 이메일 동일 |
| 기기 저장 | localStorage · Preferences | `shared_preferences` (+ 민감값은 `flutter_secure_storage`) |
| 위젯 데이터 | WidgetsBridgePlugin | `home_widget` 또는 플랫폼 채널로 **같은 키에 기록** |
| 음력 | JS 음력 라이브러리 | Dart 음력 변환(후보 조사 필요) 또는 서버 계산 |
| 공휴일 | date.nager.at | 동일 |

Swift/Kotlin 두 벌 대신 Flutter를 고른 이유: 한 벌로 두 플랫폼, 구글 공식 광고 SDK, Supabase·RevenueCat 공식 SDK.

## 3. 기존 사용자를 그대로 넘기기 — 바꾸면 안 되는 것

| 항목 | 값 | 어기면 |
|---|---|---|
| iOS 번들 ID | `com.lsung.uricalendar` | 새 앱으로 올라감(업데이트 아님) |
| **Android 패키지명** | **`app.vercel.first_uri_calender.twa`** (codemagic 이 `com.lsung.uricalendar` 를 이 값으로 바꿔 빌드 중) | 새 앱으로 올라감 |
| Android 서명 | 지금 업로드 키(Play App Signing) | 업데이트 거부 |
| iOS App Group | `group.com.lsung.uricalendar` | 위젯이 데이터를 못 읽음 |
| Supabase 프로젝트 | `bgqzkkaslqchbovzrkao` (RLS·RPC 그대로) | 데이터 없음 |
| RevenueCat 사용자 ID | Supabase `user.id` | 구독·코인 연결 끊김 |
| 상품 ID | `premium_1m/3m/12m`, `coins_30/100/300` | 결제 안 됨 |
| AdMob 앱·광고 단위 | iOS `~4146324211`, Android `~5315935656`, 보상형 `…/2472669914`·`…/3775063169` | 광고·보상 코인 끊김 |
| 앱 링크 | `uricalendar://add`, `join?code=`, `open?room=&date=`, `login-callback` · 초대 웹 링크 `r.html?join=` | 초대·위젯 탭·로그인 복귀 깨짐 |
| 위젯 데이터 형식 | Android `CapacitorStorage` SharedPreferences(`roomId`·`filterUser` 등) / iOS App Group UserDefaults | 위젯 빈 화면 |
| 푸시 토큰 | `fcm_tokens` 테이블 형식 | 푸시 안 옴 |

## 4. 1단계 — "다리 역할 업데이트" (지금 앱의 마지막 웹뷰 빌드) — ✅ 구현됨(2026-09-24, 1.2.3 에 포함)

Flutter 앱은 웹뷰의 localStorage 를 읽을 수 없다. 그래서 **전환 전에 지금 앱이 필요한 값을 네이티브 저장소로 복사해 두는 업데이트**를 먼저 낸다. 코드는 `index.html` 의 `_migrSaveSession` / `_migrSavePrefs` / `_checkMinAppVersion`(로그인 상태 처리 바로 아래).

| 키 | 내용 | 언제 기록 |
|---|---|---|
| `migr.session` | `{refresh_token, user_id, at}` | 로그인·토큰 갱신·첫 세션 확인마다, 로그아웃이면 삭제 |
| `migr.prefs` | `{at, items:{uricalv2.* · lastRoomId · agreedTerms}}` (`uricalv2.deployVersion`·세션 토큰 제외) | 앱 시작 2.5초 후 · 백그라운드로 갈 때 |

**저장 위치(Flutter 가 읽는 법)** — `@capacitor/preferences` 기본 그룹:
- iOS: `UserDefaults.standard` 의 키 **`CapacitorStorage.migr.session`** / **`CapacitorStorage.migr.prefs`** (앱 전용, 위젯 App Group 아님)
- Android: SharedPreferences 파일 **`CapacitorStorage`** 의 키 `migr.session` / `migr.prefs` (앱 전용 — 위젯도 같은 파일을 읽지만 같은 앱 안이라 밖으로 새지 않음)
- Flutter 의 `shared_preferences` 는 `flutter.` 접두어·다른 파일을 쓰므로 **플랫폼 채널(또는 파일명 지정 가능한 SharedPreferencesAsync)로 위 위치를 직접 읽는다.**
- 2.0 첫 실행 순서: `migr.session` 읽기 → `supabase.auth.setSession(refresh_token)`(=refresh) → 성공하면 두 키 삭제. 실패하면 로그인 화면(데이터는 서버에 있어 손실 없음).

**최소 지원 버전 스위치** — `app_config.min_app_version`(기본 `'0'`) · `min_app_version_force`(기본 `'false'`).
- 네이티브 앱이 시작할 때 자기 버전(`#buildInfo` 의 `build X.Y.Z`)이 이보다 낮으면 "업데이트 안내"(나중에 가능). force=`'true'` 면 어느 버튼이든 스토어로 보내고 다시 안내.
- 스토어 링크: iOS `https://apps.apple.com/app/id6773996160`, Android `…details?id=app.vercel.first_uri_calender.twa`.
- 로그인 전(RLS 로 app_config 못 읽음)에는 검사하지 않는다.
- 2.0 출시 후 옛 웹뷰 앱 정리: 먼저 force 없이 `min_app_version='2.0.0'` → 몇 주 뒤 force.

이 업데이트는 **2.0 출시 몇 주 전**에 나가야 대부분 사용자가 받아 둔다. 못 받은 사람은 2.0 에서 한 번 다시 로그인하면 된다.

## 5. 기능 목록과 순서

**원칙: 돈을 낸 것은 첫날부터 그대로 보여야 한다.** 구독 혜택과 코인으로 산 항목(테마·컬러·이모지·스티커·강조·글꼴)의 *표시*는 1차에 반드시 넣는다. 만드는 도구(편집기)는 2차로 미뤄도 된다.

### 1차 (2.0 출시에 필수)
- 로그인: 이메일(비밀번호·인증번호·재설정), Apple, Google, 카카오 · 약관 동의 · 계정 삭제
- 방: 목록·순서·만들기·코드/링크로 참여·방 설정·멤버·가상 멤버·방 프로필(씰)
- 캘린더: 월 보기, 일정 바(하루·기간·여러 날), 반복(매일/주/월/년/음력·예외·종료일), 공휴일·음력 표시, 오늘 표시
- 일정: 추가/수정/삭제/복사(다른 방으로)·함께 일정(나만/멤버 모두)·반복 '이 일정만/이후/전체'·시간·메모·색·방별 색
- 날짜 시트·할 일(우리 할 일·날짜 할 일)·검색
- 알림: 푸시 받기·카테고리 설정·알림 목록
- 결제: 구독(구매·복원·서버 판정 `my_premium_status` 그대로), 코인(잔액·충전·`buy_item`·보상형 광고)
- 광고: 배너(적응형) · 보상형 · **네이티브 광고(신규)** · 앱 오프닝(신규, 선택)
- **꾸미기 표시**: 테마 세트(카드·배경씬·아이콘) · 별밤 캠핑(어두운 팔레트) · 컬러 팩 · 이모지 반응 · 스티커 · 날짜 강조 · 글꼴
- 스토어(구매·사용하기) · 설정 · 공지 · 고객센터
- 위젯 데이터 기록(지금과 같은 키)

### 2차 (2.x)
- 나만의 테마 만들기(사진·스티커 편집기)
- 일정 사진·사진 갤러리
- 끌어서 복사/이동 + 화면 끝 달 넘기기
- 내보내기/공유, 달력 인쇄 주문(`calendar_orders`·PDF)

### 옮기지 않음
- 관리자 대시보드·관리자 전용 버튼(웹 `admin.html` 로 유지)
- 서비스워커·`deploy_version` 배포 게이트(네이티브에선 의미 없음 → `min_app_version` 으로 대체)
- 웹 푸시(VAPID)

## 6. 광고 설계 (전환의 핵심 목적)

| 광고 | 위치 | 원칙 |
|---|---|---|
| 배너(적응형) | 지금처럼 하단 | 구독자 없음 |
| 보상형 | 코인 충전 화면 | 지금 서버(SSV·하루 상한) 그대로, 구독자도 볼 수 있음 |
| **네이티브 광고** | **첫 위치: 설정 탭 목록 중간 — '꾸미기' 묶음과 '기타' 묶음 사이 1칸** (사용자 지정 2026-09-24). 이후 후보: 날짜 시트 목록 아래 · 할 일 목록 중간 · 방 목록 맨 아래 | 목록 카드와 같은 모양으로 섞되 "광고" 표시 명확히 · 한 화면 1개 · 구독자 없음 · **설정 화면에선 하단 배너를 숨겨 광고 2개가 겹치지 않게** · 프리미엄/구매 버튼과 바로 붙이지 않음(오클릭 방지 — 그래서 '프리미엄' 묶음 옆이 아닌 '꾸미기'와 '기타' 사이) |
| 앱 오프닝 | 앱을 켤 때 | 하루 1회 · 가입 3일 이내 없음 · 구독자 없음 |
| 미디에이션 | AdMob 미디에이션에 다른 광고 회사 추가 | 단가 경쟁 — 후보 조사 후 결정 |

- iOS 추적 동의(ATT)·개인정보 동의 흐름은 지금과 같게.
- 광고를 공격적으로 늘리면 이탈이 더 손해 — **네이티브 광고 1곳(설정 탭)부터** 시작해 지표를 보고 늘린다.
- 지금 웹뷰 앱에서는 목록 사이에 광고를 끼울 수 없다(광고가 화면 위에 떠 있는 방식이라 스크롤을 못 따라감) — **2.0(Flutter)부터 적용**.

## 7. 서버 쪽 변경

거의 없다.
- 추가: `app_config.min_app_version`.
- 그대로: 모든 테이블·RLS·RPC·`send-push`·`push-cron`·`revenuecat-webhook`·`admob-ssv`.
- 정리(2.0 안정 후): `deploy_version`·웹 푸시 구독(`push_subscriptions`) 사용 중단.

## 8. 저장소·빌드

- **새 저장소 `uri-calendar-app`** 에서 Flutter 앱을 만든다. 위젯 Swift/Java 는 `appstore/widget/` 에서 옮겨 온다.
- Codemagic 에 Flutter 워크플로를 새로 만든다. 서명·번들 ID·패키지명·빌드번호 하한은 지금 설정을 그대로 옮긴다(빌드번호는 지금보다 커야 함).
- 버전은 **2.0.0** 으로 시작.
- 전환 기간 지금 앱(`index.html`)은 **버그 수정만** 한다.

## 9. 진행 순서와 대략 일정

| 단계 | 내용 | 기간(대략) |
|---|---|---|
| 0 | 다리 역할 업데이트(1.2.3) 출시 — ✅ 코드 완료, 빌드 대기 | 1주 |
| 1 | Flutter 뼈대: 로그인·세션 이전·방·월 캘린더·일정 CRUD | 3~4주 |
| 2 | 반복·기간·함께 일정·할 일·푸시·구독·코인·광고 | 3~4주 |
| 3 | 꾸미기 표시(테마·팩·스티커·강조·글꼴)·스토어·설정·위젯 데이터 | 2~3주 |
| 4 | 내부 테스트(TestFlight·Play 내부 테스트) — 지금 앱과 나란히 비교 | 2주 |
| 5 | 2.0 출시(업데이트) → 2차 기능 2.x | — |

## 10. 위험과 대응

| 위험 | 대응 |
|---|---|
| 반복·기간·음력 계산이 지금과 달라짐 | 지금 앱의 검증 스크립트 시나리오(반복 수정·저장 잠금·드래그 등)를 Dart 테스트로 옮겨 같은 결과인지 비교 |
| 세션 이전 실패로 로그아웃 | 실패해도 재로그인만 하면 됨(데이터 서버 보관) · 다리 업데이트를 충분히 먼저 배포 |
| 산 항목이 안 보임 | "돈 낸 것 첫날부터" 원칙 — 꾸미기 표시를 1차에 포함 |
| 위젯 깨짐 | 데이터 키·App Group 그대로, 위젯 코드는 재사용 |
| 두 벌 유지 부담 | 전환 기간 지금 앱은 버그 수정만 |
| 심사 거절 | 결제·광고·추적 동의 흐름을 지금과 동일하게 |

## 11. 결정 (2026-09-24, 사용자 "니가 추천하는 걸로 정해서 진행해줘")

| 항목 | 결정 | 이유 |
|---|---|---|
| 새 저장소 | **`uri-calendar-app`** (Flutter 개발 시작할 때 생성) | 지금 두 저장소(`first-uri-calender`·`appstore`)와 구분되고 뜻이 바로 보임 |
| 2.0 출시 목표 | **2027년 1월 중순** (다리 업데이트 1.2.3 은 10월 초) | 개발 8~11주 + 테스트 2주. 연말 심사 지연 기간(12월 말)을 피함 |
| 네이티브 광고 첫 위치 | **설정 탭 목록 중간('꾸미기'와 '기타' 사이) 1칸** — 사용자 지정(처음 추천이던 날짜 시트 아래에서 변경) | 일정 보기·입력 흐름을 전혀 방해하지 않음. 설정 화면에선 하단 배너 대신 이 광고. 지표를 보고 날짜 시트·할 일 목록으로 확장 |
| 앱 오프닝 광고 | **1차 제외** → 2.0 안정 후 지표 보고 결정 | 켤 때마다 광고는 이탈 위험이 가장 큼. 사용자 규모가 작은 지금은 유지율이 더 중요 |
| 2차로 미루는 기능 | **5장 목록대로 동의** | '돈 낸 것은 첫날부터' 원칙을 지키면서 1차 범위를 줄임 |

# 우리 캘린더 — 네이티브(Flutter) 전환 설계

> 작성 2026-09-24 · 상태: **초안(결정 대기 항목 있음, 맨 아래)**
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

## 4. 1단계 — "다리 역할 업데이트" (지금 앱의 마지막 웹뷰 빌드)

Flutter 앱은 웹뷰의 localStorage 를 읽을 수 없다. 그래서 **전환 전에 지금 앱이 필요한 값을 네이티브 저장소로 복사해 두는 업데이트**를 먼저 낸다.

1. **로그인 유지**: supabase-js 세션(localStorage `sb-bgqzkkaslqchbovzrkao-auth-token`)의 `refresh_token`·`user.id` 를 Preferences(`migr.session`)에 기록. 로그인·토큰 갱신·로그아웃 때마다 갱신, 로그아웃이면 지움.
   Flutter 첫 실행 → 이 값으로 `setSession`/`refreshSession` → 성공하면 `migr.session` 삭제. 실패하면 그냥 로그인 화면(데이터는 서버에 있으니 손실 없음).
2. **기기 설정**: `uricalv2.*` 설정(글꼴·크기·주 시작·기본 보기·격자·정렬·마지막 방·읽은 공지·온보딩 여부 등)을 JSON 하나(`migr.prefs`)로 Preferences 에 기록. Flutter 가 한 번 읽어 자기 저장소로 옮긴다.
3. **최소 지원 버전 스위치**: `app_config.min_app_version` 을 새로 두고, 웹뷰 앱이 이보다 낮으면 "업데이트해 주세요" 안내를 띄우게 한다(2.0 이후 옛 앱 정리용).
4. 이 업데이트는 **2.0 출시 몇 주 전**에 나가야 대부분 사용자가 받아 둔다. 못 받은 사람은 2.0 에서 한 번 다시 로그인하면 된다.

> ⚠️ `refresh_token` 은 민감값 — 기록 위치는 앱 전용 저장소(iOS UserDefaults 앱 영역·Android 앱 전용 SharedPreferences)로 한정하고, **위젯과 공유하는 App Group/CapacitorStorage 에는 쓰지 않는다**(구현 때 Preferences 그룹 분리 확인).

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
| **네이티브 광고** | 후보: 날짜 시트 일정 목록 아래 · 할 일 목록 중간 · 방 목록 맨 아래 | 목록에 섞되 "광고" 표시 명확히 · 한 화면 1개 · 구독자 없음 |
| 앱 오프닝 | 앱을 켤 때 | 하루 1회 · 가입 3일 이내 없음 · 구독자 없음 |
| 미디에이션 | AdMob 미디에이션에 다른 광고 회사 추가 | 단가 경쟁 — 후보 조사 후 결정 |

- iOS 추적 동의(ATT)·개인정보 동의 흐름은 지금과 같게.
- 광고를 공격적으로 늘리면 이탈이 더 손해 — **네이티브 광고 1곳부터** 시작해 지표를 보고 늘린다.

## 7. 서버 쪽 변경

거의 없다.
- 추가: `app_config.min_app_version`.
- 그대로: 모든 테이블·RLS·RPC·`send-push`·`push-cron`·`revenuecat-webhook`·`admob-ssv`.
- 정리(2.0 안정 후): `deploy_version`·웹 푸시 구독(`push_subscriptions`) 사용 중단.

## 8. 저장소·빌드

- **새 저장소**에서 Flutter 앱을 만든다(이름 결정 필요). 위젯 Swift/Java 는 `appstore/widget/` 에서 옮겨 온다.
- Codemagic 에 Flutter 워크플로를 새로 만든다. 서명·번들 ID·패키지명·빌드번호 하한은 지금 설정을 그대로 옮긴다(빌드번호는 지금보다 커야 함).
- 버전은 **2.0.0** 으로 시작.
- 전환 기간 지금 앱(`index.html`)은 **버그 수정만** 한다.

## 9. 진행 순서와 대략 일정

| 단계 | 내용 | 기간(대략) |
|---|---|---|
| 0 | 다리 역할 업데이트(1.2.x) 출시 | 1주 |
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

## 11. 결정이 필요한 것

1. 새 저장소 이름(예: `uri-calendar-app`)
2. 2.0 출시 목표 시점
3. 네이티브 광고 첫 위치(날짜 시트 아래 / 할 일 목록 / 방 목록 중 하나)
4. 앱 오프닝 광고를 1차에 넣을지
5. 2차로 미루는 기능 목록(위 5장) 동의 여부

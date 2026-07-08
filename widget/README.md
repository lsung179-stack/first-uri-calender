# 우리 캘린더 — iOS 홈 화면 위젯 (도입 준비)

> 상태: **스캐폴드 완료, 미도입.** 반응 보고 도입 결정 시 아래 체크리스트대로 연결하면 바로 나감.
> 이 폴더의 파일들은 앱/웹 동작에 아무 영향 없음 (참조되지 않는 대기 코드).

## 구성

| 파일 | 역할 |
|---|---|
| `ios/UriCalendarWidget.swift` | WidgetKit 익스텐션 전체 (small=오늘 일정, medium=오늘+다가오는 3일, 빈티지 크림/테라 테마) |
| `ios/Info.plist` | 익스텐션 Info.plist |
| `ios/add_widget_target.rb` | **CI 주입 스크립트** — Codemagic이 네이티브 프로젝트를 매번 재생성하므로, cap sync 후 이 스크립트가 위젯 타깃·App Group entitlement를 Xcode 프로젝트에 자동 추가 |
| `bridge.js` | index.html에 이식할 데이터 브릿지 — 7일치 일정을 App Group UserDefaults(`widget.events`)에 JSON으로 기록 |

## 데이터 흐름

```
index.html (일정 로드/변경)
  → syncWidgetData()                         [bridge.js, 500ms 디바운스]
  → Capacitor Preferences (group: group.com.lsung.uricalendar)
  → App Group UserDefaults "widget.events"
  → WidgetKit 타임라인이 읽어서 렌더 (자정 자동 롤오버 + 앱 트리거 갱신)
```

## 도입 체크리스트

### 1. Apple Developer (1회, 개발자 계정에서)
- [ ] Identifiers → App Groups → **`group.com.lsung.uricalendar`** 생성
- [ ] 기존 앱 ID(`com.lsung.uricalendar`)에 App Groups capability 추가 + 위 그룹 연결
- [ ] 새 App ID **`com.lsung.uricalendar.widget`** 생성 (App Groups capability + 같은 그룹)
- [ ] 위젯용 배포 프로비저닝 프로파일 생성 → Codemagic 서명에 등록
  (Codemagic 자동 서명(App Store Connect API 키) 사용 중이면 프로파일은 자동 — App ID/그룹만 만들면 됨)

### 2. Codemagic 워크플로 (빌드 스크립트 수정)
`npx cap sync ios`(또는 add) **다음**, xcodebuild/archive **이전**에 추가:
```bash
npm i @capacitor/preferences            # 브릿지가 쓰는 플러그인 (미설치 시)
gem install xcodeproj --no-document
ruby widget/ios/add_widget_target.rb ios/App/App.xcodeproj
npx cap sync ios                        # 플러그인 새로 깔았으면 한 번 더
```
- ⚠️ 실제 워크플로 yaml/스크립트를 보고 정확한 위치에 끼워야 함 — 도입 시점에 Codemagic 워크플로 내용 공유 필요.

### 3. index.html 이식
- [ ] `bridge.js` 내용을 index.html에 병합 (전역 스코프, 16786 IIFE 바깥)
- [ ] TODO 4곳 연결: 일별 일정 조회 함수(반복 포함), 색상 hex 맵, 호출 지점(loadEvents 완료·일정 저장·삭제·enterRoom)
- [ ] (선택) `capacitor-widgetsbridge-plugin` 설치하면 일정 변경 즉시 위젯 갱신 — 없어도 자정 갱신은 동작

### 4. 검증
- [ ] TestFlight/실기기: 홈 화면 길게 눌러 위젯 추가 → '우리 캘린더' 노출 확인
- [ ] 일정 추가 → 위젯 반영 (즉시 or 자정)
- [ ] 로그아웃/방 없음 상태에서 위젯이 "오늘 일정이 없어요"로 안전하게 표시되는지

## 설계 메모
- **왜 CI 주입 스크립트인가**: Codemagic이 매 빌드 네이티브 프로젝트를 재생성하므로 Xcode에서 수동으로 타깃을 추가해도 다음 빌드에 사라짐. Ruby(xcodeproj)로 매 빌드 재주입하는 것이 유일하게 지속 가능한 방식.
- 위젯 딥링크(탭하면 해당 날짜로)는 2차 — `widgetURL`에 `com.lsung.uricalendar://open?date=` 붙이면 기존 딥링크 핸들러(index.html ~7286)에 케이스 하나 추가로 가능.
- Android 위젯(Glance)은 TWA 구조라 별도 네이티브 래퍼 작업이 커서 보류. iOS 반응 보고 결정.

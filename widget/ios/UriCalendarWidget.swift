// 우리 캘린더 — iOS 홈 화면 위젯 (WidgetKit)
// ⚠️ 빌드 전 코드: 이 환경에선 컴파일/실행 불가. 다음 빌드(Xcode/Codemagic) 때 검증.
// 데이터: 앱(bridge.js)이 App Group UserDefaults(group.com.lsung.uricalendar)의
//   "widget.data" 키에 기록한 JSON을 읽는다. 스키마는 widget/bridge.js 주석 참조.
// 위젯 4종: 오늘(small) · 2주 캘린더(medium) · 콤보/월(large, 편집 전환).
// 공통: 방 선택(AppIntent) + 멤버 필터 + ＋추가(딥링크) + 할일 체크(AppIntent).

import WidgetKit
import SwiftUI
import AppIntents
import UIKit

// MARK: - 데이터 모델 (bridge.js 스키마)

struct WGData: Codable {
    let updatedAt: Double?
    let currentRoomId: String?
    var myUserId: String?          // 현재 로그인 사용자 — 작은 위젯 '내 일정만' 기본값
    var gridV: Bool? = nil         // 세로 격자선(앱 설정 연동)
    var gridH: Bool? = nil         // 가로 격자선
    var holidays: [String:String]? = nil   // 빨간날(공휴일) 'YYYY-MM-DD'→이름 — 날짜 빨간색 + 빨간 일정바
    let rooms: [WGRoom]
}
struct WGRoom: Codable, Identifiable {
    let id: String
    let name: String
    let seal: String?
    var sealPng: String?          // 앱이 구운 씰 PNG(dataURL) — 없으면 색+이니셜 폴백
    let members: [WGMember]
    let events: [WGEvent]
    let todos: [WGTodo]
    var hls: [WGHighlight]? = nil     // 날짜 강조(앱 date_highlights) — 앱에서 테마 해석까지 끝낸 결과만 옴
}
/* 날짜 강조 한 건. 앱이 테마표(DH_THEMES)를 해석해 render/색/라벨여부까지 계산해 넘기므로
   위젯은 이 값 그대로 그리기만 한다(테마가 늘어도 위젯 수정 불필요). [2026-08-19]
   r: border | fill | deep | folder | flower | dashed */
struct WGHighlight: Codable {
    let id: String; let t: String; let r: String; let c: String
    let ink: String; let lb: Bool; let dk: Bool; let s: String; let e: String
}
// 그 날짜에 걸린 강조(겹치면 시작일이 이른 것 하나) — 앱 _dhForDate와 동일 규칙
func hlFor(_ room: WGRoom, _ key: String) -> WGHighlight? {
    guard let list = room.hls else { return nil }
    var best: WGHighlight? = nil
    for h in list where key >= h.s && key <= h.e {
        if best == nil || h.s < best!.s { best = h }
    }
    return best
}
// 어느 변에 테두리를 그릴지 — 위/아래/좌/우 이웃 날짜가 범위 밖이면 그 변이 바깥 경계.
// 앱 _dhSideFlags와 동일(위젯은 항상 일요일 시작이라 col = dow).
func hlSides(_ h: WGHighlight, _ key: String, _ dow: Int) -> (top: Bool, bottom: Bool, left: Bool, right: Bool) {
    func inR(_ k: String) -> Bool { return k >= h.s && k <= h.e }
    func shift(_ k: String, _ n: Int) -> String {
        guard let d = parse(k), let m = Calendar.current.date(byAdding: .day, value: n, to: d) else { return "" }
        return fmt(m)
    }
    return (top: !inR(shift(key, -7)), bottom: !inR(shift(key, 7)),
            left: dow == 0 || !inR(shift(key, -1)), right: dow == 6 || !inR(shift(key, 1)))
}
// 채움형(fill/deep)인지 — 날짜 숫자 뒤에 색을 깔고, 진한 색이면 숫자를 흰 글자로
func hlIsFill(_ h: WGHighlight) -> Bool { return h.r == "fill" || h.r == "deep" }
// dataURL(base64 PNG) → UIImage
func decodeDataURLImage(_ s: String?) -> UIImage? {
    guard let s = s, let comma = s.firstIndex(of: ",") else { return nil }
    let b64 = String(s[s.index(after: comma)...])
    guard let d = Data(base64Encoded: b64) else { return nil }
    return UIImage(data: d)
}
struct WGMember: Codable { let userId: String?; let name: String; let color: String; var avatarPng: String? = nil }
// 함께 일정(멤버별 복사본) 중복 제거 — 같은 날+제목+시간 = 하나로
func dedupeEvents(_ evs: [WGEvent]) -> [WGEvent] {
    var seen = Set<String>(); var out: [WGEvent] = []
    for e in evs { let k = e.date + "|" + e.title + "|" + e.time; if !seen.contains(k) { seen.insert(k); out.append(e) } }
    return out
}
struct WGEvent: Codable { let date: String; let title: String; let time: String; let color: String; let userId: String?; var gid: String? = nil; var style: String = "solid"; var ord: Int = 0; var shared: Bool = false }
struct WGTodo: Codable, Identifiable { let id: String; let date: String; let title: String; let time: String; let color: String; let done: Bool; var userId: String? = nil; var memberKeys: [String]? = nil }
// 멤버 필터 기준 할일 표시 여부 — 앱 _todoVisibleForView와 동일.
// 전체(nil)=모두, 함께 할일(memberKeys)=그 멤버 포함 시, 혼자 할일=작성자(userId) 일치 시.
func todoVisibleFor(_ t: WGTodo, _ filter: String?) -> Bool {
    guard let f = filter else { return true }
    if let mk = t.memberKeys, !mk.isEmpty { return mk.contains(f) }
    if f.hasPrefix("v-") { return false }
    return t.userId == f
}

let APP_GROUP = "group.com.lsung.uricalendar"
let DATA_KEY = "widget.data"

func loadWGData() -> WGData? {
    guard let ud = UserDefaults(suiteName: APP_GROUP),
          let raw = ud.string(forKey: DATA_KEY),
          let d = raw.data(using: .utf8) else { return nil }
    return try? JSONDecoder().decode(WGData.self, from: d)
}
// 선택된 방(없으면 currentRoom, 그것도 없으면 첫 방)
func pickRoom(_ data: WGData?, roomId: String?) -> WGRoom? {
    guard let data = data else { return nil }
    if let rid = roomId, let r = data.rooms.first(where: { $0.id == rid }) { return r }
    if let cur = data.currentRoomId, let r = data.rooms.first(where: { $0.id == cur }) { return r }
    return data.rooms.first
}

// MARK: - 팔레트 (앱 빈티지 테마)

extension Color {
    init(hexStr: String) {
        var s = hexStr.trimmingCharacters(in: CharacterSet.alphanumerics.inverted)
        if s.count == 3 { s = s.map { "\($0)\($0)" }.joined() }
        let v = UInt64(s, radix: 16) ?? 0x8b3a2a
        self.init(.sRGB, red: Double((v >> 16) & 0xff)/255, green: Double((v >> 8) & 0xff)/255, blue: Double(v & 0xff)/255)
    }
    static let cream = Color(hexStr: "#f6efe0")
    static let ink = Color(hexStr: "#2a1c0f")
    static let terra = Color(hexStr: "#8b3a2a")
    static let mutedBrown = Color(hexStr: "#8a6c52")
    static let sunRed = Color(hexStr: "#c0503f")
}
// 배경색 명도에 따라 가독성 있는 텍스트 색(앱 getContrastTextColor와 동일: 가중 luminance>0.62면 어두운 글자).
func contrastText(_ hex: String) -> Color {
    var s = hex.trimmingCharacters(in: CharacterSet.alphanumerics.inverted)
    if s.count == 3 { s = s.map { "\($0)\($0)" }.joined() }
    guard s.count >= 6, let v = UInt64(s.prefix(6), radix: 16) else { return .white }
    let r = Double((v >> 16) & 0xff), g = Double((v >> 8) & 0xff), b = Double(v & 0xff)
    let lum = (0.299*r + 0.587*g + 0.114*b) / 255.0
    return lum > 0.62 ? Color(hexStr: "#3a2418") : .white
}

// MARK: - 설정 인텐트 (방 선택 + 멤버 필터 + 큰 위젯 레이아웃)

struct RoomEntity: AppEntity {
    let id: String
    let name: String
    static var typeDisplayRepresentation: TypeDisplayRepresentation = "방"
    var displayRepresentation: DisplayRepresentation { DisplayRepresentation(title: "\(name)") }
    static var defaultQuery = RoomQuery()
}
struct RoomQuery: EntityQuery {
    func entities(for identifiers: [String]) async throws -> [RoomEntity] {
        (loadWGData()?.rooms ?? []).filter { identifiers.contains($0.id) }.map { RoomEntity(id: $0.id, name: $0.name) }
    }
    func suggestedEntities() async throws -> [RoomEntity] {
        (loadWGData()?.rooms ?? []).map { RoomEntity(id: $0.id, name: $0.name) }
    }
    func defaultResult() async -> RoomEntity? {
        let d = loadWGData()
        if let cur = d?.currentRoomId, let r = d?.rooms.first(where: { $0.id == cur }) { return RoomEntity(id: r.id, name: r.name) }
        if let r = d?.rooms.first { return RoomEntity(id: r.id, name: r.name) }
        return nil
    }
}
// 멤버 필터 = 드롭다운(직접 입력 X). id="" 는 '전체 보기'.
struct MemberEntity: AppEntity {
    let id: String     // userId ("" = 전체)
    let name: String
    static var typeDisplayRepresentation: TypeDisplayRepresentation = "멤버"
    var displayRepresentation: DisplayRepresentation { DisplayRepresentation(title: "\(name)") }
    static var defaultQuery = MemberQuery()
}
struct MemberQuery: EntityQuery {
    // 위젯 편집에서 선택한 '방'에 의존 → 그 방 멤버만 후보로 뜨게(다른 방 멤버 노출 방지)
    @IntentParameterDependency<CalConfigIntent>(\.$room)
    var config
    func entities(for identifiers: [String]) async throws -> [MemberEntity] {
        membersFor(config?.room.id).filter { identifiers.contains($0.id) }
    }
    func suggestedEntities() async throws -> [MemberEntity] { membersFor(config?.room.id) }
    func defaultResult() async -> MemberEntity? { MemberEntity(id: "", name: "전체 보기") }
    // 선택된 방(없으면 현재/첫 방)의 실제 멤버(가상 제외)만 userId 중복 제거해 나열. 맨 앞에 '전체 보기'.
    private func membersFor(_ roomId: String?) -> [MemberEntity] {
        let d = loadWGData()
        let rid = roomId ?? d?.currentRoomId ?? d?.rooms.first?.id
        var out: [MemberEntity] = [MemberEntity(id: "", name: "전체 보기")]
        var seen = Set<String>()
        if let room = d?.rooms.first(where: { $0.id == rid }) {
            for m in room.members {
                guard let uid = m.userId, !uid.isEmpty, !seen.contains(uid) else { continue }
                seen.insert(uid)
                out.append(MemberEntity(id: uid, name: m.name))
            }
        }
        return out
    }
}
struct CalConfigIntent: WidgetConfigurationIntent {
    static var title: LocalizedStringResource = "우리 캘린더 위젯"
    static var description = IntentDescription("볼 방과 멤버를 선택하세요. (프로필을 눌러도 멤버별로 볼 수 있어요)")
    @Parameter(title: "방") var room: RoomEntity?
    // 멤버 필터: '전체 보기'(기본) 또는 특정 멤버 선택. 드롭다운(직접 입력 아님).
    @Parameter(title: "멤버") var member: MemberEntity?
}

// MARK: - 멤버 필터 탭 인텐트 (프로필 눌러서 그 사람 일정만 보기)
// App Group에 'widget.memberFilter'를 기록 → 타임라인이 이걸 우선 적용. ""=전체.
let FILTER_KEY = "widget.memberFilter"
let ROOM_KEY = "widget.roomOverride"
struct SetFilterIntent: AppIntent {
    static var title: LocalizedStringResource = "멤버별 보기 전환"
    @Parameter(title: "userId") var userId: String
    init() {}
    init(userId: String) { self.userId = userId }
    func perform() async throws -> some IntentResult {
        UserDefaults(suiteName: APP_GROUP)?.set(userId, forKey: FILTER_KEY)
        return .result()
    }
}
// 새로고침 — 위젯 타임라인 다시 로드(App Group 최신 데이터 재반영 + 오늘 재계산)
struct RefreshIntent: AppIntent {
    static var title: LocalizedStringResource = "새로고침"
    func perform() async throws -> some IntentResult {
        // 새로고침 = 데이터 갱신 + '오늘'로 복귀(월/2주/콤보 오프셋 모두 0으로).
        // removeObject로 확실히 0(기본값) 상태로 만들고 synchronize로 즉시 반영 후 리로드.
        let ud = UserDefaults(suiteName: APP_GROUP)
        ud?.removeObject(forKey: MONTH_KEY)
        ud?.removeObject(forKey: WEEK_KEY)
        ud?.removeObject(forKey: COMBO_MONTH_KEY)
        ud?.set(Date().timeIntervalSince1970, forKey: FLASH_KEY)   // 깜빡임 트리거
        ud?.synchronize()
        WidgetCenter.shared.reloadAllTimelines()
        return .result()
    }
}
// 월 위젯 이전/다음달 이동 (< >) — App Group에 개월 오프셋 저장
let MONTH_KEY = "widget.monthOffset"
func readMonthOffset() -> Int { UserDefaults(suiteName: APP_GROUP)?.integer(forKey: MONTH_KEY) ?? 0 }
struct ShiftMonthIntent: AppIntent {
    static var title: LocalizedStringResource = "달 이동"
    @Parameter(title: "delta") var delta: Int
    init() {}
    init(delta: Int) { self.delta = delta }
    func perform() async throws -> some IntentResult {
        let ud = UserDefaults(suiteName: APP_GROUP)
        ud?.set((ud?.integer(forKey: MONTH_KEY) ?? 0) + delta, forKey: MONTH_KEY)
        return .result()
    }
}
// 2주 위젯 이전/다음 2주 이동 (< >) — App Group에 '2주 페이지' 오프셋 저장(1페이지=14일)
let WEEK_KEY = "widget.weekOffset"
func readWeekOffset() -> Int { UserDefaults(suiteName: APP_GROUP)?.integer(forKey: WEEK_KEY) ?? 0 }
struct ShiftWeekIntent: AppIntent {
    static var title: LocalizedStringResource = "2주 이동"
    @Parameter(title: "delta") var delta: Int
    init() {}
    init(delta: Int) { self.delta = delta }
    func perform() async throws -> some IntentResult {
        let ud = UserDefaults(suiteName: APP_GROUP)
        ud?.set((ud?.integer(forKey: WEEK_KEY) ?? 0) + delta, forKey: WEEK_KEY)
        return .result()
    }
}
// 콤보(다가오는 일정) 위젯의 미니 달력 이전/다음달 이동 — 월 위젯과 독립된 별도 오프셋
let COMBO_MONTH_KEY = "widget.comboMonthOffset"
func readComboMonthOffset() -> Int { UserDefaults(suiteName: APP_GROUP)?.integer(forKey: COMBO_MONTH_KEY) ?? 0 }
struct ShiftComboMonthIntent: AppIntent {
    static var title: LocalizedStringResource = "미니 달력 달 이동"
    @Parameter(title: "delta") var delta: Int
    init() {}
    init(delta: Int) { self.delta = delta }
    func perform() async throws -> some IntentResult {
        let ud = UserDefaults(suiteName: APP_GROUP)
        ud?.set((ud?.integer(forKey: COMBO_MONTH_KEY) ?? 0) + delta, forKey: COMBO_MONTH_KEY)
        return .result()
    }
}
// 방 프로필(씰) 탭 → 다음 방으로 순환 전환 (방이 여러 개일 때)
struct CycleRoomIntent: AppIntent {
    static var title: LocalizedStringResource = "방 전환"
    func perform() async throws -> some IntentResult {
        guard let ud = UserDefaults(suiteName: APP_GROUP) else { return .result() }
        let rooms = loadWGData()?.rooms ?? []
        guard rooms.count > 1 else { return .result() }
        let cur = ud.string(forKey: ROOM_KEY) ?? loadWGData()?.currentRoomId
        let idx = rooms.firstIndex(where: { $0.id == cur }) ?? 0
        let next = rooms[(idx + 1) % rooms.count]
        ud.set(next.id, forKey: ROOM_KEY)
        ud.set("", forKey: FILTER_KEY)   // 방 바뀌면 멤버 필터 전체로 초기화
        return .result()
    }
}

// MARK: - 할일 체크 인텐트 (위젯에서 바로 완료 — App Group 대기열 기록, 앱이 flush)

struct ToggleTodoIntent: AppIntent {
    static var title: LocalizedStringResource = "할 일 완료 전환"
    @Parameter(title: "todoId") var todoId: String
    init() {}
    init(todoId: String) { self.todoId = todoId }
    func perform() async throws -> some IntentResult {
        guard let ud = UserDefaults(suiteName: APP_GROUP) else { return .result() }
        // 대기열에 토글 기록만 함(앱이 다음 포그라운드에 Supabase 반영). 위젯 표시는
        // todoDone()이 이 대기열 홀짝으로 즉시 반영하므로 widget.data를 직접 안 건드림
        // (전체 JSON 재인코딩은 대용량 씰/사진 PNG까지 다시 써야 해서 취약 → 제거).
        var pending = ud.stringArray(forKey: "widget.pendingTodoToggles") ?? []
        pending.append(todoId)
        ud.set(pending, forKey: "widget.pendingTodoToggles")
        return .result()
    }
}
// 위젯 표시용 완료 상태 = 서버 done XOR 대기열 홀짝(위젯에서 방금 누른 토글 즉시 반영).
func todoDone(_ t: WGTodo) -> Bool {
    let pending = UserDefaults(suiteName: APP_GROUP)?.stringArray(forKey: "widget.pendingTodoToggles") ?? []
    let odd = pending.filter { $0 == t.id }.count % 2 == 1
    return odd ? !t.done : t.done
}

// MARK: - 타임라인

struct CalEntry: TimelineEntry {
    let date: Date
    let room: WGRoom?
    let memberFilter: String?   // userId or nil(전체)
    var myUserId: String? = nil // 현재 사용자 (작은 위젯 기본 필터)
    var gridV: Bool = false     // 세로/가로 격자선(앱 설정)
    var gridH: Bool = false
    var holidays: [String:String] = [:]   // 빨간날(공휴일)
    var flash: Bool = false      // 새로고침 직후 잠깐 흐려짐(깜빡임 피드백)
}
// 새로고침 깜빡임 타임스탬프(App Group). 최근이면 타임라인이 flash 엔트리를 잠깐 넣음.
let FLASH_KEY = "widget.flashRefresh"
struct CalProvider: AppIntentTimelineProvider {
    func placeholder(in context: Context) -> CalEntry {
        CalEntry(date: Date(), room: sampleRoom(), memberFilter: nil, myUserId: "u1")
    }
    func snapshot(for configuration: CalConfigIntent, in context: Context) async -> CalEntry {
        let room = effectiveRoom(configuration.room?.id) ?? sampleRoom()
        let d = loadWGData()
        return CalEntry(date: Date(), room: room, memberFilter: effectiveFilter(configuration.member), myUserId: d?.myUserId, gridV: d?.gridV ?? false, gridH: d?.gridH ?? false, holidays: d?.holidays ?? [:])
    }
    func timeline(for configuration: CalConfigIntent, in context: Context) async -> Timeline<CalEntry> {
        let room = effectiveRoom(configuration.room?.id)
        let d = loadWGData()
        let filter = effectiveFilter(configuration.member)
        func mk(_ date: Date, _ flash: Bool) -> CalEntry {
            CalEntry(date: date, room: room, memberFilter: filter, myUserId: d?.myUserId, gridV: d?.gridV ?? false, gridH: d?.gridH ?? false, holidays: d?.holidays ?? [:], flash: flash)
        }
        // 자정에 '오늘'이 넘어가므로 자정 직후 갱신 예약(그 외는 앱이 reloadAllTimelines)
        let mid = Calendar.current.startOfDay(for: Calendar.current.date(byAdding: .day, value: 1, to: Date())!)
        // 새로고침 직후(0.8s 이내)면 잠깐 흐려졌다(flash) 복귀하는 엔트리 2개 → 깜빡임 피드백
        let flashAt = UserDefaults(suiteName: APP_GROUP)?.double(forKey: FLASH_KEY) ?? 0
        let now = Date()
        if flashAt > 0 && (now.timeIntervalSince1970 - flashAt) < 0.8 {
            return Timeline(entries: [mk(now, true), mk(now.addingTimeInterval(0.45), false)], policy: .after(mid))
        }
        return Timeline(entries: [mk(now, false)], policy: .after(mid))
    }
}
// 멤버 필터 정규화: nil 또는 ""(전체 보기) → nil(전체), 그 외 userId
func wgMemberFilter(_ m: MemberEntity?) -> String? {
    guard let id = m?.id, !id.isEmpty else { return nil }
    return id
}
// 실효 필터: 프로필 탭(App Group 오버라이드)이 있으면 그걸, 없으면 설정의 멤버.
// 오버라이드 값 ""=전체, 키 없음=탭 안 함→설정값 사용.
func effectiveFilter(_ configMember: MemberEntity?) -> String? {
    if let ov = UserDefaults(suiteName: APP_GROUP)?.string(forKey: FILTER_KEY) {
        return ov.isEmpty ? nil : ov
    }
    return wgMemberFilter(configMember)
}
// 실효 방: 씰 탭으로 전환한 방(오버라이드)이 있으면 그 방, 없으면 설정/현재 방.
func effectiveRoom(_ configRoomId: String?) -> WGRoom? {
    let data = loadWGData()
    if let ov = UserDefaults(suiteName: APP_GROUP)?.string(forKey: ROOM_KEY), !ov.isEmpty,
       let r = data?.rooms.first(where: { $0.id == ov }) { return r }
    return pickRoom(data, roomId: configRoomId)
}
func sampleRoom() -> WGRoom {
    WGRoom(id: "s", name: "가족방", seal: "navy:taegeuk", sealPng: nil,
           members: [WGMember(userId: "u1", name: "나", color: "#ea4d4d")],
           events: [WGEvent(date: todayStr(), title: "가족 저녁", time: "19:00", color: "#ea4d4d", userId: "u1")],
           todos: [WGTodo(id: "t", date: todayStr(), title: "약 챙기기", time: "09:00", color: "#3d85d4", done: false)])
}

// MARK: - 날짜 유틸

func todayStr() -> String { fmt(Date()) }
func fmt(_ d: Date) -> String { let f = DateFormatter(); f.dateFormat = "yyyy-MM-dd"; return f.string(from: d) }
func parse(_ s: String) -> Date? { let f = DateFormatter(); f.dateFormat = "yyyy-MM-dd"; return f.date(from: s) }
// start~end(포함) 날짜 문자열 목록 — 레인 점유 판정용
func runDays(_ start: String, _ end: String) -> [String] {
    guard var d = parse(start), let e = parse(end) else { return [start] }
    let cal = Calendar.current
    var out: [String] = []
    var guardN = 0
    while d <= e && guardN < 400 { out.append(fmt(d)); guard let nx = cal.date(byAdding: .day, value: 1, to: d) else { break }; d = nx; guardN += 1 }
    return out
}

// MARK: - 공통 헤더 (좌 프로필+멤버 / 우 방 선택은 위젯 편집이므로 이름만 표시)

struct WGHeader: View {
    let room: WGRoom
    var compact: Bool = false
    var active: String? = nil          // 현재 선택된 멤버 userId (nil=전체)
    var monthNav: Bool = false         // 월 위젯 이전/다음달 < >
    var monthLabel: String = ""
    var myUserId: String? = nil        // 현재 사용자 — 아바타 맨 앞에 고정(항상 노출)
    var weekNav: Bool = false          // 2주 위젯 이전/다음 2주 < >
    var weekLabel: String = ""
    var weekOffset: Int = 0            // 현재 2주 오프셋(라벨 탭=현재로 복귀용)
    private var sealSize: CGFloat { compact ? 22 : 26 }
    private var avSize: CGFloat { compact ? 19 : 21 }
    // 그리드 위젯은 폭이 넓어 4명 + '전체' 칩. 단 2주(medium)는 주 이동 < > 라벨이
    // 폭을 더 먹어 작은 폰에서 넘칠 수 있으므로 3명으로(나 먼저 정렬이라 내 것은 항상 보임).
    private var maxAvatars: Int { weekNav ? 3 : 4 }
    // 실멤버(가상 제외)를 '나 먼저' 순서로 정렬 → prefix로 잘려도 내가 항상 보임.
    private var orderedMembers: [WGMember] {
        let reals = room.members.filter { ($0.userId ?? "").isEmpty == false }
        guard let me = myUserId, !me.isEmpty else { return reals }
        return reals.sorted { ($0.userId == me ? 0 : 1) < ($1.userId == me ? 0 : 1) }
    }
    // 멤버가 많아 다 못 담으면 마지막 한 칸은 '+N'용으로 비워 표시(나 먼저라 내 것은 항상 포함).
    private var shownMembers: [WGMember] {
        let all = orderedMembers
        return all.count <= maxAvatars ? all : Array(all.prefix(maxAvatars - 1))
    }
    private var hiddenCount: Int { max(0, orderedMembers.count - shownMembers.count) }
    var body: some View {
        HStack(spacing: 6) {
            // 씰 = 방 프로필. 탭하면 다음 방으로 전환(방 여러 개일 때). 실제 앱 씰 이미지.
            Button(intent: CycleRoomIntent()) {
                SealIcon(seal: room.seal, name: room.name, pngDataURL: room.sealPng, size: sealSize)
            }.buttonStyle(.plain)
            // 멤버 아바타 = 그 사람만 보기 (탭). 겹치지 않게 간격, 사진 있으면 사진.
            HStack(spacing: 4) {
                // 전체 = 필터 해제(모든 멤버 일정). 아무도 선택 안 됐을 때 강조.
                Button(intent: SetFilterIntent(userId: "")) {
                    Text("전체")
                        .font(.system(size: compact ? 9 : 9.5, weight: .bold))
                        .foregroundColor(active == nil ? .cream : .mutedBrown)
                        .lineLimit(1).fixedSize()   // 헤더가 좁아도 '전체'로 안 잘림
                        .padding(.horizontal, 6).frame(height: avSize)
                        .background(Capsule().fill(active == nil ? Color.terra : Color.mutedBrown.opacity(0.16)))
                        .overlay(Capsule().stroke(active == nil ? Color.clear : Color.cream, lineWidth: 1))
                }.buttonStyle(.plain)
                ForEach(Array(shownMembers.enumerated()), id: \.offset) { _, m in
                    let uid = m.userId ?? ""
                    let on = (active == uid)
                    Button(intent: SetFilterIntent(userId: on ? "" : uid)) {
                        Group {
                            if let img = decodeDataURLImage(m.avatarPng) {
                                Image(uiImage: img).resizable().scaledToFill().frame(width: avSize, height: avSize).clipShape(Circle())
                            } else {
                                Circle().fill(Color(hexStr: m.color)).frame(width: avSize, height: avSize)
                                    .overlay(Text(String(m.name.prefix(1))).font(.system(size: avSize*0.46, weight: .bold)).foregroundColor(.white))
                            }
                        }
                        .overlay(Circle().stroke(on ? Color.terra : Color.cream, lineWidth: on ? 2.5 : 1.5))
                        .opacity(active == nil || on ? 1 : 0.4)   // 필터 중이면 선택된 사람만 또렷
                    }.buttonStyle(.plain)
                }
                // 멤버가 많아 다 못 담을 때 '+N' — 탭하면 앱을 열어(그 방) 거기서 전체 멤버 선택 가능.
                // (위젯 편집 화면의 '멤버' 드롭다운에도 전체 멤버가 나오므로 숨은 멤버 필터도 가능)
                if hiddenCount > 0 {
                    Link(destination: URL(string: "com.lsung.uricalendar://open?room=\(room.id)")!) {
                        Circle().fill(Color.mutedBrown.opacity(0.18)).frame(width: avSize, height: avSize)
                            .overlay(Text("+\(hiddenCount)").font(.system(size: avSize*0.4, weight: .bold)).foregroundColor(.mutedBrown))
                            .overlay(Circle().stroke(Color.cream, lineWidth: 1.5))
                    }
                }
            }
            Spacer(minLength: 4)
            if monthNav {
                // 이전/다음달 이동
                Button(intent: ShiftMonthIntent(delta: -1)) {
                    Image(systemName: "chevron.left").font(.system(size: 12, weight: .bold)).foregroundColor(.terra).frame(width: 20, height: 20)
                }.buttonStyle(.plain)
                Text(monthLabel).font(.system(size: 13, weight: .heavy)).foregroundColor(.ink).lineLimit(1)
                Button(intent: ShiftMonthIntent(delta: 1)) {
                    Image(systemName: "chevron.right").font(.system(size: 12, weight: .bold)).foregroundColor(.terra).frame(width: 20, height: 20)
                }.buttonStyle(.plain)
            } else if weekNav {
                // 이전/다음 2주 이동. 라벨 탭 = 현재 2주로 복귀(오프셋 0).
                Button(intent: ShiftWeekIntent(delta: -1)) {
                    Image(systemName: "chevron.left").font(.system(size: 12, weight: .bold)).foregroundColor(.terra).frame(width: 20, height: 20)
                }.buttonStyle(.plain)
                Button(intent: ShiftWeekIntent(delta: -weekOffset)) {
                    Text(weekLabel).font(.system(size: 12, weight: .heavy)).foregroundColor(weekOffset == 0 ? .ink : .terra).lineLimit(1).fixedSize()
                }.buttonStyle(.plain)
                Button(intent: ShiftWeekIntent(delta: 1)) {
                    Image(systemName: "chevron.right").font(.system(size: 12, weight: .bold)).foregroundColor(.terra).frame(width: 20, height: 20)
                }.buttonStyle(.plain)
            } else {
                Text(room.name).font(.system(size: compact ? 12 : 14, weight: .heavy)).foregroundColor(.ink).lineLimit(1)
            }
            // 새로고침
            Button(intent: RefreshIntent()) {
                Image(systemName: "arrow.clockwise").font(.system(size: 12, weight: .bold)).foregroundColor(.mutedBrown).frame(width: 20, height: 20)
            }.buttonStyle(.plain)
        }
    }
}
struct SealIcon: View {
    let seal: String?; let name: String; var pngDataURL: String? = nil; var size: CGFloat = 26
    var body: some View {
        if let img = decodeDataURLImage(pngDataURL) {
            // 앱이 구운 실제 씰(컬러+문양) 그대로
            Image(uiImage: img).resizable().interpolation(.high).scaledToFill()
                .frame(width: size, height: size).clipShape(Circle())
        } else {
            // 폴백: 색 그라데이션 + 이니셜 (씰 PNG 없을 때)
            Circle().fill(LinearGradient(colors: [Color(hexStr: "#7c92b4"), Color(hexStr: "#566f8f")], startPoint: .topLeading, endPoint: .bottomTrailing))
                .frame(width: size, height: size)
                .overlay(Text(String(name.prefix(1))).font(.system(size: size*0.45, weight: .bold)).foregroundColor(Color(hexStr: "#f3e6cf")))
        }
    }
}

// MARK: - ① 오늘 (small)

struct TodayView: View {
    let room: WGRoom; let filter: String?
    var myUserId: String? = nil
    // 작은 위젯 기본 = '내 일정만'. 명시 필터(설정/탭)가 있으면 그걸 우선.
    private var eff: String? { filter ?? myUserId }
    var todays: [WGEvent] { dedupeEvents(room.events.filter { $0.date == todayStr() && (eff == nil || $0.userId == eff) })
        .sorted { ($0.time.isEmpty ? "zz" : $0.time) < ($1.time.isEmpty ? "zz" : $1.time) } }
    var todaysTodos: [WGTodo] { room.todos.filter { $0.date == todayStr() && todoVisibleFor($0, eff) } }
    var body: some View {
        VStack(alignment: .leading, spacing: 5) {
            HStack(spacing: 5) {
                // 좌측 상단 방 프로필 씰 — 탭하면 다음 방으로 전환
                Button(intent: CycleRoomIntent()) {
                    SealIcon(seal: room.seal, name: room.name, pngDataURL: room.sealPng, size: 20)
                }.buttonStyle(.plain)
                Text("오늘").font(.system(size: 15, weight: .black)).foregroundColor(.terra)
                if todays.count + todaysTodos.count > 0 {
                    Text("\(todays.count + todaysTodos.count)").font(.system(size: 10, weight: .black)).foregroundColor(.cream)
                        .padding(.horizontal, 5).background(Capsule().fill(Color.terra))
                }
                Spacer()
                Button(intent: RefreshIntent()) {
                    Image(systemName: "arrow.clockwise").font(.system(size: 13, weight: .bold)).foregroundColor(.mutedBrown)
                }.buttonStyle(.plain)
            }
            if todays.isEmpty && todaysTodos.isEmpty {
                Spacer(); HStack { Spacer(); Text("오늘 일정이 없어요").font(.system(size: 11)).foregroundColor(.mutedBrown); Spacer() }; Spacer()
            } else {
                ForEach(Array(todays.prefix(3).enumerated()), id: \.offset) { _, e in EventRow(e: e) }
                ForEach(todaysTodos.prefix(2)) { t in TodoRow(t: t) }
                Spacer(minLength: 0)
            }
        }.padding(12).widgetBg()
    }
}
struct EventRow: View {
    let e: WGEvent
    var body: some View {
        HStack(spacing: 7) {
            RoundedRectangle(cornerRadius: 2).fill(Color(hexStr: e.color)).frame(width: 3, height: 15)
            Text(e.title).font(.system(size: 12, weight: .semibold)).foregroundColor(.ink).lineLimit(1)
            Spacer(minLength: 2)
            if !e.time.isEmpty { Text(e.time).font(.system(size: 10.5).monospacedDigit()).foregroundColor(.mutedBrown) }
        }
    }
}
struct TodoRow: View {
    let t: WGTodo
    private var done: Bool { todoDone(t) }
    var body: some View {
        HStack(spacing: 8) {
            if #available(iOS 17.0, *) {
                Button(intent: ToggleTodoIntent(todoId: t.id)) { checkbox } .buttonStyle(.plain)
            } else { checkbox }
            Text(t.title).font(.system(size: 12, weight: .medium))
                .foregroundColor(done ? .mutedBrown : .ink).strikethrough(done)
            Spacer(minLength: 2)
            if !t.time.isEmpty { Text(t.time).font(.system(size: 10).monospacedDigit()).foregroundColor(.mutedBrown) }
        }.opacity(done ? 0.65 : 1)
    }
    var checkbox: some View {
        RoundedRectangle(cornerRadius: 5).stroke(Color.terra, lineWidth: 2).frame(width: 16, height: 16)
            .background(done ? RoundedRectangle(cornerRadius: 5).fill(Color.terra) : nil)
            .overlay(done ? Image(systemName: "checkmark").font(.system(size: 10, weight: .black)).foregroundColor(.white) : nil)
    }
}

// MARK: - ② 2주 캘린더 (medium) / ④ 월 그리드 (large) 공통 그리드

struct GridView: View {
    let room: WGRoom; let filter: String?; let weeks: Int   // 2 or 6
    var monthNav: Bool = false        // 월 위젯: 이전/다음달 이동
    var weekNav: Bool = false         // 2주 위젯: 이전/다음 2주 이동
    var gridV: Bool = false           // 세로/가로 격자선(앱 설정 연동)
    var gridH: Bool = false
    var myUserId: String? = nil       // 헤더 아바타 '나 먼저' 정렬용
    var holidays: [String:String] = [:]   // 빨간날(공휴일) 'YYYY-MM-DD'→이름
    private var weekOffset: Int { weekNav ? readWeekOffset() : 0 }   // 2주 페이지(1=14일)
    // 월 위젯 기준 달 (오프셋 적용)
    var monthDate: Date {
        let cal = Calendar.current
        return cal.date(byAdding: .month, value: (monthNav ? readMonthOffset() : 0), to: Date()) ?? Date()
    }
    var monthLabel: String {
        let cal = Calendar.current
        let c = cal.dateComponents([.year, .month], from: monthDate)
        let curY = cal.component(.year, from: Date())
        return (c.year == curY) ? "\(c.month ?? 0)월" : "\(c.year ?? 0).\(c.month ?? 0)"
    }
    // 2주 위젯 헤더 라벨: 보이는 범위 'M.d~M.d' (같은 달이면 뒤 달 생략)
    var weekLabel: String {
        let cal = Calendar.current
        let s = startDate
        let e = cal.date(byAdding: .day, value: 13, to: s)!
        let cs = cal.dateComponents([.month, .day], from: s)
        let ce = cal.dateComponents([.month, .day], from: e)
        let tail = (cs.month == ce.month) ? "\(ce.day ?? 0)" : "\(ce.month ?? 0).\(ce.day ?? 0)"
        return "\(cs.month ?? 0).\(cs.day ?? 0)~\(tail)"
    }
    var startDate: Date {
        let cal = Calendar.current
        if weeks == 2 { // 이번 주 일요일부터(+ 2주 페이지 오프셋)
            let today = cal.startOfDay(for: Date())
            let wd = cal.component(.weekday, from: today) - 1
            let sun = cal.date(byAdding: .day, value: -wd, to: today)!
            return cal.date(byAdding: .day, value: weekOffset * 14, to: sun)!
        } else { // 기준 달 1일이 포함된 주의 일요일
            let comp = cal.dateComponents([.year, .month], from: monthDate)
            let first = cal.date(from: comp)!
            let wd = cal.component(.weekday, from: first) - 1
            return cal.date(byAdding: .day, value: -wd, to: first)!
        }
    }
    var maxLanes: Int { 2 }
    // 표시 주 수 — 2주 위젯은 2. 월 위젯은 그 달이 실제 걸치는 주 수(5 또는 6, 드물게 4)로
    // 동적 계산 → 6주 달도 마지막 날이 잘리지 않음. 2개 일정 줄(maxLanes)은 유지.
    var rowCount: Int {
        if weeks == 2 { return 2 }
        let cal = Calendar.current
        let comp = cal.dateComponents([.year, .month], from: monthDate)
        guard let first = cal.date(from: comp) else { return 5 }
        let offset = cal.component(.weekday, from: first) - 1        // 1일의 요일(0=일)
        let dim = cal.range(of: .day, in: .month, for: first)?.count ?? 30
        return Int(ceil(Double(offset + dim) / 7.0))                 // 4·5·6
    }
    // 보이는 그리드 범위의 '기간 런(run)'을 만들고 줄(lane)을 고정 배정 →
    // 같은 일정이 날마다 같은 줄에 놓여 끊기지 않고 이어짐.
    var runs: [EvRun] {
        let cal = Calendar.current
        let startS = fmt(startDate)
        let endS = fmt(cal.date(byAdding: .day, value: rowCount*7 - 1, to: startDate)!)
        let vis = room.events.filter { $0.date >= startS && $0.date <= endS && (filter == nil || $0.userId == filter) }
        var result: [EvRun] = []
        // gid(연결 키)가 있는 것만 연속 날짜로 묶어 기간 바로. 없으면(단일·여러날) 각각 단독 칩.
        // (앱 isSameRangeGroup과 동일 — 제목이 같아도 gid 없으면 절대 안 붙음)
        var byGid: [String: (title: String, color: String, outline: Bool, ord: Int, shared: Bool, dates: Set<String>)] = [:]
        for e in vis {
            let ol = e.style == "outline"
            if let g = e.gid, !g.isEmpty {
                if byGid[g] == nil { byGid[g] = (e.title, e.color, ol, e.ord, e.shared, []) }
                byGid[g]!.dates.insert(e.date)
            } else {
                result.append(EvRun(title: e.title, color: e.color, start: e.date, end: e.date, lane: 0, outline: ol, ord: e.ord, shared: e.shared))
            }
        }
        for (_, v) in byGid {
            let ds = v.dates.sorted()
            var i = 0
            while i < ds.count {
                var j = i
                while j + 1 < ds.count, let cur = parse(ds[j]), let nd = cal.date(byAdding: .day, value: 1, to: cur), fmt(nd) == ds[j+1] { j += 1 }
                result.append(EvRun(title: v.title, color: v.color, start: ds[i], end: ds[j], lane: 0, outline: v.outline, ord: v.ord, shared: v.shared))
                i = j + 1
            }
        }
        // 앱 _computeMonthLanes 순서 그대로: 기간(여러날) 상단 우선 → order_index(사용자 배치) → 시작일 → 제목
        result.sort {
            let ar = $0.end > $0.start, br = $1.end > $1.start
            if ar != br { return ar }                 // 기간 바가 위 레인
            if $0.ord != $1.ord { return $0.ord < $1.ord }
            if $0.start != $1.start { return $0.start < $1.start }
            return $0.title < $1.title
        }
        // 실제 날짜 점유(occupancy) 기반 레인 배정(앱 _computeMonthLanes와 동일) —
        // 기간 일정은 '자기가 걸친 날'만 레인을 막고, 그 외 날의 일정은 같은 레인을 재사용.
        // (예전 laneEnd 방식은 기간 일정 종료일이 멀면 그 사이 모든 일정을 위 레인으로 밀어냈음)
        var occ: [Set<String>] = []   // lane → 점유된 날짜들
        for idx in result.indices {
            let days = runDays(result[idx].start, result[idx].end)
            var lane = 0
            while lane < occ.count && !occ[lane].isDisjoint(with: days) { lane += 1 }
            if lane == occ.count { occ.append(Set(days)) } else { occ[lane].formUnion(days) }
            result[idx].lane = lane
        }
        return result
    }
    // 날짜별 줄 슬롯(고정 길이) + 넘침 개수
    func slotsFor(_ d: Date, _ rs: [EvRun]) -> ([DayBar?], Int) {
        let s = fmt(d)
        var arr = [DayBar?](repeating: nil, count: maxLanes)
        var overflow = 0
        for r in rs where r.start <= s && s <= r.end {
            if r.lane < maxLanes {
                arr[r.lane] = DayBar(title: r.title, color: r.color, contLeft: r.start < s, contRight: r.end > s, outline: r.outline, shared: r.shared)
            } else { overflow += 1 }
        }
        return (arr, overflow)
    }
    var body: some View {
        let cal = Calendar.current
        let allRuns = runs
        let curMonth = cal.component(.month, from: monthDate)
        // 실제로 쓰인 이벤트 줄 수만 예약(빈 줄이 할일을 셀 밖으로 밀어내지 않게) → 할일 노출 개선
        let usedLanes = min(maxLanes, max(0, (allRuns.map { $0.lane }.max() ?? -1) + 1))
        VStack(spacing: 0) {
            WGHeader(room: room, compact: weeks > 2, active: filter, monthNav: monthNav, monthLabel: monthLabel, myUserId: myUserId, weekNav: weekNav, weekLabel: weekLabel, weekOffset: weekOffset)
            Color.clear.frame(height: weeks > 2 ? 9 : 7)     // 헤더 ↔ 달력 사이 여백(위아래 균형)
            HStack(spacing: 0) {
                ForEach(0..<7) { i in
                    Text(["일","월","화","수","목","금","토"][i]).font(.system(size: 10, weight: .bold))
                        .foregroundColor(i == 0 ? .sunRed : .mutedBrown).frame(maxWidth: .infinity)
                }
            }.padding(.bottom, 2)
            // 주(week)별 행을 maxHeight 무한으로 균등 분배 → 위젯 높이를 정확히 채워 '아래 잘림' 방지
            VStack(spacing: 2) {
                ForEach(0..<rowCount, id: \.self) { w in
                    HStack(spacing: 0) {
                        ForEach(0..<7, id: \.self) { c in
                            let d = cal.date(byAdding: .day, value: w*7 + c, to: startDate)!
                            let pair = slotsFor(d, allRuns)
                            let inM = (weeks != 6) || (cal.component(.month, from: d) == curMonth)
                            let dTodos = room.todos.filter { $0.date == fmt(d) && todoVisibleFor($0, filter) }   // 멤버 필터 적용(앱과 동일)
                            let dKey = fmt(d)
                            let dHl = hlFor(room, dKey)      // 날짜 강조(칸당 1건, 겹치면 시작일 이른 것)
                            DayCell(date: d, slots: Array(pair.0.prefix(usedLanes)), overflow: pair.1, isToday: fmt(d) == todayStr(),
                                    dow: cal.component(.weekday, from: d) - 1, roomId: room.id, inMonth: inM,
                                    rightLine: gridV && c < 6, bottomLine: gridH && w < rowCount - 1,
                                    holiday: holidays[fmt(d)],
                                    todos: dTodos, maxTodos: weeks == 2 ? max(0, 3 - usedLanes) : 1, dense: rowCount >= 6 || weeks == 2,
                                    hl: dHl, hlStart: dHl?.s == dKey)
                        }
                    }.frame(maxHeight: .infinity)
                }
            }.frame(maxHeight: .infinity)
        }.padding(.horizontal, weeks > 2 ? 12 : 13).padding(.vertical, 10).widgetBg()
    }
}
// 기간 런: 같은 일정의 연속 날짜 묶음 + 배정된 줄(lane)
struct EvRun { let title: String; let color: String; let start: String; let end: String; var lane: Int; var outline: Bool = false; var ord: Int = 0; var shared: Bool = false }
// 하루치 한 줄의 바(연속 정보 포함)
struct DayBar { let title: String; let color: String; let contLeft: Bool; let contRight: Bool; var outline: Bool = false; var shared: Bool = false }
// 변에 그릴 1차원 선 — 대시/도트 패턴을 쓰려면 Rectangle 채움이 아니라 stroke 가능한 path여야 한다.
struct HLine: Shape {
    func path(in r: CGRect) -> Path { var p = Path(); p.move(to: CGPoint(x: r.minX, y: r.midY)); p.addLine(to: CGPoint(x: r.maxX, y: r.midY)); return p }
}
struct VLine: Shape {
    func path(in r: CGRect) -> Path { var p = Path(); p.move(to: CGPoint(x: r.midX, y: r.minY)); p.addLine(to: CGPoint(x: r.midX, y: r.maxY)); return p }
}
struct DayCell: View {
    let date: Date; let slots: [DayBar?]; let overflow: Int; let isToday: Bool; let dow: Int; let roomId: String; let inMonth: Bool
    var rightLine: Bool = false; var bottomLine: Bool = false
    var holiday: String? = nil        // 빨간날(공휴일) 이름 — 있으면 날짜 빨강 + 빨간 바 자동 표시
    var todos: [WGTodo] = []          // 그 날 할일 (이벤트 바 아래에 체크박스 줄로)
    var maxTodos: Int = 1             // 셀에 보일 할일 최대 수(칸 높이에 맞춤)
    var dense: Bool = false           // 6주 달 등 칸이 짧을 때 숫자/바를 살짝 줄여 2개 일정 확보
    var hl: WGHighlight? = nil        // 날짜 강조 — 격자선 기준 테두리/채움 + 시작일 라벨(앱과 동일)
    var hlStart: Bool = false         // 이 칸이 강조의 진짜 시작일인지(라벨은 여기에만)
    private var numBox: CGFloat { dense ? 16 : 18 }
    private var barH: CGFloat { dense ? 10.5 : 12 }
    private var totalOverflow: Int { overflow + max(0, todos.count - maxTodos) }   // 이벤트+할일 넘침 합산
    // 할일 한 줄: 작은 체크박스 + 제목. 완료면 취소선+흐리게. 이벤트 바와 시각적으로 구분.
    @ViewBuilder private func todoLine(_ t: WGTodo) -> some View {
        let done = todoDone(t)
        HStack(spacing: 2) {
            Image(systemName: done ? "checkmark.square.fill" : "square")
                .font(.system(size: 7.5, weight: .bold)).foregroundColor(Color(hexStr: t.color))
            Text(t.title).font(.system(size: 8, weight: .semibold))
                .foregroundColor(done ? .mutedBrown : .ink)
                .strikethrough(done, color: .mutedBrown).lineLimit(1)
            Spacer(minLength: 0)
        }.frame(minHeight: barH).opacity(done ? 0.6 : 1).padding(.leading, 1)
    }
    // 기간 바: 주 안에서 이어지는 쪽은 각지게+딱 붙게, 끝/주경계는 둥글게 → 옆칸과 맞닿아 연속.
    // 테두리 일정(outline)은 채움 대신 색 테두리 + 먹색 글자(앱 .ev-outline과 동일).
    @ViewBuilder private func barView(_ b: DayBar) -> some View {
        let roundL = !b.contLeft || dow == 0
        let roundR = !b.contRight || dow == 6
        let showTitle = !b.contLeft || dow == 0     // 시작일/주 시작에만 제목
        let showMark = b.shared && !b.contLeft       // 함께 일정: 시작 칸 왼쪽에 얇은 띠 (앱 .cal-evbar-marker)
        let shape = UnevenRoundedRectangle(
            topLeadingRadius: roundL ? 3 : 0, bottomLeadingRadius: roundL ? 3 : 0,
            bottomTrailingRadius: roundR ? 3 : 0, topTrailingRadius: roundR ? 3 : 0)
        Text(showTitle ? b.title : " ").font(.system(size: 8.5, weight: .bold))
            .foregroundColor(b.outline ? .ink : contrastText(b.color)).lineLimit(1)
            .padding(.leading, showMark ? 8 : (roundL ? 3 : 0)).padding(.trailing, roundR ? 3 : 0)
            .frame(maxWidth: .infinity, minHeight: barH, alignment: .leading)
            .background(
                b.outline
                    ? AnyView(shape.stroke(Color(hexStr: b.color), lineWidth: 1.2))
                    : AnyView(shape.fill(Color(hexStr: b.color)))
            )
            .overlay(alignment: .leading) {
                if showMark {
                    RoundedRectangle(cornerRadius: 1)
                        .fill(b.outline ? Color(hexStr: b.color) : contrastText(b.color))
                        .frame(width: 2.5, height: barH * 0.52)
                        // 왼쪽 여백은 조금 더, 제목과의 간격은 더 좁게(안드로이드와 동일 조정). [2026-08-19]
                        .padding(.leading, roundL ? 4 : 2)
                }
            }
            // 양끝(둥근 쪽)에만 1pt 바깥 여백 — 안드로이드 1dp inset과 육안 동일. 기간 중간은 0(연속 유지).
            .padding(.leading, roundL ? 1 : 0).padding(.trailing, roundR ? 1 : 0)
    }
    /* 날짜 강조 테두리 — 변마다 선을 그린다(칸을 꽉 채워 격자선과 정확히 맞음, 앱과 동일하게 모서리 직각).
       테마별로 두께/선 모양만 다르다: 폴더=굵게, 점선=대시, 꽃=둥근 점(위젯 크기에선 꽃 그림이 뭉개져
       같은 색 도트로 근사 — 안드로이드와 동일 정책). [2026-08-19] */
    @ViewBuilder private func hlBorder(_ h: WGHighlight) -> some View {
        let f = hlSides(h, fmt(date), dow)
        let col = Color(hexStr: h.c)
        let w: CGFloat = h.r == "folder" ? 2.2 : (h.r == "dashed" ? 1.4 : 1.3)
        let dash: [CGFloat]? = h.r == "dashed" ? [2.6, 2.0] : (h.r == "flower" ? [0.1, 3.2] : nil)
        let style = StrokeStyle(lineWidth: w, lineCap: dash != nil ? .round : .butt, dash: dash ?? [])
        ZStack {
            if f.top    { HLine().stroke(col, style: style).frame(height: w).frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top) }
            if f.bottom { HLine().stroke(col, style: style).frame(height: w).frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .bottom) }
            if f.left   { VLine().stroke(col, style: style).frame(width: w).frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .leading) }
            if f.right  { VLine().stroke(col, style: style).frame(width: w).frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .trailing) }
        }
    }
    var body: some View {
        // 날짜 탭 → 앱의 그 날짜 열기(위젯은 스크롤 불가 → 많은 일정은 앱에서 전부 보기)
        Link(destination: URL(string: "com.lsung.uricalendar://open?room=\(roomId)&date=\(fmt(date))")!) {
            VStack(spacing: 1.5) {
                Text("\(Calendar.current.component(.day, from: date))")
                    .font(.system(size: 12, weight: .semibold))
                    .foregroundColor(isToday ? .white
                        : ((hl != nil && hl!.dk) ? .white                                  // 진한 채움 위 = 흰 숫자
                        : ((dow == 0 || holiday != nil) ? .sunRed : .ink)))
                    .frame(width: numBox, height: numBox)                 // ★ 항상 고정 → 모든 날짜 같은 라인
                    .background(isToday ? Circle().fill(Color.terra) : nil)
                // 강조 라벨: 앱은 칸 위로 솟은 탭이지만 위젯 칸은 그럴 여백이 없어 공휴일 바와 같은
                // '색 바' 방식으로 제목을 보여준다(라벨을 쓰는 테마=기본/폴더에서만). [2026-08-19]
                if hlStart, let h = hl, h.lb, !h.t.isEmpty {
                    Text(h.t).font(.system(size: 8.5, weight: .bold))
                        .foregroundColor(Color(hexStr: h.ink)).lineLimit(1)
                        .padding(.leading, 3).padding(.trailing, 3)
                        .frame(maxWidth: .infinity, minHeight: barH, alignment: .leading)
                        .background(RoundedRectangle(cornerRadius: 3).fill(Color(hexStr: h.c)))
                        .padding(.leading, 1).padding(.trailing, 1)
                }
                if let hn = holiday {   // 빨간날: 이름을 빨간 바로 자동 표시(앱은 텍스트만 → 위젯은 바)
                    Text(hn).font(.system(size: 8.5, weight: .bold))
                        .foregroundColor(.white).lineLimit(1)
                        .padding(.leading, 3).padding(.trailing, 3)
                        .frame(maxWidth: .infinity, minHeight: barH, alignment: .leading)
                        .background(RoundedRectangle(cornerRadius: 3).fill(Color.sunRed))
                        .padding(.leading, 1).padding(.trailing, 1)
                }
                ForEach(0..<slots.count, id: \.self) { idx in
                    if let b = slots[idx] { barView(b) } else { Color.clear.frame(height: barH) }   // 빈 줄도 높이 유지 → 줄 정렬
                }
                ForEach(Array(todos.prefix(maxTodos).enumerated()), id: \.offset) { _, t in todoLine(t) }
                if totalOverflow > 0 {
                    Text("+\(totalOverflow)").font(.system(size: 7.5, weight: .bold)).foregroundColor(.mutedBrown)
                }
                Spacer(minLength: 0)
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)   // 셀이 행 높이를 채움 → 위젯 밖으로 안 넘침
            .opacity(inMonth ? 1 : 0.4)   // 다음/이전달 날짜는 흐리게
            // 강조 채움 — 날짜/일정 뒤에 깔림(앱 .dhr-fill/.dhr-deep의 z-index:0과 동일)
            .background { if let h = hl, hlIsFill(h) { Rectangle().fill(Color(hexStr: h.c)).opacity(inMonth ? 1 : 0.4) } }
            // 강조 테두리 — 칸을 꽉 채우는 사각 경계(채움형은 테두리 없음)
            .overlay { if let h = hl, !hlIsFill(h) { hlBorder(h).opacity(inMonth ? 1 : 0.4) } }
            .clipped()
            // 격자선(앱 설정 연동) — 세로=우측, 가로=하단
            .overlay(alignment: .trailing) { if rightLine { Rectangle().fill(Color.mutedBrown.opacity(0.16)).frame(width: 0.6) } }
            .overlay(alignment: .bottom) { if bottomLine { Rectangle().fill(Color.mutedBrown.opacity(0.16)).frame(height: 0.6) } }
        }.buttonStyle(.plain)
    }
}

// MARK: - ③ 콤보 (large): 다가오는 일정(오늘+다음날들) + 미니 월

// 날짜 라벨: 오늘/내일/M.d
func dayLabel(_ ds: String) -> String {
    if ds == todayStr() { return "오늘" }
    let cal = Calendar.current
    if let tm = cal.date(byAdding: .day, value: 1, to: Date()), fmt(tm) == ds { return "내일" }
    if let d = parse(ds) { let c = cal.dateComponents([.month, .day], from: d); return "\(c.month ?? 0).\(c.day ?? 0)" }
    return ds
}
// 'M월d일' 한글 라벨(기간 표시용)
func mdKo(_ ds: String) -> String {
    if let d = parse(ds) { let c = Calendar.current.dateComponents([.month, .day], from: d); return "\(c.month ?? 0)월\(c.day ?? 0)일" }
    return ds
}
// 기간 라벨: 하루면 오늘/내일/M.d, 여러 날이면 'M월d일 ~ M월d일'
func rangeLabel(_ s: String, _ e: String) -> String {
    return s == e ? dayLabel(s) : "\(mdKo(s)) ~ \(mdKo(e))"
}
// 다가오는 일정 한 줄(기간 일정은 하나로 묶음)
struct UpRun { let title: String; let color: String; let start: String; let end: String; let time: String }
struct ComboView: View {
    let room: WGRoom; let filter: String?; var myUserId: String? = nil
    var holidays: [String:String] = [:]
    // 오늘부터 앞으로의 일정. 같은 제목+색의 '연속 날짜'는 하나의 기간(run)으로 묶음.
    var upcoming: [UpRun] {
        let cal = Calendar.current
        let t = todayStr()
        let evs = dedupeEvents(room.events.filter { $0.date >= t && (filter == nil || $0.userId == filter) })
        var result: [UpRun] = []
        // gid가 있는 것만 연속 날짜로 묶음. 없으면(단일·여러날) 각각 단독. 제목만 같고 gid 없으면 안 붙음.
        var byGid: [String: (title: String, color: String, dates: [String: String])] = [:]  // gid → date→time
        for e in evs {
            if let g = e.gid, !g.isEmpty {
                if byGid[g] == nil { byGid[g] = (e.title, e.color, [:]) }
                let prev = byGid[g]!.dates[e.date]
                if prev == nil || (!e.time.isEmpty && e.time < (prev ?? "zz")) { byGid[g]!.dates[e.date] = e.time }
            } else {
                result.append(UpRun(title: e.title, color: e.color, start: e.date, end: e.date, time: e.time))
            }
        }
        for (_, v) in byGid {
            let ds = v.dates.keys.sorted()
            var i = 0
            while i < ds.count {
                var j = i
                while j + 1 < ds.count, let cur = parse(ds[j]), let nd = cal.date(byAdding: .day, value: 1, to: cur), fmt(nd) == ds[j+1] { j += 1 }
                result.append(UpRun(title: v.title, color: v.color, start: ds[i], end: ds[j],
                                    time: i == j ? (v.dates[ds[i]] ?? "") : ""))   // 시간은 하루짜리에만
                i = j + 1
            }
        }
        result.sort { $0.start != $1.start ? $0.start < $1.start : $0.title < $1.title }
        return result
    }
    var body: some View {
        VStack(spacing: 8) {
            WGHeader(room: room, active: filter, myUserId: myUserId)
            HStack(alignment: .top, spacing: 14) {
                VStack(alignment: .leading, spacing: 7) {
                    Text("다가오는 일정").font(.system(size: 14, weight: .black)).foregroundColor(.terra)
                    if upcoming.isEmpty {
                        Text("예정된 일정이 없어요").font(.system(size: 12)).foregroundColor(.mutedBrown).padding(.top, 4)
                    } else {
                        ForEach(Array(upcoming.prefix(6).enumerated()), id: \.offset) { _, e in
                            let isNow = e.start <= todayStr() && todayStr() <= e.end   // 진행 중(오늘 포함)
                            HStack(spacing: 7) {
                                RoundedRectangle(cornerRadius: 2).fill(Color(hexStr: e.color)).frame(width: 3, height: 24)
                                VStack(alignment: .leading, spacing: 1) {
                                    HStack(spacing: 5) {
                                        Text(rangeLabel(e.start, e.end))
                                            .font(.system(size: 9.5, weight: .bold))
                                            .foregroundColor(isNow ? .cream : .mutedBrown)
                                            .padding(.horizontal, 5).padding(.vertical, 1)
                                            .background(Capsule().fill(isNow ? Color.terra : Color.mutedBrown.opacity(0.18)))
                                            .lineLimit(1)
                                        if !e.time.isEmpty { Text(e.time).font(.system(size: 10).monospacedDigit()).foregroundColor(.mutedBrown) }
                                    }
                                    Text(e.title).font(.system(size: 13.5, weight: .bold)).foregroundColor(.ink).lineLimit(1)
                                }
                            }
                        }
                    }
                    Spacer(minLength: 0)
                    ForEach(room.todos.filter { $0.date == todayStr() && todoVisibleFor($0, filter) }.prefix(1)) { t in TodoRow(t: t) }
                }.frame(maxWidth: .infinity, alignment: .leading)
                Rectangle().fill(Color.mutedBrown.opacity(0.2)).frame(width: 1)
                MiniMonth(room: room, filter: filter, holidays: holidays).frame(maxWidth: .infinity)
            }
        }.padding(16).widgetBg()
    }
}
struct MiniMonth: View {
    let room: WGRoom; let filter: String?
    var holidays: [String:String] = [:]
    func hasEvent(_ d: Date) -> [Color] {
        let s = fmt(d)
        return dedupeEvents(room.events.filter { $0.date == s && (filter == nil || $0.userId == filter) }).prefix(3).map { Color(hexStr: $0.color) }
    }
    var body: some View {
        let cal = Calendar.current
        let off = readComboMonthOffset()
        let base = cal.date(byAdding: .month, value: off, to: Date()) ?? Date()
        let comp = cal.dateComponents([.year, .month], from: base)
        let first = cal.date(from: comp)!
        let wd = cal.component(.weekday, from: first) - 1
        let start = cal.date(byAdding: .day, value: -wd, to: first)!
        let baseMonth = cal.component(.month, from: base)
        let cols = Array(repeating: GridItem(.flexible(), spacing: 1), count: 7)
        VStack(alignment: .leading, spacing: 4) {
            // 이전/다음달 이동 — 라벨 탭 = 현재 달로 복귀
            HStack(spacing: 2) {
                Button(intent: ShiftComboMonthIntent(delta: -1)) {
                    Image(systemName: "chevron.left").font(.system(size: 11, weight: .bold)).foregroundColor(.terra).frame(width: 18, height: 18)
                }.buttonStyle(.plain)
                Button(intent: ShiftComboMonthIntent(delta: -off)) {
                    Text("\(baseMonth)월").font(.system(size: 15, weight: .bold)).foregroundColor(off == 0 ? .ink : .terra)
                }.buttonStyle(.plain)
                Button(intent: ShiftComboMonthIntent(delta: 1)) {
                    Image(systemName: "chevron.right").font(.system(size: 11, weight: .bold)).foregroundColor(.terra).frame(width: 18, height: 18)
                }.buttonStyle(.plain)
            }
            HStack(spacing: 0) { ForEach(0..<7) { i in
                Text(["일","월","화","수","목","금","토"][i]).font(.system(size: 8, weight: .bold))
                    .foregroundColor(i == 0 ? .sunRed : .mutedBrown).frame(maxWidth: .infinity) } }
            LazyVGrid(columns: cols, spacing: 2) {
                ForEach(0..<35, id: \.self) { i in
                    let d = cal.date(byAdding: .day, value: i, to: start)!
                    let inMonth = cal.component(.month, from: d) == baseMonth
                    let isToday = fmt(d) == todayStr()
                    let isRed = (cal.component(.weekday, from: d) == 1) || (holidays[fmt(d)] != nil)   // 일요일/공휴일
                    // 미니 달력은 칸이 너무 작아 테두리/라벨이 뭉개짐 → 어느 날이 강조됐는지만 옅은 색으로. [2026-08-19]
                    let mh = hlFor(room, fmt(d))
                    VStack(spacing: 1) {
                        Text("\(cal.component(.day, from: d))").font(.system(size: 10, weight: .semibold))
                            .foregroundColor(isToday ? .white : (isRed ? (inMonth ? .sunRed : Color.sunRed.opacity(0.35)) : (inMonth ? .ink : Color.ink.opacity(0.3))))
                            .frame(width: 16, height: 16)                 // ★ 항상 고정 → 오늘 동그라미 정렬
                            .background(isToday ? Circle().fill(Color.terra) : nil)
                        HStack(spacing: 1) { ForEach(Array(hasEvent(d).enumerated()), id: \.offset) { _, c in
                            Circle().fill(c).frame(width: 3, height: 3) } }.frame(height: 4)
                    }
                    .background { if let h = mh { RoundedRectangle(cornerRadius: 3).fill(Color(hexStr: h.c).opacity(inMonth ? 0.30 : 0.14)) } }
                }
            }
        }
    }
}

// MARK: - 위젯 엔트리 뷰 (패밀리별 분기)

// 방 없음(로그인/입장 전) 안내 화면 — 위젯 공통
struct EmptyStateView: View {
    var body: some View {
        VStack(spacing: 3) {
            Text("우리 캘린더").font(.system(size: 14, weight: .bold)).foregroundColor(.terra)
            Text("앱을 열어 방에 입장해 주세요").font(.system(size: 11)).foregroundColor(.mutedBrown)
        }.frame(maxWidth: .infinity, maxHeight: .infinity).widgetBg()
    }
}

extension View {
    @ViewBuilder func widgetBg() -> some View {
        if #available(iOSApplicationExtension 17.0, *) { self.containerBackground(Color.cream, for: .widget) }
        else { self.background(Color.cream) }
    }
}

// MARK: - 위젯 정의

// 크기(패밀리)에 따라 종류가 바뀌는 적응형 뷰 —
//  iOS 18: 홈에서 위젯 모서리(오른쪽 아래)를 드래그해 크기를 바꾸면
//  작게=오늘 / 중간=2주 / 크게=한 달 로 종류가 자동 전환된다.
//  (한 위젯이 여러 패밀리를 지원해야 iOS18 드래그 리사이즈가 활성화됨.)
struct AdaptiveCalView: View {
    @Environment(\.widgetFamily) var family
    let entry: CalEntry
    var body: some View {
        Group {
            if let room = entry.room {
                switch family {
                case .systemSmall:
                    TodayView(room: room, filter: entry.memberFilter, myUserId: entry.myUserId)
                case .systemMedium:
                    GridView(room: room, filter: entry.memberFilter, weeks: 2, weekNav: true, gridV: entry.gridV, gridH: entry.gridH, myUserId: entry.myUserId, holidays: entry.holidays)
                default:
                    GridView(room: room, filter: entry.memberFilter, weeks: 6, monthNav: true, gridV: entry.gridV, gridH: entry.gridH, myUserId: entry.myUserId, holidays: entry.holidays)
                }
            } else { EmptyStateView() }
        }.opacity(entry.flash ? 0.4 : 1)
    }
}

// ① 우리 캘린더 — 한 위젯이 3가지 크기 지원(오늘/2주/월). iOS18 드래그 리사이즈로 종류 전환.
struct CalendarWidget: Widget {
    var body: some WidgetConfiguration {
        AppIntentConfiguration(kind: "UriCalendar", intent: CalConfigIntent.self, provider: CalProvider()) { entry in
            AdaptiveCalView(entry: entry)
        }
        .configurationDisplayName("우리 캘린더")
        .description("작게=오늘, 중간=2주, 크게=한 달 달력. 위젯 모서리를 드래그해 크기를 바꾸면 종류가 자동 전환돼요.")
        .supportedFamilies([.systemSmall, .systemMedium, .systemLarge])
        .contentMarginsDisabled()
    }
}
// ② 다가오는 일정 + 미니 달력 (큰 위젯) — 별도 종류
struct ComboWidget: Widget {
    var body: some WidgetConfiguration {
        AppIntentConfiguration(kind: "UriCombo", intent: CalConfigIntent.self, provider: CalProvider()) { entry in
            Group { if let room = entry.room { ComboView(room: room, filter: entry.memberFilter, myUserId: entry.myUserId, holidays: entry.holidays) } else { EmptyStateView() } }.opacity(entry.flash ? 0.4 : 1)
        }
        .configurationDisplayName("다가오는 일정")
        .description("다가오는 일정 목록 + 이번 달 미니 달력.")
        .supportedFamilies([.systemLarge])
        .contentMarginsDisabled()
    }
}

@main
struct UriCalendarWidgetBundle: WidgetBundle {
    var body: some Widget {
        CalendarWidget()
        ComboWidget()
    }
}

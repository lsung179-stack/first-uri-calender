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
}
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
struct WGEvent: Codable { let date: String; let title: String; let time: String; let color: String; let userId: String? }
struct WGTodo: Codable, Identifiable { let id: String; let date: String; let title: String; let time: String; let color: String; let done: Bool }

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
    func entities(for identifiers: [String]) async throws -> [MemberEntity] {
        allMembers().filter { identifiers.contains($0.id) }
    }
    func suggestedEntities() async throws -> [MemberEntity] { allMembers() }
    func defaultResult() async -> MemberEntity? { MemberEntity(id: "", name: "전체 보기") }
    // 모든 방의 실제 멤버(가상 제외)를 userId로 중복 제거해 나열. 맨 앞에 '전체 보기'.
    private func allMembers() -> [MemberEntity] {
        var out: [MemberEntity] = [MemberEntity(id: "", name: "전체 보기")]
        var seen = Set<String>()
        for r in (loadWGData()?.rooms ?? []) {
            for m in r.members {
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
        // 1) 대기열에 토글 기록 → 앱이 다음 실행/포그라운드에 Supabase 반영
        var pending = ud.stringArray(forKey: "widget.pendingTodoToggles") ?? []
        pending.append(todoId)
        ud.set(pending, forKey: "widget.pendingTodoToggles")
        // 2) 위젯 즉시 반영(낙관): widget.data의 해당 todo.done 뒤집기
        if let raw = ud.string(forKey: DATA_KEY), let d = raw.data(using: .utf8),
           var data = try? JSONDecoder().decode(WGData.self, from: d) {
            data = flipTodo(data, id: todoId)
            if let enc = try? JSONEncoder().encode(data), let s = String(data: enc, encoding: .utf8) {
                ud.set(s, forKey: DATA_KEY)
            }
        }
        return .result()
    }
    private func flipTodo(_ data: WGData, id: String) -> WGData {
        let rooms = data.rooms.map { r -> WGRoom in
            let todos = r.todos.map { t in
                t.id == id ? WGTodo(id: t.id, date: t.date, title: t.title, time: t.time, color: t.color, done: !t.done) : t
            }
            return WGRoom(id: r.id, name: r.name, seal: r.seal, sealPng: r.sealPng, members: r.members, events: r.events, todos: todos)
        }
        return WGData(updatedAt: data.updatedAt, currentRoomId: data.currentRoomId, myUserId: data.myUserId, rooms: rooms)
    }
}
// (WGData 등은 이미 Codable → Encodable 자동 충족. JSONEncoder().encode 그대로 사용)

// MARK: - 타임라인

struct CalEntry: TimelineEntry {
    let date: Date
    let room: WGRoom?
    let memberFilter: String?   // userId or nil(전체)
    var myUserId: String? = nil // 현재 사용자 (작은 위젯 기본 필터)
}
struct CalProvider: AppIntentTimelineProvider {
    func placeholder(in context: Context) -> CalEntry {
        CalEntry(date: Date(), room: sampleRoom(), memberFilter: nil, myUserId: "u1")
    }
    func snapshot(for configuration: CalConfigIntent, in context: Context) async -> CalEntry {
        let room = effectiveRoom(configuration.room?.id) ?? sampleRoom()
        return CalEntry(date: Date(), room: room, memberFilter: effectiveFilter(configuration.member), myUserId: loadWGData()?.myUserId)
    }
    func timeline(for configuration: CalConfigIntent, in context: Context) async -> Timeline<CalEntry> {
        let room = effectiveRoom(configuration.room?.id)
        let entry = CalEntry(date: Date(), room: room, memberFilter: effectiveFilter(configuration.member), myUserId: loadWGData()?.myUserId)
        // 자정에 '오늘'이 넘어가므로 자정 직후 갱신 예약(그 외는 앱이 reloadAllTimelines)
        let mid = Calendar.current.startOfDay(for: Calendar.current.date(byAdding: .day, value: 1, to: Date())!)
        return Timeline(entries: [entry], policy: .after(mid))
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

// MARK: - 공통 헤더 (좌 프로필+멤버 / 우 방 선택은 위젯 편집이므로 이름만 표시)

struct WGHeader: View {
    let room: WGRoom
    var compact: Bool = false
    var active: String? = nil          // 현재 선택된 멤버 userId (nil=전체)
    var monthNav: Bool = false         // 월 위젯 이전/다음달 < >
    var monthLabel: String = ""
    private var sealSize: CGFloat { compact ? 22 : 26 }
    private var avSize: CGFloat { compact ? 19 : 21 }
    private var maxAvatars: Int { compact ? 3 : 4 }
    var body: some View {
        HStack(spacing: 6) {
            // 씰 = 방 프로필. 탭하면 다음 방으로 전환(방 여러 개일 때). 실제 앱 씰 이미지.
            Button(intent: CycleRoomIntent()) {
                SealIcon(seal: room.seal, name: room.name, pngDataURL: room.sealPng, size: sealSize)
            }.buttonStyle(.plain)
            // 멤버 아바타 = 그 사람만 보기 (탭). 겹치지 않게 간격, 사진 있으면 사진.
            HStack(spacing: 4) {
                ForEach(Array(room.members.filter { ($0.userId ?? "").isEmpty == false }.prefix(maxAvatars).enumerated()), id: \.offset) { _, m in
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
            } else {
                Text(room.name).font(.system(size: compact ? 12 : 14, weight: .heavy)).foregroundColor(.ink).lineLimit(1)
            }
            // ＋ 빠른 추가 (앱의 추가 화면 딥링크)
            Link(destination: URL(string: "com.lsung.uricalendar://add?room=\(room.id)")!) {
                ZStack { Circle().fill(Color.terra).frame(width: 22, height: 22)
                    Image(systemName: "plus").font(.system(size: 12, weight: .bold)).foregroundColor(.white) }
            }
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
    var todaysTodos: [WGTodo] { room.todos.filter { $0.date == todayStr() } }
    var body: some View {
        VStack(alignment: .leading, spacing: 5) {
            HStack(spacing: 4) {
                Text("오늘").font(.system(size: 15, weight: .black)).foregroundColor(.terra)
                if !todays.isEmpty {
                    Text("\(todays.count)").font(.system(size: 10, weight: .black)).foregroundColor(.cream)
                        .padding(.horizontal, 5).background(Capsule().fill(Color.terra))
                }
                Spacer()
                Link(destination: URL(string: "com.lsung.uricalendar://add?room=\(room.id)")!) {
                    Image(systemName: "plus.circle.fill").font(.system(size: 17)).foregroundColor(.terra)
                }
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
    var body: some View {
        HStack(spacing: 8) {
            if #available(iOS 17.0, *) {
                Button(intent: ToggleTodoIntent(todoId: t.id)) { checkbox } .buttonStyle(.plain)
            } else { checkbox }
            Text(t.title).font(.system(size: 12, weight: .medium))
                .foregroundColor(t.done ? .mutedBrown : .ink).strikethrough(t.done)
            Spacer(minLength: 2)
            if !t.time.isEmpty { Text(t.time).font(.system(size: 10).monospacedDigit()).foregroundColor(.mutedBrown) }
        }
    }
    var checkbox: some View {
        RoundedRectangle(cornerRadius: 5).stroke(Color.terra, lineWidth: 2).frame(width: 16, height: 16)
            .background(t.done ? RoundedRectangle(cornerRadius: 5).fill(Color.terra) : nil)
            .overlay(t.done ? Image(systemName: "checkmark").font(.system(size: 10, weight: .black)).foregroundColor(.white) : nil)
    }
}

// MARK: - ② 2주 캘린더 (medium) / ④ 월 그리드 (large) 공통 그리드

struct GridView: View {
    let room: WGRoom; let filter: String?; let weeks: Int   // 2 or 6
    var monthNav: Bool = false        // 월 위젯: 이전/다음달 이동
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
    var startDate: Date {
        let cal = Calendar.current
        if weeks == 2 { // 이번 주 일요일부터
            let today = cal.startOfDay(for: Date())
            let wd = cal.component(.weekday, from: today) - 1
            return cal.date(byAdding: .day, value: -wd, to: today)!
        } else { // 기준 달 1일이 포함된 주의 일요일
            let comp = cal.dateComponents([.year, .month], from: monthDate)
            let first = cal.date(from: comp)!
            let wd = cal.component(.weekday, from: first) - 1
            return cal.date(byAdding: .day, value: -wd, to: first)!
        }
    }
    // 특정 날짜의 '바' 목록 — 기간 일정이 이어져 보이도록 좌/우 연속 여부 계산.
    func bars(_ d: Date) -> [DayBar] {
        let cal = Calendar.current
        let s = fmt(d)
        let prev = fmt(cal.date(byAdding: .day, value: -1, to: d)!)
        let next = fmt(cal.date(byAdding: .day, value: 1, to: d)!)
        let evs = dedupeEvents(room.events.filter { $0.date == s && (filter == nil || $0.userId == filter) })
        return evs.map { e -> DayBar in
            let cl = room.events.contains { $0.date == prev && $0.title == e.title && $0.color == e.color && (filter == nil || $0.userId == filter) }
            let cr = room.events.contains { $0.date == next && $0.title == e.title && $0.color == e.color && (filter == nil || $0.userId == filter) }
            return DayBar(title: e.title, color: e.color, contLeft: cl, contRight: cr)
        }
    }
    var body: some View {
        let cal = Calendar.current
        let cols = Array(repeating: GridItem(.flexible(), spacing: 0), count: 7)   // 가로 간격 0 → 기간 바 이어짐
        VStack(spacing: 0) {
            WGHeader(room: room, compact: weeks > 2, active: filter, monthNav: monthNav, monthLabel: monthLabel)
            Color.clear.frame(height: weeks > 2 ? 9 : 7)     // 헤더 ↔ 달력 사이 여백(위아래 균형)
            HStack(spacing: 0) {
                ForEach(0..<7) { i in
                    Text(["일","월","화","수","목","금","토"][i]).font(.system(size: 10, weight: .bold))
                        .foregroundColor(i == 0 ? .sunRed : .mutedBrown).frame(maxWidth: .infinity)
                }
            }.padding(.bottom, 2)
            LazyVGrid(columns: cols, spacing: 2) {
                ForEach(0..<(weeks*7), id: \.self) { i in
                    let d = cal.date(byAdding: .day, value: i, to: startDate)!
                    DayCell(date: d, bars: bars(d), isToday: fmt(d) == todayStr(),
                            dow: cal.component(.weekday, from: d) - 1, dense: weeks > 2, roomId: room.id)
                }
            }
        }.padding(.horizontal, weeks > 2 ? 12 : 13).padding(.vertical, weeks > 2 ? 10 : 11).widgetBg()
    }
}
// 하루치 바(기간 연속 정보 포함)
struct DayBar: Identifiable { let id = UUID(); let title: String; let color: String; let contLeft: Bool; let contRight: Bool }
struct DayCell: View {
    let date: Date; let bars: [DayBar]; let isToday: Bool; let dow: Int; let dense: Bool; let roomId: String
    // 날짜 숫자는 '항상' 같은 크기 상자에 담아, 오늘 동그라미가 다른 날 일정바를 밀지 않게 함.
    private var numBox: CGFloat { dense ? 19 : 22 }
    private var maxBars: Int { dense ? 2 : 2 }
    // 기간 바: 이어지는 쪽은 패딩 0 + 각지게, 끝나는 쪽만 여백+둥글게 → 옆칸 바와 맞닿아 연속으로 보임
    @ViewBuilder private func barView(_ b: DayBar) -> some View {
        let showTitle = (!b.contLeft || dow == 0)   // 시작일 또는 주 시작에만 제목
        Text(showTitle ? b.title : " ").font(.system(size: dense ? 8.5 : 10, weight: .bold))
            .foregroundColor(.white).lineLimit(1)
            .padding(.leading, b.contLeft ? 0 : 3).padding(.trailing, b.contRight ? 0 : 3)
            .padding(.vertical, 0.5).frame(maxWidth: .infinity, alignment: .leading)
            .background(
                UnevenRoundedRectangle(
                    topLeadingRadius: b.contLeft ? 0 : 3, bottomLeadingRadius: b.contLeft ? 0 : 3,
                    bottomTrailingRadius: b.contRight ? 0 : 3, topTrailingRadius: b.contRight ? 0 : 3
                ).fill(Color(hexStr: b.color))
            )
    }
    var body: some View {
        // 날짜 탭 → 앱의 그 날짜 열기(위젯은 스크롤 불가 → 많은 일정은 앱에서 전부 보기)
        Link(destination: URL(string: "com.lsung.uricalendar://open?room=\(roomId)&date=\(fmt(date))")!) {
            VStack(spacing: 2) {
                Text("\(Calendar.current.component(.day, from: date))")
                    .font(.system(size: dense ? 11 : 13, weight: .semibold))
                    .foregroundColor(isToday ? .white : (dow == 0 ? .sunRed : .ink))
                    .frame(width: numBox, height: numBox)                 // ★ 항상 고정 → 모든 날짜 같은 라인
                    .background(isToday ? Circle().fill(Color.terra) : nil)
                ForEach(bars.prefix(maxBars)) { b in barView(b) }
                if bars.count > maxBars {
                    Text("+\(bars.count - maxBars)").font(.system(size: dense ? 7.5 : 9, weight: .bold)).foregroundColor(.mutedBrown)
                }
                Spacer(minLength: 0)
            }.frame(maxWidth: .infinity, minHeight: dense ? 40 : 50, alignment: .top)
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
struct ComboView: View {
    let room: WGRoom; let filter: String?
    // 오늘부터 앞으로의 일정(날짜→시간 순). 오늘만이 아니라 다음날들도 포함.
    var upcoming: [WGEvent] {
        let t = todayStr()
        return dedupeEvents(room.events.filter { $0.date >= t && (filter == nil || $0.userId == filter) }
            .sorted { ($0.date, $0.time.isEmpty ? "zz" : $0.time) < ($1.date, $1.time.isEmpty ? "zz" : $1.time) })
    }
    var body: some View {
        VStack(spacing: 8) {
            WGHeader(room: room, active: filter)
            HStack(alignment: .top, spacing: 14) {
                VStack(alignment: .leading, spacing: 7) {
                    Text("다가오는 일정").font(.system(size: 14, weight: .black)).foregroundColor(.terra)
                    if upcoming.isEmpty {
                        Text("예정된 일정이 없어요").font(.system(size: 12)).foregroundColor(.mutedBrown).padding(.top, 4)
                    } else {
                        ForEach(Array(upcoming.prefix(6).enumerated()), id: \.offset) { _, e in
                            HStack(spacing: 7) {
                                RoundedRectangle(cornerRadius: 2).fill(Color(hexStr: e.color)).frame(width: 3, height: 24)
                                VStack(alignment: .leading, spacing: 1) {
                                    HStack(spacing: 5) {
                                        Text(dayLabel(e.date))
                                            .font(.system(size: 9.5, weight: .bold))
                                            .foregroundColor(e.date == todayStr() ? .cream : .mutedBrown)
                                            .padding(.horizontal, 5).padding(.vertical, 1)
                                            .background(Capsule().fill(e.date == todayStr() ? Color.terra : Color.mutedBrown.opacity(0.18)))
                                        if !e.time.isEmpty { Text(e.time).font(.system(size: 10).monospacedDigit()).foregroundColor(.mutedBrown) }
                                    }
                                    Text(e.title).font(.system(size: 13.5, weight: .bold)).foregroundColor(.ink).lineLimit(1)
                                }
                            }
                        }
                    }
                    Spacer(minLength: 0)
                    ForEach(room.todos.filter { $0.date == todayStr() && !$0.done }.prefix(1)) { t in TodoRow(t: t) }
                }.frame(maxWidth: .infinity, alignment: .leading)
                Rectangle().fill(Color.mutedBrown.opacity(0.2)).frame(width: 1)
                MiniMonth(room: room, filter: filter).frame(maxWidth: .infinity)
            }
        }.padding(16).widgetBg()
    }
}
struct MiniMonth: View {
    let room: WGRoom; let filter: String?
    func hasEvent(_ d: Date) -> [Color] {
        let s = fmt(d)
        return dedupeEvents(room.events.filter { $0.date == s && (filter == nil || $0.userId == filter) }).prefix(3).map { Color(hexStr: $0.color) }
    }
    var body: some View {
        let cal = Calendar.current
        let comp = cal.dateComponents([.year, .month], from: Date())
        let first = cal.date(from: comp)!
        let wd = cal.component(.weekday, from: first) - 1
        let start = cal.date(byAdding: .day, value: -wd, to: first)!
        let cols = Array(repeating: GridItem(.flexible(), spacing: 1), count: 7)
        VStack(alignment: .leading, spacing: 4) {
            Text("\(cal.component(.month, from: Date()))월").font(.system(size: 15, weight: .bold)).foregroundColor(.ink)
            HStack(spacing: 0) { ForEach(0..<7) { i in
                Text(["일","월","화","수","목","금","토"][i]).font(.system(size: 8, weight: .bold))
                    .foregroundColor(i == 0 ? .sunRed : .mutedBrown).frame(maxWidth: .infinity) } }
            LazyVGrid(columns: cols, spacing: 2) {
                ForEach(0..<35, id: \.self) { i in
                    let d = cal.date(byAdding: .day, value: i, to: start)!
                    let inMonth = cal.component(.month, from: d) == cal.component(.month, from: Date())
                    let isToday = fmt(d) == todayStr()
                    VStack(spacing: 1) {
                        Text("\(cal.component(.day, from: d))").font(.system(size: 10, weight: .semibold))
                            .foregroundColor(isToday ? .white : (inMonth ? .ink : Color.ink.opacity(0.3)))
                            .frame(width: 16, height: 16)                 // ★ 항상 고정 → 오늘 동그라미 정렬
                            .background(isToday ? Circle().fill(Color.terra) : nil)
                        HStack(spacing: 1) { ForEach(Array(hasEvent(d).enumerated()), id: \.offset) { _, c in
                            Circle().fill(c).frame(width: 3, height: 3) } }.frame(height: 4)
                    }
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

// MARK: - 위젯 정의 (종류별로 '분리' — 위젯 갤러리에 각각 따로 노출)

// ① 오늘 (작은 위젯)
struct TodayWidget: Widget {
    var body: some WidgetConfiguration {
        AppIntentConfiguration(kind: "UriToday", intent: CalConfigIntent.self, provider: CalProvider()) { entry in
            if let room = entry.room { TodayView(room: room, filter: entry.memberFilter, myUserId: entry.myUserId) } else { EmptyStateView() }
        }
        .configurationDisplayName("오늘 일정")
        .description("오늘의 일정과 할 일을 한눈에. (기본: 내 일정)")
        .supportedFamilies([.systemSmall])
        .contentMarginsDisabled()
    }
}
// ② 2주 달력 (중간 위젯)
struct TwoWeekWidget: Widget {
    var body: some WidgetConfiguration {
        AppIntentConfiguration(kind: "UriTwoWeek", intent: CalConfigIntent.self, provider: CalProvider()) { entry in
            if let room = entry.room { GridView(room: room, filter: entry.memberFilter, weeks: 2) } else { EmptyStateView() }
        }
        .configurationDisplayName("2주 달력")
        .description("이번 주·다음 주 2주치 달력.")
        .supportedFamilies([.systemMedium])
        .contentMarginsDisabled()
    }
}
// ③ 다가오는 일정 + 미니 달력 (큰 위젯)
struct ComboWidget: Widget {
    var body: some WidgetConfiguration {
        AppIntentConfiguration(kind: "UriCombo", intent: CalConfigIntent.self, provider: CalProvider()) { entry in
            if let room = entry.room { ComboView(room: room, filter: entry.memberFilter) } else { EmptyStateView() }
        }
        .configurationDisplayName("다가오는 일정")
        .description("다가오는 일정 목록 + 이번 달 미니 달력.")
        .supportedFamilies([.systemLarge])
        .contentMarginsDisabled()
    }
}
// ④ 한 달 전체 달력 (큰 위젯)
struct MonthWidget: Widget {
    var body: some WidgetConfiguration {
        AppIntentConfiguration(kind: "UriMonth", intent: CalConfigIntent.self, provider: CalProvider()) { entry in
            if let room = entry.room { GridView(room: room, filter: entry.memberFilter, weeks: 6, monthNav: true) } else { EmptyStateView() }
        }
        .configurationDisplayName("한 달 달력")
        .description("이번 달 전체 달력을 크게.")
        .supportedFamilies([.systemLarge])
        .contentMarginsDisabled()
    }
}

@main
struct UriCalendarWidgetBundle: WidgetBundle {
    var body: some Widget {
        TodayWidget()
        TwoWeekWidget()
        ComboWidget()
        MonthWidget()
    }
}

// 우리 캘린더 — iOS 홈 화면 위젯 (WidgetKit)
// ⚠️ 빌드 전 코드: 이 환경에선 컴파일/실행 불가. 다음 빌드(Xcode/Codemagic) 때 검증.
// 데이터: 앱(bridge.js)이 App Group UserDefaults(group.com.lsung.uricalendar)의
//   "widget.data" 키에 기록한 JSON을 읽는다. 스키마는 widget/bridge.js 주석 참조.
// 위젯 4종: 오늘(small) · 2주 캘린더(medium) · 콤보/월(large, 편집 전환).
// 공통: 방 선택(AppIntent) + 멤버 필터 + ＋추가(딥링크) + 할일 체크(AppIntent).

import WidgetKit
import SwiftUI
import AppIntents

// MARK: - 데이터 모델 (bridge.js 스키마)

struct WGData: Codable {
    let updatedAt: Double?
    let currentRoomId: String?
    let rooms: [WGRoom]
}
struct WGRoom: Codable, Identifiable {
    let id: String
    let name: String
    let seal: String?
    let members: [WGMember]
    let events: [WGEvent]
    let todos: [WGTodo]
}
struct WGMember: Codable { let userId: String?; let name: String; let color: String }
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

// MARK: - 설정 인텐트 (방 선택 + 멤버 필터)

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
struct CalConfigIntent: WidgetConfigurationIntent {
    static var title: LocalizedStringResource = "우리 캘린더 위젯"
    static var description = IntentDescription("볼 방과 멤버를 선택하세요.")
    @Parameter(title: "방") var room: RoomEntity?
    // 멤버 필터: 비우면 전체. userId 문자열(없으면 '나만'은 앱이 currentUser로 처리).
    @Parameter(title: "이 멤버만 (선택)") var memberUserId: String?
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
            return WGRoom(id: r.id, name: r.name, seal: r.seal, members: r.members, events: r.events, todos: todos)
        }
        return WGData(updatedAt: data.updatedAt, currentRoomId: data.currentRoomId, rooms: rooms)
    }
}
extension WGData { func encodableCopy() -> WGData { self } }
extension WGTodo: Encodable {}
extension WGData: Encodable {}
extension WGRoom: Encodable {}
extension WGMember: Encodable {}
extension WGEvent: Encodable {}

// MARK: - 타임라인

struct CalEntry: TimelineEntry {
    let date: Date
    let room: WGRoom?
    let memberFilter: String?   // userId or nil(전체)
}
struct CalProvider: AppIntentTimelineProvider {
    func placeholder(in context: Context) -> CalEntry {
        CalEntry(date: Date(), room: sampleRoom(), memberFilter: nil)
    }
    func snapshot(for configuration: CalConfigIntent, in context: Context) async -> CalEntry {
        let room = pickRoom(loadWGData(), roomId: configuration.room?.id) ?? sampleRoom()
        return CalEntry(date: Date(), room: room, memberFilter: configuration.memberUserId)
    }
    func timeline(for configuration: CalConfigIntent, in context: Context) async -> Timeline<CalEntry> {
        let room = pickRoom(loadWGData(), roomId: configuration.room?.id)
        let entry = CalEntry(date: Date(), room: room, memberFilter: configuration.memberUserId)
        // 자정에 '오늘'이 넘어가므로 자정 직후 갱신 예약(그 외는 앱이 reloadAllTimelines)
        let mid = Calendar.current.startOfDay(for: Calendar.current.date(byAdding: .day, value: 1, to: Date())!)
        return Timeline(entries: [entry], policy: .after(mid))
    }
}
func sampleRoom() -> WGRoom {
    WGRoom(id: "s", name: "가족방", seal: "navy:taegeuk",
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
    var body: some View {
        HStack(spacing: 7) {
            SealIcon(seal: room.seal, name: room.name, size: compact ? 20 : 26)
            if !compact {
                HStack(spacing: -5) {
                    ForEach(Array(room.members.prefix(4).enumerated()), id: \.offset) { _, m in
                        Circle().fill(Color(hexStr: m.color)).frame(width: 17, height: 17)
                            .overlay(Text(String(m.name.prefix(1))).font(.system(size: 8, weight: .bold)).foregroundColor(.white))
                            .overlay(Circle().stroke(Color.cream, lineWidth: 1.5))
                    }
                }
            }
            Spacer()
            Text(room.name).font(.system(size: compact ? 12 : 14, weight: .heavy)).foregroundColor(.ink)
            // ＋ 빠른 추가 (앱의 추가 화면 딥링크)
            Link(destination: URL(string: "com.lsung.uricalendar://add?room=\(room.id)")!) {
                ZStack { Circle().fill(Color.terra).frame(width: 22, height: 22)
                    Image(systemName: "plus").font(.system(size: 12, weight: .bold)).foregroundColor(.white) }
            }
        }
    }
}
struct SealIcon: View {
    let seal: String?; let name: String; var size: CGFloat = 26
    var body: some View {
        Circle().fill(LinearGradient(colors: [Color(hexStr: "#7c92b4"), Color(hexStr: "#566f8f")], startPoint: .topLeading, endPoint: .bottomTrailing))
            .frame(width: size, height: size)
            .overlay(Text(String(name.prefix(1))).font(.system(size: size*0.45, weight: .bold)).foregroundColor(Color(hexStr: "#f3e6cf")))
    }
}

// MARK: - ① 오늘 (small)

struct TodayView: View {
    let room: WGRoom; let filter: String?
    var todays: [WGEvent] { room.events.filter { $0.date == todayStr() && (filter == nil || $0.userId == filter) }
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
    var startDate: Date {
        let cal = Calendar.current
        if weeks == 2 { // 이번 주 일요일부터
            let today = cal.startOfDay(for: Date())
            let wd = cal.component(.weekday, from: today) - 1
            return cal.date(byAdding: .day, value: -wd, to: today)!
        } else { // 이번 달 1일이 포함된 주의 일요일
            let comp = cal.dateComponents([.year, .month], from: Date())
            let first = cal.date(from: comp)!
            let wd = cal.component(.weekday, from: first) - 1
            return cal.date(byAdding: .day, value: -wd, to: first)!
        }
    }
    func eventsOn(_ d: Date) -> [WGEvent] {
        let s = fmt(d)
        return room.events.filter { $0.date == s && (filter == nil || $0.userId == filter) }
    }
    var body: some View {
        let cal = Calendar.current
        let cols = Array(repeating: GridItem(.flexible(), spacing: 1), count: 7)
        VStack(spacing: 3) {
            WGHeader(room: room, compact: weeks > 2)
            HStack(spacing: 0) {
                ForEach(0..<7) { i in
                    Text(["일","월","화","수","목","금","토"][i]).font(.system(size: 10, weight: .bold))
                        .foregroundColor(i == 0 ? .sunRed : .mutedBrown).frame(maxWidth: .infinity)
                }
            }
            LazyVGrid(columns: cols, spacing: 1) {
                ForEach(0..<(weeks*7), id: \.self) { i in
                    let d = cal.date(byAdding: .day, value: i, to: startDate)!
                    DayCell(date: d, events: eventsOn(d), isToday: fmt(d) == todayStr(),
                            dow: cal.component(.weekday, from: d) - 1, dense: weeks > 2)
                }
            }
        }.padding(weeks > 2 ? 12 : 13).widgetBg()
    }
}
struct DayCell: View {
    let date: Date; let events: [WGEvent]; let isToday: Bool; let dow: Int; let dense: Bool
    var body: some View {
        VStack(spacing: 1) {
            Text("\(Calendar.current.component(.day, from: date))")
                .font(.system(size: dense ? 11 : 13, weight: .semibold))
                .foregroundColor(isToday ? .white : (dow == 0 ? .sunRed : .ink))
                .frame(width: isToday ? (dense ? 17 : 20) : nil, height: isToday ? (dense ? 17 : 20) : nil)
                .background(isToday ? Circle().fill(Color.terra) : nil)
            ForEach(Array(events.prefix(dense ? 1 : 2).enumerated()), id: \.offset) { _, e in
                Text(e.title).font(.system(size: dense ? 8 : 10, weight: .bold)).foregroundColor(.white).lineLimit(1)
                    .padding(.horizontal, 3).frame(maxWidth: .infinity)
                    .background(RoundedRectangle(cornerRadius: 3).fill(Color(hexStr: e.color)))
            }
            Spacer(minLength: 0)
        }.frame(maxWidth: .infinity, minHeight: dense ? 30 : 44, alignment: .top)
    }
}

// MARK: - ③ 콤보 (large): 오늘 리스트 + 미니 월

struct ComboView: View {
    let room: WGRoom; let filter: String?
    var todays: [WGEvent] { room.events.filter { $0.date == todayStr() && (filter == nil || $0.userId == filter) }
        .sorted { ($0.time.isEmpty ? "zz" : $0.time) < ($1.time.isEmpty ? "zz" : $1.time) } }
    var body: some View {
        VStack(spacing: 8) {
            WGHeader(room: room)
            HStack(alignment: .top, spacing: 14) {
                VStack(alignment: .leading, spacing: 8) {
                    Text("오늘").font(.system(size: 15, weight: .black)).foregroundColor(.terra)
                    ForEach(Array(todays.prefix(4).enumerated()), id: \.offset) { _, e in
                        VStack(alignment: .leading, spacing: 1) {
                            HStack(spacing: 7) {
                                RoundedRectangle(cornerRadius: 2).fill(Color(hexStr: e.color)).frame(width: 3, height: 26)
                                VStack(alignment: .leading, spacing: 1) {
                                    Text(e.title).font(.system(size: 14, weight: .bold)).foregroundColor(.ink).lineLimit(1)
                                    if !e.time.isEmpty { Text(e.time).font(.system(size: 11)).foregroundColor(.mutedBrown) }
                                }
                            }
                        }
                    }
                    Spacer(minLength: 0)
                    ForEach(room.todos.filter { $0.date == todayStr() }.prefix(2)) { t in TodoRow(t: t) }
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
        return room.events.filter { $0.date == s && (filter == nil || $0.userId == filter) }.prefix(3).map { Color(hexStr: $0.color) }
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
                            .frame(width: isToday ? 16 : nil, height: isToday ? 16 : nil)
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

struct UriCalendarEntryView: View {
    @Environment(\.widgetFamily) var family
    let entry: CalEntry
    var body: some View {
        if let room = entry.room {
            switch family {
            case .systemSmall: TodayView(room: room, filter: entry.memberFilter)
            case .systemMedium: GridView(room: room, filter: entry.memberFilter, weeks: 2)
            case .systemLarge: ComboView(room: room, filter: entry.memberFilter)  // 편집서 월(GridView weeks:6)로 전환 옵션
            default: TodayView(room: room, filter: entry.memberFilter)
            }
        } else {
            VStack { Text("우리 캘린더").font(.system(size: 14, weight: .bold)).foregroundColor(.terra)
                Text("앱에서 방에 입장해 주세요").font(.system(size: 11)).foregroundColor(.mutedBrown) }
                .frame(maxWidth: .infinity, maxHeight: .infinity).widgetBg()
        }
    }
}

extension View {
    @ViewBuilder func widgetBg() -> some View {
        if #available(iOSApplicationExtension 17.0, *) { self.containerBackground(Color.cream, for: .widget) }
        else { self.background(Color.cream) }
    }
}

// MARK: - 위젯 정의

struct UriCalendarWidget: Widget {
    var body: some WidgetConfiguration {
        AppIntentConfiguration(kind: "UriCalendarWidget", intent: CalConfigIntent.self, provider: CalProvider()) { entry in
            UriCalendarEntryView(entry: entry)
        }
        .configurationDisplayName("우리 캘린더")
        .description("오늘·2주·한 달 일정을 홈 화면에서. 방과 멤버를 골라보세요.")
        .supportedFamilies([.systemSmall, .systemMedium, .systemLarge])
    }
}

@main
struct UriCalendarWidgetBundle: WidgetBundle {
    var body: some Widget { UriCalendarWidget() }
}

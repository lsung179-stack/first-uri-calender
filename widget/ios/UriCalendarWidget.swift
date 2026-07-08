// 우리 캘린더 — iOS 홈 화면 위젯 (WidgetKit)
// 데이터: 앱(WebView JS)이 App Group UserDefaults에 저장한 JSON을 읽는다.
//   suite: group.com.lsung.uricalendar / key: "widget.events"
//   형식: {"updatedAt":"ISO8601","roomName":"가족방","days":[{"date":"2026-07-08","label":"오늘","events":[{"title":"...","time":"14:00"|null,"color":"#b55c46"}]}]}
// 갱신: 앱이 일정 로드/저장 시마다 JSON 갱신 + WidgetCenter reload. 타임라인은 자정마다 롤오버.

import WidgetKit
import SwiftUI

// MARK: - 데이터 모델

struct WidgetEvent: Codable, Identifiable {
    var id: String { title + (time ?? "") }
    let title: String
    let time: String?
    let color: String?
}

struct WidgetDay: Codable {
    let date: String          // YYYY-MM-DD
    let label: String?        // "오늘"/"내일" (앱이 넣어줌, 없으면 날짜로 계산)
    let events: [WidgetEvent]
}

struct WidgetPayload: Codable {
    let updatedAt: String?
    let roomName: String?
    let days: [WidgetDay]
}

let APP_GROUP_ID = "group.com.lsung.uricalendar"
let PAYLOAD_KEY = "widget.events"

func loadPayload() -> WidgetPayload? {
    guard let ud = UserDefaults(suiteName: APP_GROUP_ID),
          let raw = ud.string(forKey: PAYLOAD_KEY),
          let data = raw.data(using: .utf8) else { return nil }
    return try? JSONDecoder().decode(WidgetPayload.self, from: data)
}

// MARK: - 타임라인

struct CalEntry: TimelineEntry {
    let date: Date
    let payload: WidgetPayload?
}

struct CalProvider: TimelineProvider {
    func placeholder(in context: Context) -> CalEntry {
        CalEntry(date: Date(), payload: WidgetPayload(
            updatedAt: nil, roomName: "가족방",
            days: [WidgetDay(date: "", label: "오늘", events: [
                WidgetEvent(title: "가족 저녁 식사", time: "19:00", color: "#b55c46"),
                WidgetEvent(title: "병원 예약", time: "10:30", color: "#5d7291"),
            ])]))
    }
    func getSnapshot(in context: Context, completion: @escaping (CalEntry) -> Void) {
        completion(CalEntry(date: Date(), payload: loadPayload() ?? placeholder(in: context).payload))
    }
    func getTimeline(in context: Context, completion: @escaping (Timeline<CalEntry>) -> Void) {
        let entry = CalEntry(date: Date(), payload: loadPayload())
        // 자정에 '오늘'이 바뀌므로 자정 직후 갱신 예약 (그 외 갱신은 앱이 reloadAllTimelines로 트리거)
        let midnight = Calendar.current.startOfDay(for: Calendar.current.date(byAdding: .day, value: 1, to: Date())!)
        completion(Timeline(entries: [entry], policy: .after(midnight)))
    }
}

// MARK: - 빈티지 팔레트 (앱 테마와 동일 계열)

extension Color {
    init(hex: String) {
        var s = hex.trimmingCharacters(in: .alphanumerics.inverted)
        if s.count == 3 { s = s.map { "\($0)\($0)" }.joined() }
        let v = UInt64(s, radix: 16) ?? 0x888888
        self.init(.sRGB,
                  red: Double((v >> 16) & 0xff) / 255,
                  green: Double((v >> 8) & 0xff) / 255,
                  blue: Double(v & 0xff) / 255, opacity: 1)
    }
    static let cream = Color(hex: "#f3e6cf")
    static let paper = Color(hex: "#fbf3e2")
    static let ink = Color(hex: "#2a1c0f")
    static let terra = Color(hex: "#8b3a2a")
    static let mutedBrown = Color(hex: "#8a6c52")
}

// MARK: - 뷰

struct EventRow: View {
    let ev: WidgetEvent
    var body: some View {
        HStack(spacing: 6) {
            RoundedRectangle(cornerRadius: 1.5)
                .fill(Color(hex: ev.color ?? "#8b3a2a"))
                .frame(width: 3, height: 14)
            Text(ev.title)
                .font(.system(size: 12, weight: .semibold))
                .foregroundColor(.ink)
                .lineLimit(1)
            Spacer(minLength: 2)
            if let t = ev.time, !t.isEmpty {
                Text(t)
                    .font(.system(size: 10.5, weight: .medium).monospacedDigit())
                    .foregroundColor(.mutedBrown)
            }
        }
    }
}

struct TodayView: View {
    let payload: WidgetPayload?
    let maxRows: Int
    var today: WidgetDay? { payload?.days.first }

    var body: some View {
        VStack(alignment: .leading, spacing: 5) {
            HStack(spacing: 4) {
                Text(today?.label ?? "오늘")
                    .font(.system(size: 12, weight: .heavy))
                    .foregroundColor(.terra)
                if let n = today?.events.count, n > 0 {
                    Text("\(n)")
                        .font(.system(size: 11, weight: .heavy))
                        .foregroundColor(.cream)
                        .padding(.horizontal, 5).padding(.vertical, 1)
                        .background(Capsule().fill(Color.terra))
                }
                Spacer()
                if let rn = payload?.roomName, !rn.isEmpty {
                    Text(rn).font(.system(size: 10, weight: .semibold)).foregroundColor(.mutedBrown).lineLimit(1)
                }
            }
            if let evs = today?.events, !evs.isEmpty {
                VStack(alignment: .leading, spacing: 4) {
                    ForEach(evs.prefix(maxRows)) { EventRow(ev: $0) }
                }
                if evs.count > maxRows {
                    Text("외 \(evs.count - maxRows)개")
                        .font(.system(size: 10, weight: .medium)).foregroundColor(.mutedBrown)
                }
                Spacer(minLength: 0)
            } else {
                Spacer()
                HStack {
                    Spacer()
                    Text("오늘 일정이 없어요").font(.system(size: 11.5, weight: .medium)).foregroundColor(.mutedBrown)
                    Spacer()
                }
                Spacer()
            }
        }
    }
}

struct WeekView: View {
    let payload: WidgetPayload?
    var body: some View {
        HStack(alignment: .top, spacing: 10) {
            TodayView(payload: payload, maxRows: 4).frame(maxWidth: .infinity)
            Divider().background(Color.mutedBrown.opacity(0.35))
            VStack(alignment: .leading, spacing: 4) {
                let rest = (payload?.days ?? []).dropFirst().prefix(3)
                if rest.isEmpty {
                    Text("다가오는 일정 없음").font(.system(size: 11, weight: .medium)).foregroundColor(.mutedBrown)
                } else {
                    ForEach(Array(rest), id: \.date) { day in
                        HStack(spacing: 5) {
                            Text(day.label ?? String(day.date.suffix(5)))
                                .font(.system(size: 10, weight: .heavy)).foregroundColor(.terra)
                            Text(day.events.first?.title ?? "")
                                .font(.system(size: 11, weight: .semibold)).foregroundColor(.ink).lineLimit(1)
                            if day.events.count > 1 {
                                Text("+\(day.events.count - 1)").font(.system(size: 9.5)).foregroundColor(.mutedBrown)
                            }
                        }
                    }
                }
                Spacer(minLength: 0)
            }.frame(maxWidth: .infinity, alignment: .leading)
        }
    }
}

struct UriCalendarWidgetEntryView: View {
    @Environment(\.widgetFamily) var family
    let entry: CalEntry

    var body: some View {
        Group {
            switch family {
            case .systemMedium: WeekView(payload: entry.payload)
            default: TodayView(payload: entry.payload, maxRows: 3)
            }
        }
        .padding(12)
        .widgetBackground(Color.cream)
    }
}

extension View {
    // iOS 17 containerBackground / 이하 버전 background 호환
    @ViewBuilder func widgetBackground(_ c: Color) -> some View {
        if #available(iOSApplicationExtension 17.0, *) {
            self.containerBackground(c, for: .widget)
        } else {
            self.background(c)
        }
    }
}

// MARK: - 위젯 정의

struct UriCalendarWidget: Widget {
    var body: some WidgetConfiguration {
        StaticConfiguration(kind: "UriCalendarWidget", provider: CalProvider()) { entry in
            UriCalendarWidgetEntryView(entry: entry)
        }
        .configurationDisplayName("우리 캘린더")
        .description("오늘과 다가오는 일정을 홈 화면에서 바로 확인해요.")
        .supportedFamilies([.systemSmall, .systemMedium])
    }
}

@main
struct UriCalendarWidgetBundle: WidgetBundle {
    var body: some Widget {
        UriCalendarWidget()
    }
}

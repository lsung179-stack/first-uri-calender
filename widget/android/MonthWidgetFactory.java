package com.lsung.uricalendar.widget;

/*
 * 월 그리드 셀 어댑터 — SharedPreferences의 widget.data를 읽어 이번 달 35(또는 42)칸을 만든다.
 * 각 칸: 날짜 숫자 + 기간 런(연속 바) 최대 2줄 + 할일 1개 + 넘침(+N). 탭하면 앱 열림.
 *
 * ⚠️ iOS(WidgetKit) GridView.runs/slotsFor와 동일한 계약:
 *   - gid(연결 키)가 있는 이벤트만 연속 날짜로 묶어 '기간 바'로. 없으면(단일·여러날) 각각 단독 칩.
 *     (앱 isSameRangeGroup과 동일 — 제목이 같아도 gid 없으면 절대 안 붙음)
 *   - 실제 날짜 점유(occupancy) 기반 레인(줄) 배정(앱 _computeMonthLanes와 동일) →
 *     같은 일정이 날마다 같은 줄에 놓여 옆칸과 맞닿아 끊기지 않고 이어짐.
 *   - 제목은 런 시작일/주 시작(일요일)에만 표시, 이어지는 칸은 색만.
 */

import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.widget.RemoteViews;
import android.widget.RemoteViewsService;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class MonthWidgetFactory implements RemoteViewsService.RemoteViewsFactory {

    private static final int MAX_LANES = 2;   // 셀 이벤트 줄 수(iOS 월 위젯과 동일)

    private final Context ctx;
    private final List<Cell> cells = new ArrayList<>();

    MonthWidgetFactory(Context c) { this.ctx = c; }

    // 하루치 한 줄의 바(연속 정보 포함) — iOS DayBar 대응
    static class DayBar {
        String title; int color; boolean outline;
        boolean contLeft;    // 왼쪽(전날)으로 이어짐 → 제목 숨김
        boolean contRight;   // 오른쪽(다음날)으로 이어짐
    }

    // 기간 런: 같은 gid의 연속 날짜 묶음 + 배정된 줄(lane) — iOS EvRun 대응
    static class Run {
        String title; int color; boolean outline; int ord;
        String start; String end; int lane;
        Set<String> days = new HashSet<>();
    }

    static class Cell {
        int day; int dow; boolean inMonth; boolean isToday; String key;
        DayBar[] bars = new DayBar[MAX_LANES];
        int overflow;
        List<WidgetData.Todo> todos = new ArrayList<>();
    }

    private int id(String name, String type) {
        return ctx.getResources().getIdentifier(name, type, ctx.getPackageName());
    }

    @Override public void onCreate() {}
    @Override public void onDestroy() { cells.clear(); }
    @Override public int getCount() { return cells.size(); }
    @Override public long getItemId(int position) { return position; }
    @Override public boolean hasStableIds() { return true; }
    @Override public int getViewTypeCount() { return 1; }
    @Override public RemoteViews getLoadingView() { return null; }

    @Override
    public void onDataSetChanged() {
        cells.clear();
        WidgetData.WData data = WidgetData.load(ctx);
        WidgetData.Room room = data != null
            ? data.pickRoom(UriCalendarWidgetProvider.selectedRoomId(ctx)) : null;

        // 오늘 하이라이트는 실제 오늘 기준, 표시 달은 오프셋(‹ ›) 반영
        Calendar real = Calendar.getInstance();
        String todayKey = key(real.get(Calendar.YEAR), real.get(Calendar.MONTH), real.get(Calendar.DAY_OF_MONTH));
        Calendar now = Calendar.getInstance();
        now.add(Calendar.MONTH, UriCalendarWidgetProvider.monthOffset(ctx));
        int year = now.get(Calendar.YEAR);
        int month0 = now.get(Calendar.MONTH);

        Calendar first = Calendar.getInstance();
        first.set(year, month0, 1, 0, 0, 0);
        first.set(Calendar.MILLISECOND, 0);
        int offset = first.get(Calendar.DAY_OF_WEEK) - 1; // 0=일
        int dim = first.getActualMaximum(Calendar.DAY_OF_MONTH);
        int rows = (int) Math.ceil((offset + dim) / 7.0);

        Calendar start = Calendar.getInstance();
        start.set(year, month0, 1, 0, 0, 0);
        start.set(Calendar.MILLISECOND, 0);
        start.add(Calendar.DAY_OF_MONTH, -offset);

        // 보이는 그리드 범위 [startKey, endKey]
        Calendar endCal = (Calendar) start.clone();
        endCal.add(Calendar.DAY_OF_MONTH, rows * 7 - 1);
        String startKey = key(start.get(Calendar.YEAR), start.get(Calendar.MONTH), start.get(Calendar.DAY_OF_MONTH));
        String endKey = key(endCal.get(Calendar.YEAR), endCal.get(Calendar.MONTH), endCal.get(Calendar.DAY_OF_MONTH));

        // 날짜별 할일
        Map<String, List<WidgetData.Todo>> byTodo = new HashMap<>();
        // 기간 런 계산 (iOS runs와 동일)
        List<Run> allRuns = new ArrayList<>();
        if (room != null) {
            // gid 있는 이벤트는 gid별로 날짜 모으고, 없는 것은 단독 런
            Map<String, Run> byGid = new HashMap<>();
            for (WidgetData.Event e : room.events) {
                if (e.date == null || e.date.compareTo(startKey) < 0 || e.date.compareTo(endKey) > 0) continue;
                boolean ol = "outline".equals(e.style);
                if (e.gid != null && !e.gid.isEmpty()) {
                    Run acc = byGid.get(e.gid);
                    if (acc == null) {
                        acc = new Run();
                        acc.title = e.title; acc.color = parseColor(e.color);
                        acc.outline = ol; acc.ord = e.ord;
                        byGid.put(e.gid, acc);
                    }
                    acc.days.add(e.date);
                } else {
                    Run r = new Run();
                    r.title = e.title; r.color = parseColor(e.color); r.outline = ol; r.ord = e.ord;
                    r.start = e.date; r.end = e.date; r.days.add(e.date);
                    allRuns.add(r);
                }
            }
            // gid별 날짜집합을 연속 구간으로 쪼개 각각 런으로
            for (Run acc : byGid.values()) {
                List<String> ds = new ArrayList<>(acc.days);
                Collections.sort(ds);
                int i = 0;
                while (i < ds.size()) {
                    int j = i;
                    while (j + 1 < ds.size() && addDays(ds.get(j), 1).equals(ds.get(j + 1))) j++;
                    Run r = new Run();
                    r.title = acc.title; r.color = acc.color; r.outline = acc.outline; r.ord = acc.ord;
                    r.start = ds.get(i); r.end = ds.get(j);
                    for (int k = i; k <= j; k++) r.days.add(ds.get(k));
                    allRuns.add(r);
                    i = j + 1;
                }
            }
            // 정렬: 기간(여러날) 상단 우선 → order_index → 시작일 → 제목 (앱 _computeMonthLanes)
            Collections.sort(allRuns, (a, b) -> {
                boolean ar = a.end.compareTo(a.start) > 0, br = b.end.compareTo(b.start) > 0;
                if (ar != br) return ar ? -1 : 1;
                if (a.ord != b.ord) return Integer.compare(a.ord, b.ord);
                if (!a.start.equals(b.start)) return a.start.compareTo(b.start);
                String at = a.title == null ? "" : a.title, bt = b.title == null ? "" : b.title;
                return at.compareTo(bt);
            });
            // 점유 기반 레인 배정
            List<Set<String>> occ = new ArrayList<>();
            for (Run r : allRuns) {
                int lane = 0;
                while (lane < occ.size() && !disjoint(occ.get(lane), r.days)) lane++;
                if (lane == occ.size()) occ.add(new HashSet<>(r.days));
                else occ.get(lane).addAll(r.days);
                r.lane = lane;
            }
            // 할일 인덱싱
            for (WidgetData.Todo t : room.todos) {
                List<WidgetData.Todo> l = byTodo.get(t.date);
                if (l == null) { l = new ArrayList<>(); byTodo.put(t.date, l); }
                l.add(t);
            }
        }

        for (int i = 0; i < rows * 7; i++) {
            Calendar d = (Calendar) start.clone();
            d.add(Calendar.DAY_OF_MONTH, i);
            Cell cell = new Cell();
            cell.day = d.get(Calendar.DAY_OF_MONTH);
            cell.dow = d.get(Calendar.DAY_OF_WEEK) - 1;
            cell.inMonth = d.get(Calendar.MONTH) == month0;
            cell.key = key(d.get(Calendar.YEAR), d.get(Calendar.MONTH), cell.day);
            cell.isToday = cell.key.equals(todayKey);

            // 이 날짜를 덮는 런 → 레인 슬롯에 배치, 초과는 넘침
            for (Run r : allRuns) {
                if (r.start.compareTo(cell.key) <= 0 && cell.key.compareTo(r.end) <= 0) {
                    if (r.lane < MAX_LANES) {
                        DayBar b = new DayBar();
                        b.title = r.title; b.color = r.color; b.outline = r.outline;
                        b.contLeft = r.start.compareTo(cell.key) < 0;
                        b.contRight = r.end.compareTo(cell.key) > 0;
                        cell.bars[r.lane] = b;
                    } else {
                        cell.overflow++;
                    }
                }
            }
            List<WidgetData.Todo> tl = byTodo.get(cell.key);
            if (tl != null) cell.todos = tl;
            cells.add(cell);
        }
    }

    @Override
    public RemoteViews getViewAt(int position) {
        RemoteViews rv = new RemoteViews(ctx.getPackageName(), id("widget_cell", "layout"));
        if (position < 0 || position >= cells.size()) return rv;
        Cell cell = cells.get(position);

        int numColor;
        if (!cell.inMonth) numColor = 0x552A1C0F;
        else if (cell.isToday) numColor = 0xFF8B3A2A;
        else if (cell.dow == 0) numColor = 0xFFC0503F;
        else numColor = 0xFF2A1C0F;
        rv.setTextViewText(id("cell_day", "id"), String.valueOf(cell.day));
        rv.setTextColor(id("cell_day", "id"), numColor);

        // 기간 바 2슬롯(레인). 이어지는 칸은 제목 숨김 → 색만 이어져 연속 바처럼 보임.
        // 테두리 일정(outline)=투명 배경+색 글자, 채움=색 배경+대비 글자.
        int[] evViews = { id("cell_ev1", "id"), id("cell_ev2", "id") };
        for (int s = 0; s < MAX_LANES; s++) {
            DayBar b = s < cell.bars.length ? cell.bars[s] : null;
            if (b != null) {
                boolean showTitle = !b.contLeft || cell.dow == 0;   // 시작일/주 시작에만 제목
                rv.setViewVisibility(evViews[s], android.view.View.VISIBLE);
                rv.setTextViewText(evViews[s], showTitle ? (b.title == null ? "" : b.title) : " ");
                if (b.outline) {
                    rv.setInt(evViews[s], "setBackgroundColor", 0x00000000); // 투명
                    rv.setTextColor(evViews[s], b.color);                     // 색 글자(테두리 대체)
                } else {
                    rv.setInt(evViews[s], "setBackgroundColor", b.color);
                    rv.setTextColor(evViews[s], contrastText(b.color));
                }
            } else {
                rv.setViewVisibility(evViews[s], android.view.View.GONE);
            }
        }

        // 할일 1개 (이벤트 아래) — 체크박스 + 제목, 완료면 취소선+흐리게
        if (!cell.todos.isEmpty()) {
            WidgetData.Todo t = cell.todos.get(0);
            String mark = t.done ? "☑ " : "☐ "; // ☑ / ☐
            CharSequence label;
            if (t.done) {
                android.text.SpannableString sp = new android.text.SpannableString(mark + t.title);
                sp.setSpan(new android.text.style.StrikethroughSpan(), mark.length(), sp.length(), 0);
                label = sp;
                rv.setTextColor(id("cell_todo", "id"), 0xFF8A6C52);
            } else {
                label = mark + t.title;
                rv.setTextColor(id("cell_todo", "id"), 0xFF2A1C0F);
            }
            rv.setViewVisibility(id("cell_todo", "id"), android.view.View.VISIBLE);
            rv.setTextViewText(id("cell_todo", "id"), label);
        } else {
            rv.setViewVisibility(id("cell_todo", "id"), android.view.View.GONE);
        }

        int over = cell.overflow + (cell.todos.size() > 1 ? cell.todos.size() - 1 : 0);
        if (over > 0) {
            rv.setViewVisibility(id("cell_more", "id"), android.view.View.VISIBLE);
            rv.setTextViewText(id("cell_more", "id"), "+" + over);
        } else {
            rv.setViewVisibility(id("cell_more", "id"), android.view.View.GONE);
        }

        // 탭 → 앱 (fill-in intent에 날짜 실어 보냄)
        Intent fill = new Intent();
        fill.putExtra("widgetDate", cell.key);
        rv.setOnClickFillInIntent(id("cell_root", "id"), fill);
        return rv;
    }

    private static String key(int y, int m0, int d) {
        return String.format(Locale.US, "%04d-%02d-%02d", y, m0 + 1, d);
    }

    // "yyyy-MM-dd" + n일 → "yyyy-MM-dd"
    static String addDays(String ymd, int n) {
        String[] p = ymd.split("-");
        Calendar c = Calendar.getInstance();
        c.clear();
        c.set(Integer.parseInt(p[0]), Integer.parseInt(p[1]) - 1, Integer.parseInt(p[2]));
        c.add(Calendar.DAY_OF_MONTH, n);
        return key(c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH));
    }

    // 두 날짜집합이 겹치지 않으면 true
    static boolean disjoint(Set<String> a, Set<String> b) {
        Set<String> small = a.size() <= b.size() ? a : b, big = a.size() <= b.size() ? b : a;
        for (String s : small) if (big.contains(s)) return false;
        return true;
    }

    static int parseColor(String hex) {
        try {
            String h = hex == null ? "#8b3a2a" : hex.trim();
            if (!h.startsWith("#")) h = "#" + h;
            return Color.parseColor(h);
        } catch (Exception e) { return 0xFF8B3A2A; }
    }

    // 앱 getContrastTextColor와 동일: 가중 luminance>0.62면 어두운 글자
    static int contrastText(int color) {
        int r = (color >> 16) & 0xff, g = (color >> 8) & 0xff, b = color & 0xff;
        double lum = (0.299 * r + 0.587 * g + 0.114 * b) / 255.0;
        return lum > 0.62 ? 0xFF3A2418 : 0xFFFFFFFF;
    }
}

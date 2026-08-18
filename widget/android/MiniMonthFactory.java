package com.lsung.uricalendar.widget;

/*
 * 콤보(large) 위젯의 우측 미니 월 그리드 — 35칸(5주). 작은 날짜 숫자 + 이벤트 점 최대 3개.
 * comboOffset(달 이동) 반영. 멤버 필터 적용.
 */

import android.content.Context;
import android.content.Intent;
import android.widget.RemoteViews;
import android.widget.RemoteViewsService;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class MiniMonthFactory implements RemoteViewsService.RemoteViewsFactory {

    private final Context ctx;
    private final List<Cell> cells = new ArrayList<>();
    private String roomId = null; // 셀 탭 딥링크(://open?room=&date=)용
    private Map<String,String> holidays = new HashMap<>(); // 빨간날(공휴일)

    MiniMonthFactory(Context c) { this.ctx = c; }

    static class Cell {
        int day; boolean inMonth; boolean isToday; int dow; String key; boolean holiday;
        List<Integer> dots = new ArrayList<>();  // 색상 최대 3
        int hlTint;   // 날짜 강조 옅은 배경색(0=없음)
    }

    private int id(String name, String type) { return WidgetCommon.resId(ctx, name, type); }

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
        WidgetData.Room room = data != null ? data.pickRoom(WidgetCommon.selectedRoomId(ctx)) : null;
        roomId = room != null ? room.id : null;
        holidays = (data != null && data.holidays != null) ? data.holidays : new HashMap<String,String>();
        String filter = WidgetCommon.filterUser(ctx);
        String todayKey = WidgetCommon.todayKey();

        Calendar disp = Calendar.getInstance();
        disp.add(Calendar.MONTH, WidgetCommon.comboOffset(ctx));
        int year = disp.get(Calendar.YEAR), month0 = disp.get(Calendar.MONTH);
        Calendar first = Calendar.getInstance();
        first.clear(); first.set(year, month0, 1);
        int wd = first.get(Calendar.DAY_OF_WEEK) - 1;
        Calendar start = (Calendar) first.clone();
        start.add(Calendar.DAY_OF_MONTH, -wd);

        // 날짜별 이벤트 색(중복 제거 후 최대 3)
        Map<String, List<Integer>> dotByDate = new HashMap<>();
        if (room != null) {
            List<WidgetData.Event> pool = new ArrayList<>();
            for (WidgetData.Event e : room.events) {
                if (filter != null && !filter.equals(e.userId)) continue;
                pool.add(e);
            }
            for (WidgetData.Event e : WidgetCommon.dedupeEvents(pool)) {
                List<Integer> l = dotByDate.get(e.date);
                if (l == null) { l = new ArrayList<>(); dotByDate.put(e.date, l); }
                if (l.size() < 3) l.add(WidgetCommon.parseColor(e.color));
            }
        }

        for (int i = 0; i < 35; i++) {
            Calendar d = (Calendar) start.clone();
            d.add(Calendar.DAY_OF_MONTH, i);
            Cell cell = new Cell();
            cell.day = d.get(Calendar.DAY_OF_MONTH);
            cell.dow = d.get(Calendar.DAY_OF_WEEK) - 1;
            cell.inMonth = d.get(Calendar.MONTH) == month0;
            cell.key = WidgetCommon.fmt(d);
            cell.isToday = cell.key.equals(todayKey);
            cell.holiday = holidays.containsKey(cell.key);
            // 미니 달력은 칸이 너무 작아 테두리/라벨이 뭉개짐 → 강조된 날은 옅은 색 배경으로만 표시(iOS와 동일)
            WidgetData.Highlight _h = WidgetCommon.hlFor(room, cell.key);
            if (_h != null) cell.hlTint = (_h.color & 0x00FFFFFF) | (cell.inMonth ? 0x4D000000 : 0x24000000);
            List<Integer> dots = dotByDate.get(cell.key);
            if (dots != null) cell.dots = dots;
            cells.add(cell);
        }
    }

    @Override
    public RemoteViews getViewAt(int position) {
        RemoteViews rv = new RemoteViews(ctx.getPackageName(), id("widget_mini_cell", "layout"));
        if (position < 0 || position >= cells.size()) return rv;
        Cell cell = cells.get(position);

        rv.setInt(id("mini_root", "id"), "setBackgroundColor", cell.hlTint);   // 날짜 강조 옅은 배경(없으면 0=투명)
        boolean red = cell.dow == 0 || cell.holiday;   // 일요일/공휴일
        int numColor = cell.isToday ? 0xFFFFFFFF
            : (!cell.inMonth ? (red ? 0x59C0503F : 0x552A1C0F) : (red ? 0xFFC0503F : 0xFF2A1C0F));
        rv.setTextViewText(id("mini_day", "id"), String.valueOf(cell.day));
        rv.setTextColor(id("mini_day", "id"), numColor);
        rv.setInt(id("mini_day", "id"), "setBackgroundResource",
            cell.isToday ? id("mini_today_bg", "drawable") : 0);

        int[] dotV = { id("mini_dot1", "id"), id("mini_dot2", "id"), id("mini_dot3", "id") };
        for (int s = 0; s < 3; s++) {
            if (s < cell.dots.size()) {
                rv.setViewVisibility(dotV[s], android.view.View.VISIBLE);
                rv.setInt(dotV[s], "setColorFilter", cell.dots.get(s));
            } else {
                rv.setViewVisibility(dotV[s], android.view.View.GONE);
            }
        }

        // 셀 탭 → 그 방·그 날짜의 '달' 캘린더로 이동(://open 딥링크). 템플릿 data 가 null 이라 여기 URI 가 적용됨.
        Intent fill = new Intent();
        String u = "com.lsung.uricalendar://open?" + (roomId != null ? "room=" + roomId + "&" : "") + "date=" + cell.key;
        fill.setData(android.net.Uri.parse(u));
        fill.putExtra("widgetDate", cell.key);
        rv.setOnClickFillInIntent(id("mini_root", "id"), fill);
        return rv;
    }
}

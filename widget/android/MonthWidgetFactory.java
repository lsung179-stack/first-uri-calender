package com.lsung.uricalendar.widget;

/*
 * 월 그리드 셀 어댑터 — SharedPreferences의 widget.data를 읽어 이번 달 42(또는 35)칸을 만든다.
 * 각 칸: 날짜 숫자 + 이벤트 칩 최대 2개 + 넘침(+N). 탭하면 앱 열림(fill-in intent에 date extra).
 * v1: 일자별 칩(iOS의 gid 연속 기간 바는 다음 반복에서).
 */

import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.widget.RemoteViews;
import android.widget.RemoteViewsService;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class MonthWidgetFactory implements RemoteViewsService.RemoteViewsFactory {

    private final Context ctx;
    private final List<Cell> cells = new ArrayList<>();

    MonthWidgetFactory(Context c) { this.ctx = c; }

    static class Cell {
        int day; int dow; boolean inMonth; boolean isToday; String key;
        List<WidgetData.Event> evs = new ArrayList<>();
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
        WidgetData.Room room = data != null ? data.pickRoom() : null;

        Calendar now = Calendar.getInstance();
        int year = now.get(Calendar.YEAR);
        int month0 = now.get(Calendar.MONTH);
        String todayKey = key(year, month0, now.get(Calendar.DAY_OF_MONTH));

        Calendar first = Calendar.getInstance();
        first.set(year, month0, 1, 0, 0, 0);
        first.set(Calendar.MILLISECOND, 0);
        int offset = first.get(Calendar.DAY_OF_WEEK) - 1; // 0=일
        int dim = first.getActualMaximum(Calendar.DAY_OF_MONTH);
        int rows = (int) Math.ceil((offset + dim) / 7.0);

        // 날짜별 이벤트
        Map<String, List<WidgetData.Event>> byDate = new HashMap<>();
        if (room != null) {
            for (WidgetData.Event e : room.events) {
                List<WidgetData.Event> l = byDate.get(e.date);
                if (l == null) { l = new ArrayList<>(); byDate.put(e.date, l); }
                l.add(e);
            }
        }

        Calendar start = Calendar.getInstance();
        start.set(year, month0, 1, 0, 0, 0);
        start.set(Calendar.MILLISECOND, 0);
        start.add(Calendar.DAY_OF_MONTH, -offset);

        for (int i = 0; i < rows * 7; i++) {
            Calendar d = (Calendar) start.clone();
            d.add(Calendar.DAY_OF_MONTH, i);
            Cell cell = new Cell();
            cell.day = d.get(Calendar.DAY_OF_MONTH);
            cell.dow = d.get(Calendar.DAY_OF_WEEK) - 1;
            cell.inMonth = d.get(Calendar.MONTH) == month0;
            cell.key = key(d.get(Calendar.YEAR), d.get(Calendar.MONTH), cell.day);
            cell.isToday = cell.key.equals(todayKey);
            List<WidgetData.Event> l = byDate.get(cell.key);
            if (l != null) cell.evs = l;
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

        // 이벤트 칩 2개 슬롯
        int[] evViews = { id("cell_ev1", "id"), id("cell_ev2", "id") };
        for (int s = 0; s < 2; s++) {
            if (s < cell.evs.size()) {
                WidgetData.Event e = cell.evs.get(s);
                int bg = parseColor(e.color);
                rv.setViewVisibility(evViews[s], android.view.View.VISIBLE);
                rv.setTextViewText(evViews[s], e.title);
                rv.setInt(evViews[s], "setBackgroundColor", bg);
                rv.setTextColor(evViews[s], contrastText(bg));
            } else {
                rv.setViewVisibility(evViews[s], android.view.View.GONE);
            }
        }
        int over = cell.evs.size() - 2;
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

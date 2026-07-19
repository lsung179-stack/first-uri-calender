package com.lsung.uricalendar.widget;

/*
 * 그리드 셀 어댑터 — 월(large) + 2주(medium) 위젯 공용.
 *   kind=MONTH:  표시 달(monthOffset 반영) 5·6주 동적, 이전/다음달 회색.
 *   kind=TWOWEEK: 이번 주 시작 + weekOffset*14 부터 14칸(2주).
 * 각 셀: 날짜 숫자 + 기간 런(연속 바) 최대 2줄 + 할일 1개 + 넘침(+N).
 * 멤버 필터(WidgetCommon.filterUser) 적용. 런/레인 계산은 WidgetCommon 공용.
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

public class GridWidgetFactory implements RemoteViewsService.RemoteViewsFactory {

    static final int MONTH = 0, TWOWEEK = 1;
    private static final int MAX_LANES = 2;

    private final Context ctx;
    private final int kind;
    private final List<Cell> cells = new ArrayList<>();

    GridWidgetFactory(Context c, int kind) { this.ctx = c; this.kind = kind; }

    static class Cell {
        int day; int dow; boolean inMonth; boolean isToday; String key;
        WidgetCommon.DayBar[] bars = new WidgetCommon.DayBar[MAX_LANES];
        int overflow;
        List<WidgetData.Todo> todos = new ArrayList<>();
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
        String filter = WidgetCommon.filterUser(ctx);
        String todayKey = WidgetCommon.todayKey();

        Calendar start = Calendar.getInstance();
        start.set(Calendar.MILLISECOND, 0); start.set(Calendar.SECOND, 0); start.set(Calendar.MINUTE, 0); start.set(Calendar.HOUR_OF_DAY, 0);
        int rows, dispMonth0;
        if (kind == TWOWEEK) {
            int dow = start.get(Calendar.DAY_OF_WEEK) - 1; // 0=일
            start.add(Calendar.DAY_OF_MONTH, -dow + WidgetCommon.weekOffset(ctx) * 14);
            rows = 2;
            dispMonth0 = -1; // 2주는 달 흐림 없음
        } else {
            Calendar disp = Calendar.getInstance();
            disp.add(Calendar.MONTH, WidgetCommon.monthOffset(ctx));
            int year = disp.get(Calendar.YEAR); dispMonth0 = disp.get(Calendar.MONTH);
            Calendar first = Calendar.getInstance();
            first.clear(); first.set(year, dispMonth0, 1);
            int offset = first.get(Calendar.DAY_OF_WEEK) - 1;
            int dim = first.getActualMaximum(Calendar.DAY_OF_MONTH);
            rows = (int) Math.ceil((offset + dim) / 7.0);
            start.clear(); start.set(year, dispMonth0, 1);
            start.add(Calendar.DAY_OF_MONTH, -offset);
        }

        String startKey = WidgetCommon.fmt(start);
        Calendar endCal = (Calendar) start.clone();
        endCal.add(Calendar.DAY_OF_MONTH, rows * 7 - 1);
        String endKey = WidgetCommon.fmt(endCal);

        List<WidgetCommon.Run> runs = room != null
            ? WidgetCommon.buildRuns(room.events, filter, startKey, endKey) : new ArrayList<>();

        Map<String, List<WidgetData.Todo>> byTodo = new HashMap<>();
        if (room != null) {
            for (WidgetData.Todo t : room.todos) {
                if (!WidgetCommon.todoVisibleFor(t, filter)) continue;
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
            cell.inMonth = (dispMonth0 < 0) || d.get(Calendar.MONTH) == dispMonth0;
            cell.key = WidgetCommon.fmt(d);
            cell.isToday = cell.key.equals(todayKey);
            int[] ov = new int[]{0};
            cell.bars = WidgetCommon.cellBars(runs, cell.key, cell.dow, MAX_LANES, ov);
            cell.overflow = ov[0];
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

        int[] evViews = { id("cell_ev1", "id"), id("cell_ev2", "id") };
        for (int s = 0; s < MAX_LANES; s++) {
            WidgetCommon.DayBar b = s < cell.bars.length ? cell.bars[s] : null;
            if (b != null) {
                boolean showTitle = !b.contLeft || cell.dow == 0;
                rv.setViewVisibility(evViews[s], android.view.View.VISIBLE);
                rv.setTextViewText(evViews[s], showTitle ? (b.title == null ? "" : b.title) : " ");
                if (b.outline) {
                    rv.setInt(evViews[s], "setBackgroundColor", 0x00000000);
                    rv.setTextColor(evViews[s], b.color);
                } else {
                    rv.setInt(evViews[s], "setBackgroundColor", b.color);
                    rv.setTextColor(evViews[s], WidgetCommon.contrastText(b.color));
                }
            } else {
                rv.setViewVisibility(evViews[s], android.view.View.GONE);
            }
        }

        if (!cell.todos.isEmpty()) {
            WidgetData.Todo t = cell.todos.get(0);
            String mark = t.done ? "☑ " : "☐ ";
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

        Intent fill = new Intent();
        fill.putExtra("widgetDate", cell.key);
        rv.setOnClickFillInIntent(id("cell_root", "id"), fill);
        return rv;
    }
}

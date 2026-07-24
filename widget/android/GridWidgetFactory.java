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
    private static final int MAX_LANE_VIEWS = 5; // 레이아웃의 이벤트 줄(cell_ev1~cell_ev5) 수

    private final Context ctx;
    private final int kind;
    private final List<Cell> cells = new ArrayList<>();
    private int laneCap = 2;    // 위젯 높이에 맞춰 산정되는 셀당 표시 줄 수(여백만큼 일정 더 노출)
    private int cellMinPx = 0;  // 셀 최소 높이(px) — 0이면 미적용(기존 동작)

    GridWidgetFactory(Context c, int kind) { this.ctx = c; this.kind = kind; }

    static class Cell {
        int day; int dow; boolean inMonth; boolean isToday; String key;
        WidgetCommon.DayBar[] bars = new WidgetCommon.DayBar[0];
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

        // 위젯 실제 높이에 맞춰 셀 높이·표시 줄 수 산정 (여백만큼 일정을 더 노출)
        laneCap = 2; cellMinPx = 0;
        try {
            float density = ctx.getResources().getDisplayMetrics().density;
            int hDp = WidgetCommon.gridHeightDp(ctx, kind);
            if (hDp > 0) {
                int headerDp = 52; // 씰 헤더 + 요일 줄 + 여백 대략치
                int gridDp = hDp - headerDp;
                int cellDp = gridDp / rows;
                if (cellDp < 30) cellDp = 30;   // 최소 셀 높이(날짜+1줄) — 작은 위젯도 전체 주 표시
                cellMinPx = Math.round(cellDp * density);
                // 날짜 숫자(~18dp) 뺀 나머지를 이벤트 줄(~12dp)로 → 셀 클수록 일정 더 노출
                int lanes = (cellDp - 18) / 12;
                laneCap = Math.max(2, Math.min(MAX_LANE_VIEWS, lanes));
            }
        } catch (Throwable t) { laneCap = 2; cellMinPx = 0; }

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
            cell.bars = WidgetCommon.cellBars(runs, cell.key, cell.dow, laneCap, ov);
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

        // 위젯 높이에 맞춘 셀 최소 높이 → GridView 행이 위젯을 꽉 채움(하단 여백 제거)
        if (cellMinPx > 0) rv.setInt(id("cell_root", "id"), "setMinimumHeight", cellMinPx);

        rv.setTextViewText(id("cell_day", "id"), String.valueOf(cell.day));
        if (cell.isToday) {
            // 오늘 = 채운 원 + 흰 숫자 (iOS 파리티)
            rv.setInt(id("cell_day", "id"), "setBackgroundResource", id("cell_today_bg", "drawable"));
            rv.setTextColor(id("cell_day", "id"), 0xFFFFFFFF);
        } else {
            rv.setInt(id("cell_day", "id"), "setBackgroundColor", 0x00000000);
            int numColor;
            if (!cell.inMonth) numColor = 0x552A1C0F;
            else if (cell.dow == 0) numColor = 0xFFC0503F;
            else numColor = 0xFF2A1C0F;
            rv.setTextColor(id("cell_day", "id"), numColor);
        }

        int[] evViews = { id("cell_ev1", "id"), id("cell_ev2", "id"), id("cell_ev3", "id"), id("cell_ev4", "id"), id("cell_ev5", "id") };
        for (int s = 0; s < evViews.length; s++) {
            WidgetCommon.DayBar b = (s < laneCap && s < cell.bars.length) ? cell.bars[s] : null;
            if (b != null) {
                boolean showTitle = !b.contLeft || cell.dow == 0;
                rv.setViewVisibility(evViews[s], android.view.View.VISIBLE);
                rv.setTextViewText(evViews[s], showTitle ? (b.title == null ? "" : b.title) : " ");
                if (b.outline) {
                    rv.setInt(evViews[s], "setBackgroundColor", 0x00000000);
                    rv.setTextColor(evViews[s], b.color);
                } else {
                    // 단일 일정 = 양끝 여백 + 살짝 둥근 칩 / 기간 = 이어지는 변만 각지게(연결 유지).
                    // (rounded chip은 tint 필요 → API 31+에서만, 미만은 기존 flat 색으로 폴백)
                    boolean lR = !b.contLeft || cell.dow == 0;   // 왼쪽 끝(둥글게)
                    boolean rR = !b.contRight || cell.dow == 6;   // 오른쪽 끝(둥글게)
                    if ((lR || rR) && android.os.Build.VERSION.SDK_INT >= 31) {
                        int shape = (lR && rR) ? id("ev_chip_single", "drawable")
                                  : lR ? id("ev_chip_start", "drawable")
                                       : id("ev_chip_end", "drawable");
                        rv.setInt(evViews[s], "setBackgroundResource", shape);
                        rv.setColorStateList(evViews[s], "setBackgroundTintList",
                            android.content.res.ColorStateList.valueOf(b.color));
                    } else {
                        rv.setInt(evViews[s], "setBackgroundColor", b.color); // 기간 중간 or API<31 폴백
                    }
                    rv.setTextColor(evViews[s], WidgetCommon.contrastText(b.color));
                }
            } else {
                rv.setViewVisibility(evViews[s], android.view.View.GONE);
            }
        }

        if (!cell.todos.isEmpty()) {
            WidgetData.Todo t = cell.todos.get(0);
            boolean done = WidgetCommon.todoDone(ctx, t);
            String mark = done ? "☑ " : "☐ ";
            CharSequence label;
            if (done) {
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

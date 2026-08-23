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
    private String roomId = null; // 셀 탭 딥링크(://open?room=&date=)용
    // 공휴일·강조 라벨은 그 칸에만 있으면 그 칸의 일정 바만 한 줄 아래로 밀려서,
    // 여러 날 기간 바가 그 날만 어긋나 '끊겨' 보인다(실기기 제보 2026-08-19).
    // → 같은 주(가로 한 줄)에 하나라도 있으면 그 줄 7칸 전부에 같은 높이를 예약한다.
    //   (앱도 같은 이유로 공휴일 라벨을 고정 높이로 항상 자리 잡아둠 — CLAUDE.md 코드 38)
    private Map<String,String> holidays = new HashMap<>(); // 빨간날(공휴일) 'YYYY-MM-DD'→이름

    GridWidgetFactory(Context c, int kind) { this.ctx = c; this.kind = kind; }

    static class Cell {
        int day; int dow; boolean inMonth; boolean isToday; String key;
        WidgetCommon.DayBar[] bars = new WidgetCommon.DayBar[0];
        int overflow;
        String holiday; // 빨간날 이름(있으면 빨간 바 + 빨간 숫자)
        WidgetData.Highlight hl;      // 날짜 강조(칸당 1건)
        boolean[] hlSides;            // [top,bottom,left,right] — 테두리를 그릴 변
        boolean hlStart;              // 강조의 진짜 시작일(라벨은 여기에만)
        int reserve;                  // 이 칸이 쓰는 예약 줄 수(공휴일 바 + 강조 라벨) — 칸별로만 적용
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
        roomId = room != null ? room.id : null;
        holidays = (data != null && data.holidays != null) ? data.holidays : new HashMap<String,String>();
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

        // 위젯 실제 높이에 맞춰 셀 높이 산정 (여백만큼 일정을 더 노출).
        // 표시 줄 수(laneCap)는 공휴일/강조 라벨 예약 줄까지 알아야 정확해서 아래에서 확정한다.
        laneCap = 2; cellMinPx = 0;
        int cellDpForLanes = 0;
        int lineDp = 13;
        try {
            float density = ctx.getResources().getDisplayMetrics().density;
            int hDp = WidgetCommon.gridHeightDp(ctx, kind);
            if (hDp > 0) {
                float fontScale = ctx.getResources().getConfiguration().fontScale;
                int headerDp = WidgetCommon.headerDpFor(fontScale); // 씰 헤더 + 요일 줄 + 여백(글자 배율 반영)
                lineDp = WidgetCommon.lineDpFor(fontScale);
                int gridDp = hDp - headerDp;
                int cellDp = gridDp / rows;
                if (cellDp < 30) cellDp = 30;   // 최소 셀 높이(날짜+1줄) — 작은 위젯도 전체 주 표시
                cellMinPx = Math.round(cellDp * density);
                cellDpForLanes = cellDp;
            }
        } catch (Throwable t) { laneCap = 2; cellMinPx = 0; cellDpForLanes = 0; lineDp = 13; }

        String startKey = WidgetCommon.fmt(start);
        Calendar endCal = (Calendar) start.clone();
        endCal.add(Calendar.DAY_OF_MONTH, rows * 7 - 1);
        String endKey = WidgetCommon.fmt(endCal);

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
            cell.holiday = holidays.get(cell.key);
            cell.hl = WidgetCommon.hlFor(room, cell.key, filter, data != null ? data.myUserId : null);
            if (cell.hl != null) {
                cell.hlSides = WidgetCommon.hlSides(cell.hl, cell.key, cell.dow);
                cell.hlStart = cell.key.equals(cell.hl.start);
            }
            List<WidgetData.Todo> tl = byTodo.get(cell.key);
            if (tl != null) cell.todos = tl;
            cells.add(cell);
        }
        /* 예약 줄(공휴일 바·강조 라벨)을 레인으로 미리 점유한 뒤 런을 만든다.
           ⚠️ 이렇게 해야 기간 바가 공휴일 칸에서 한 줄 밀리지 않고 일자로 이어지고,
              공휴일이 없는 칸의 빈 첫 줄은 하루짜리 일정이 채운다. (사용자 요청 2026-08-23) */
        // ⚠️ 줄을 먹는 건 공휴일 바뿐이다 — 강조 라벨은 앱처럼 칸 좌상단에 얹히는 '탭'이라
        //    레이아웃상 자리를 차지하지 않는다(사용자 지적 2026-08-23).
        Map<String, Integer> reserve = new HashMap<>();
        for (Cell c : cells) {
            int r = (c.holiday != null ? 1 : 0);
            c.reserve = r;
            if (r > 0) reserve.put(c.key, r);
        }
        List<WidgetCommon.Run> runs = room != null
            ? WidgetCommon.buildRuns(room.events, filter, startKey, endKey, reserve) : new ArrayList<>();

        /* 표시 줄 수 확정.
           ⚠️ 예전엔 '공휴일/강조 라벨이 있는 주는 그 줄 7칸 전부가 한 줄을 비워두는' 방식이라,
              한 달에 공휴일이 하나만 있어도 위젯 전체 일정 줄이 하나씩 깎여 일정이 한 개밖에
              안 보였다(실기기 제보 2026-08-22). 이제 예약은 '그 칸'에만 적용한다 —
              칸 전체 높이는 (예약 줄 + 일정 줄)이 항상 같다 — 예약 줄이 레인을 차지하기 때문. */
        if (cellDpForLanes > 0) {
            int lines = WidgetCommon.laneBudget(cellDpForLanes, 0, !byTodo.isEmpty(), lineDp);
            laneCap = Math.max(1, Math.min(MAX_LANE_VIEWS, lines));
        }
        for (Cell c : cells) {
            int[] ov = new int[]{0};
            // 예약 줄이 레인 0..reserve-1 을 이미 점유하므로 cap 은 '칸 전체 줄 수'를 그대로 쓴다.
            c.bars = WidgetCommon.cellBars(runs, c.key, c.dow, laneCap, ov);
            c.overflow = ov[0];
        }
    }

    @Override
    public RemoteViews getViewAt(int position) {
        RemoteViews rv = new RemoteViews(ctx.getPackageName(), id("widget_cell", "layout"));
        if (position < 0 || position >= cells.size()) return rv;
        Cell cell = cells.get(position);

        // 위젯 높이에 맞춘 셀 최소 높이 → GridView 행이 위젯을 꽉 채움(하단 여백 제거)
        if (cellMinPx > 0) rv.setInt(id("cell_root", "id"), "setMinimumHeight", cellMinPx);

        // ⚠️ 시스템 글자 크기 배율이 크면 sp 글자가 17dp 박스를 넘쳐 두 자리 날짜가 "1"만 보이던 문제
        //    (실기기 제보 2026-08-19) → 날짜만 dp로 고정해 배율과 무관하게 항상 들어가게 한다.
        rv.setTextViewTextSize(id("cell_day", "id"), android.util.TypedValue.COMPLEX_UNIT_DIP, 11f);
        rv.setTextViewText(id("cell_day", "id"), String.valueOf(cell.day));
        if (cell.isToday) {
            // 오늘 = 채운 원 + 흰 숫자 (iOS 파리티)
            rv.setInt(id("cell_day", "id"), "setBackgroundResource", id("cell_today_bg", "drawable"));
            rv.setTextColor(id("cell_day", "id"), 0xFFFFFFFF);
        } else {
            rv.setInt(id("cell_day", "id"), "setBackgroundColor", 0x00000000);
            int numColor;
            boolean onDark = (cell.hl != null && cell.hl.darkFill);   // 진한 채움 위 = 흰 숫자
            if (!cell.inMonth) numColor = onDark ? 0x88FFFFFF : ((cell.dow == 0 || cell.holiday != null) ? 0x59C0503F : 0x552A1C0F);
            else if (onDark) numColor = 0xFFFFFFFF;
            else if (cell.dow == 0 || cell.holiday != null) numColor = 0xFFC0503F;   // 일요일/공휴일 빨강
            else numColor = 0xFF2A1C0F;
            rv.setTextColor(id("cell_day", "id"), numColor);
        }

        /* 날짜 강조 — 채움형은 칸 전체 배경, 그 외는 바깥 경계 변에만 띠.
           ⚠️ RemoteViews로는 점선/꽃 패턴을 그릴 수 없어(임의 드로어블 전달 불가) iOS의 대시·도트는
              같은 색 실선으로 근사한다. 대신 두께로 구분(폴더=3dp, 기본/꽃=2dp, 점선=1.2dp) —
              두께 변경은 API 31+ 전용 API라 그 미만에서는 레이아웃 기본값 2dp 그대로. */
        int[] sideIds = { id("cell_hl_t", "id"), id("cell_hl_b", "id"), id("cell_hl_l", "id"), id("cell_hl_r", "id") };
        if (cell.hl != null && cell.hl.isFill()) {
            rv.setViewVisibility(id("cell_hl_fill", "id"), android.view.View.VISIBLE);
            rv.setInt(id("cell_hl_fill", "id"), "setBackgroundColor",
                cell.inMonth ? cell.hl.color : ((cell.hl.color & 0x00FFFFFF) | 0x66000000));
            for (int sd : sideIds) rv.setViewVisibility(sd, android.view.View.GONE);
        } else if (cell.hl != null) {
            rv.setViewVisibility(id("cell_hl_fill", "id"), android.view.View.GONE);
            int lineColor = cell.inMonth ? cell.hl.color : ((cell.hl.color & 0x00FFFFFF) | 0x66000000);
            float thick = "folder".equals(cell.hl.render) ? 3f : ("dashed".equals(cell.hl.render) ? 1.2f : 2f);
            for (int k = 0; k < 4; k++) {
                boolean on = cell.hlSides != null && cell.hlSides[k];
                rv.setViewVisibility(sideIds[k], on ? android.view.View.VISIBLE : android.view.View.GONE);
                if (!on) continue;
                rv.setInt(sideIds[k], "setBackgroundColor", lineColor);
                if (android.os.Build.VERSION.SDK_INT >= 31) {
                    // 위/아래는 높이, 좌/우는 너비가 두께
                    if (k < 2) rv.setViewLayoutHeight(sideIds[k], thick, android.util.TypedValue.COMPLEX_UNIT_DIP);
                    else       rv.setViewLayoutWidth(sideIds[k], thick, android.util.TypedValue.COMPLEX_UNIT_DIP);
                }
            }
        } else {
            rv.setViewVisibility(id("cell_hl_fill", "id"), android.view.View.GONE);
            for (int sd : sideIds) rv.setViewVisibility(sd, android.view.View.GONE);
        }
        // 강조 라벨 — 앱은 칸 위로 솟은 탭이지만 위젯 칸엔 여백이 없어 공휴일과 같은 '색 바'로 표시
        if (cell.hlStart && cell.hl != null && cell.hl.label && cell.hl.title != null && !cell.hl.title.isEmpty()) {
            rv.setViewVisibility(id("cell_hl_label", "id"), android.view.View.VISIBLE);
            rv.setTextViewText(id("cell_hl_label", "id"), cell.hl.title);
            if (android.os.Build.VERSION.SDK_INT >= 31) {
                rv.setInt(id("cell_hl_label", "id"), "setBackgroundResource", id("ev_chip_single", "drawable"));
                rv.setColorStateList(id("cell_hl_label", "id"), "setBackgroundTintList",
                    android.content.res.ColorStateList.valueOf(cell.hl.color));
            } else {
                rv.setInt(id("cell_hl_label", "id"), "setBackgroundColor", cell.hl.color);
            }
            rv.setTextColor(id("cell_hl_label", "id"), cell.hl.ink);
        } else {
            // 라벨이 없는 칸은 자리를 안 비워둔다 → 그 칸은 일정으로 꽉 채워짐
            rv.setTextViewText(id("cell_hl_label", "id"), "");
            rv.setViewVisibility(id("cell_hl_label", "id"), android.view.View.GONE);
        }

        // 빨간날(공휴일) 바 — 앱은 텍스트 라벨만, 위젯은 빨간 바로 자동 표시
        if (cell.holiday != null) {
            rv.setViewVisibility(id("cell_holiday", "id"), android.view.View.VISIBLE);
            rv.setTextViewText(id("cell_holiday", "id"), cell.holiday);
            // 공휴일 바만 각진 직사각형이라 다른 일정 바와 이질감이 있었음 → 같은 둥근 칩으로 통일
            // (칩 드로어블은 틴트가 필요해 API 31+ 전용, 그 미만은 레이아웃의 평면 색 폴백)
            if (android.os.Build.VERSION.SDK_INT >= 31) {
                rv.setInt(id("cell_holiday", "id"), "setBackgroundResource", id("ev_chip_single", "drawable"));
                rv.setColorStateList(id("cell_holiday", "id"), "setBackgroundTintList",
                    android.content.res.ColorStateList.valueOf(0xFFC0503F));
            }
        } else {
            // 공휴일이 없는 칸은 자리를 안 비워둔다(사용자 요청 2026-08-22) → 일정이 한 줄 더 보임
            rv.setTextViewText(id("cell_holiday", "id"), "");
            rv.setViewVisibility(id("cell_holiday", "id"), android.view.View.GONE);
        }

        /* 이벤트 줄 배치 — 예약 줄(공휴일 바·강조 라벨)이 이미 레인 0..reserve-1 을 차지하므로,
           evViews[k] 는 '레인 reserve+k' 를 담당한다. 중간에 빈 레인이 있으면 빈 줄(스페이서)을 넣어
           같은 기간 바가 날마다 같은 높이에 오게 한다 — 그 빈 줄은 하루짜리 일정이 있으면 그 일정이 채운다.
           ⚠️ 마지막 바 뒤의 빈 레인까지 채우면 칸만 길어지므로 '마지막 바 앞'까지만 스페이서를 둔다. */
        int[] evViews = { id("cell_ev1", "id"), id("cell_ev2", "id"), id("cell_ev3", "id"), id("cell_ev4", "id"), id("cell_ev5", "id") };
        int lastBar = -1;
        for (int L = cell.reserve; L < cell.bars.length; L++) if (cell.bars[L] != null) lastBar = L;
        for (int s = 0; s < evViews.length; s++) {
            int lane = cell.reserve + s;
            WidgetCommon.DayBar b = (lane < cell.bars.length) ? cell.bars[lane] : null;
            if (b == null && lane < lastBar) {
                // 빈 레인 자리 지킴 — 투명 배경 + 빈 글자
                rv.setViewVisibility(evViews[s], android.view.View.VISIBLE);
                rv.setTextViewText(evViews[s], "");
                rv.setInt(evViews[s], "setBackgroundColor", 0x00000000);
                continue;
            }
            if (b != null) {
                boolean showTitle = !b.contLeft || cell.dow == 0;
                boolean showMark = b.shared && !b.contLeft;   // 함께 일정: 시작 칸 왼쪽에 얇은 띠(▎, 텍스트색=대비색)
                rv.setViewVisibility(evViews[s], android.view.View.VISIBLE);
                String _bt = showTitle ? (b.title == null ? "" : b.title) : " ";
                /* 함께 일정 띠. ⚠️ '▎'(U+258E)는 글자 폭의 1/4만 잉크이고 나머지는 글리프 자체의
                   빈 공간이라, 앞뒤로 스페이스를 안 넣어도 제목과 크게 벌어진다(실기기 제보 2026-08-22).
                   → ScaleXSpan 으로 그 글자만 가로 50%로 눌러 간격을 절반으로 줄인다.
                   (RemoteViews 는 SCALE_X_SPAN 을 그대로 전달한다. 혹시 적용이 안 돼도
                    그냥 원래 폭의 띠가 되므로 안전하다.) 왼쪽 여백은 레이아웃 paddingLeft 가 담당. */
                if (showMark) {
                    android.text.SpannableStringBuilder _sb = new android.text.SpannableStringBuilder("▎");
                    _sb.setSpan(new android.text.style.ScaleXSpan(0.5f), 0, 1,
                                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    _sb.append(_bt);
                    rv.setTextViewText(evViews[s], _sb);
                } else {
                    rv.setTextViewText(evViews[s], _bt);
                }
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

        // 셀 탭 → 그 방·그 날짜의 '달' 캘린더로 이동(://open 딥링크). 템플릿 data 가 null 이라 여기 URI 가 적용됨.
        Intent fill = new Intent();
        String u = "com.lsung.uricalendar://open?" + (roomId != null ? "room=" + roomId + "&" : "") + "date=" + cell.key;
        fill.setData(android.net.Uri.parse(u));
        fill.putExtra("widgetDate", cell.key);
        rv.setOnClickFillInIntent(id("cell_root", "id"), fill);
        return rv;
    }
}

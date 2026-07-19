package com.lsung.uricalendar.widget;

/*
 * 콤보 위젯(large). 좌: 다가오는 일정(기간 묶음) + 오늘 할일 1개, 우: 미니 월(달 이동).
 */

import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;
import android.content.Intent;
import android.widget.RemoteViews;

import java.util.List;

public class ComboWidgetProvider extends AppWidgetProvider {

    @Override
    public void onUpdate(Context context, AppWidgetManager mgr, int[] ids) {
        updateAll(context, mgr, ids);
    }

    static void updateAll(Context context, AppWidgetManager mgr, int[] ids) {
        for (int id : ids) {
            RemoteViews rv = build(context, id);
            mgr.updateAppWidget(id, rv);
            mgr.notifyAppWidgetViewDataChanged(id, WidgetCommon.resId(context, "cb_grid", "id"));
        }
    }

    private static RemoteViews build(Context context, int id) {
        RemoteViews rv = new RemoteViews(context.getPackageName(), WidgetCommon.resId(context, "widget_combo", "layout"));
        WidgetData.WData data = WidgetData.load(context);
        WidgetData.Room room = data != null ? data.pickRoom(WidgetCommon.selectedRoomId(context)) : null;
        String filter = WidgetCommon.filterUser(context);
        String today = WidgetCommon.todayKey();

        WidgetCommon.wireGridHeader(context, rv, data, room);

        // 다가오는 일정 (기간 묶음)
        List<WidgetCommon.UpRun> up = room != null ? WidgetCommon.upcoming(room.events, filter) : new java.util.ArrayList<>();
        rv.setViewVisibility(WidgetCommon.resId(context, "cb_upempty", "id"),
            up.isEmpty() ? android.view.View.VISIBLE : android.view.View.GONE);
        for (int i = 0; i < 6; i++) {
            int rowId = WidgetCommon.resId(context, "cb_up" + i, "id");
            if (i < up.size()) {
                WidgetCommon.UpRun u = up.get(i);
                boolean isNow = u.start.compareTo(today) <= 0 && today.compareTo(u.end) <= 0;
                rv.setViewVisibility(rowId, android.view.View.VISIBLE);
                rv.setInt(WidgetCommon.resId(context, "cb_upbar" + i, "id"), "setBackgroundColor", u.color);
                int rgId = WidgetCommon.resId(context, "cb_uprange" + i, "id");
                rv.setTextViewText(rgId, WidgetCommon.rangeLabel(u.start, u.end));
                rv.setTextColor(rgId, isNow ? 0xFFF6EFE0 : 0xFF8A6C52);
                rv.setInt(rgId, "setBackgroundResource", WidgetCommon.resId(context, isNow ? "chip_on" : "chip_off", "drawable"));
                int ttId = WidgetCommon.resId(context, "cb_uptitle" + i, "id");
                String title = u.title;
                if (u.time != null && !u.time.isEmpty()) title = title + "  " + u.time;
                rv.setTextViewText(ttId, title);
            } else {
                rv.setViewVisibility(rowId, android.view.View.GONE);
            }
        }

        // 오늘 할일 1개
        int tdId = WidgetCommon.resId(context, "cb_td", "id");
        WidgetData.Todo firstTd = null;
        if (room != null) for (WidgetData.Todo t : room.todos) {
            if (today.equals(t.date) && WidgetCommon.todoVisibleFor(t, filter)) { firstTd = t; break; }
        }
        if (firstTd != null) {
            boolean done = WidgetCommon.todoDone(context, firstTd);
            rv.setViewVisibility(tdId, android.view.View.VISIBLE);
            String mark = done ? "☑ " : "☐ ";
            if (done) {
                android.text.SpannableString sp = new android.text.SpannableString(mark + firstTd.title);
                sp.setSpan(new android.text.style.StrikethroughSpan(), mark.length(), sp.length(), 0);
                rv.setTextViewText(tdId, sp);
                rv.setTextColor(tdId, 0xFF8A6C52);
            } else {
                rv.setTextViewText(tdId, mark + firstTd.title);
                rv.setTextColor(tdId, 0xFF2A1C0F);
            }
            rv.setOnClickPendingIntent(tdId, WidgetCommon.toggleTodo(context, firstTd.id));
        } else {
            rv.setViewVisibility(tdId, android.view.View.GONE);
        }

        // 미니 월 헤더(달 이동)
        int off = WidgetCommon.comboOffset(context);
        java.util.Calendar base = java.util.Calendar.getInstance();
        base.add(java.util.Calendar.MONTH, off);
        int mLabelId = WidgetCommon.resId(context, "cb_mlabel", "id");
        rv.setTextViewText(mLabelId, (base.get(java.util.Calendar.MONTH) + 1) + "월");
        rv.setTextColor(mLabelId, off != 0 ? 0xFFC0503F : 0xFF2A1C0F);
        rv.setOnClickPendingIntent(WidgetCommon.resId(context, "cb_mprev", "id"),
            WidgetCommon.bcast(context, WidgetCommon.RC_COMBO_PREV, WidgetCommon.ACTION_SHIFT_COMBO, -1, null));
        rv.setOnClickPendingIntent(WidgetCommon.resId(context, "cb_mnext", "id"),
            WidgetCommon.bcast(context, WidgetCommon.RC_COMBO_NEXT, WidgetCommon.ACTION_SHIFT_COMBO, 1, null));
        rv.setOnClickPendingIntent(mLabelId,
            WidgetCommon.bcast(context, WidgetCommon.RC_COMBO_RESET, WidgetCommon.ACTION_SHIFT_COMBO, 0, null));

        // 미니 월 GridView
        Intent svc = new Intent(context, ComboMiniService.class);
        svc.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id);
        svc.setData(android.net.Uri.parse(svc.toUri(Intent.URI_INTENT_SCHEME)));
        rv.setRemoteAdapter(WidgetCommon.resId(context, "cb_grid", "id"), svc);
        rv.setPendingIntentTemplate(WidgetCommon.resId(context, "cb_grid", "id"),
            WidgetCommon.openAppTemplate(context, WidgetCommon.RC_OPEN, room != null ? room.id : null));

        // 좌측 '다가오는 일정' 타이틀 탭 = 앱 열기
        rv.setOnClickPendingIntent(WidgetCommon.resId(context, "cb_uptitlehead", "id"),
            WidgetCommon.openApp(context, WidgetCommon.RC_OPEN, room != null ? room.id : null, null));
        return rv;
    }
}

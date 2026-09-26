package com.lsung.uricalendar.widget;

/*
 * 2주 위젯(medium). 이번 주 시작 + weekOffset*14 부터 14칸. 헤더 주 이동(‹ 라벨 ›).
 * 주 시작 요일 = payload.weekStart(0=일 1=월).
 */

import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;
import android.content.Intent;
import android.widget.RemoteViews;

public class TwoWeekWidgetProvider extends AppWidgetProvider {

    @Override
    public void onUpdate(Context context, AppWidgetManager mgr, int[] ids) {
        updateAll(context, mgr, ids);
    }

    @Override
    public void onAppWidgetOptionsChanged(Context context, AppWidgetManager mgr, int appWidgetId, android.os.Bundle newOptions) {
        try {
            int h = newOptions.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 0);
            if (h > 0) WidgetCommon.setGridHeightDp(context, GridWidgetFactory.TWOWEEK, h);
        } catch (Throwable t) { /* 무시 */ }
        updateAll(context, mgr, new int[]{ appWidgetId });
    }

    static void updateAll(Context context, AppWidgetManager mgr, int[] ids) {
        for (int id : ids) {
            try {
                RemoteViews rv = build(context, id);
                mgr.updateAppWidget(id, rv);
                mgr.notifyAppWidgetViewDataChanged(id, WidgetCommon.resId(context, "grid", "id"));
            } catch (Throwable t) {
                try { mgr.updateAppWidget(id, WidgetCommon.fallbackRV(context)); } catch (Throwable t2) {}
            }
        }
    }

    private static RemoteViews build(Context context, int id) {
        try {
            android.os.Bundle o = AppWidgetManager.getInstance(context).getAppWidgetOptions(id);
            int h = o != null ? o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 0) : 0;
            if (h > 0) WidgetCommon.setGridHeightDp(context, GridWidgetFactory.TWOWEEK, h);
        } catch (Throwable t) { /* 무시 */ }
        RemoteViews rv = new RemoteViews(context.getPackageName(), WidgetCommon.resId(context, "widget_twoweek", "layout"));
        WidgetData.WData data = WidgetData.load(context);
        WidgetData.Room room = data != null ? data.pickRoom(WidgetCommon.selectedRoomId(context)) : null;

        WidgetCommon.wireGridHeader(context, rv, data, room);

        // 요일 머리줄 — 앱 '주 시작 요일'(weekStart) 순서 [2026-09-26]
        int ws = WidgetCommon.weekStart(data);
        WidgetCommon.wireDowHeader(context, rv, "wg_dow", ws);

        // 주 이동 라벨: 표시 2주의 시작~끝(M.d~M.d) — 시작 = 오늘이 든 주의 첫날(weekStart 기준)
        int off = WidgetCommon.weekOffset(context);
        java.util.Calendar s = WidgetCommon.weekStartOf(java.util.Calendar.getInstance(), ws);
        s.add(java.util.Calendar.DAY_OF_MONTH, off * 14);
        java.util.Calendar e = (java.util.Calendar) s.clone();
        e.add(java.util.Calendar.DAY_OF_MONTH, 13);
        String label = (s.get(java.util.Calendar.MONTH) + 1) + "." + s.get(java.util.Calendar.DAY_OF_MONTH)
            + "~" + (e.get(java.util.Calendar.MONTH) + 1) + "." + e.get(java.util.Calendar.DAY_OF_MONTH);
        int wId = WidgetCommon.resId(context, "wg_wlabel", "id");
        rv.setTextViewText(wId, label);
        rv.setTextColor(wId, off != 0 ? 0xFFC0503F : 0xFF2A1C0F);
        rv.setOnClickPendingIntent(WidgetCommon.resId(context, "wg_wprev", "id"),
            WidgetCommon.bcast(context, WidgetCommon.RC_WEEK_PREV, WidgetCommon.ACTION_SHIFT_WEEK, -1, null));
        rv.setOnClickPendingIntent(WidgetCommon.resId(context, "wg_wnext", "id"),
            WidgetCommon.bcast(context, WidgetCommon.RC_WEEK_NEXT, WidgetCommon.ACTION_SHIFT_WEEK, 1, null));
        rv.setOnClickPendingIntent(wId,
            WidgetCommon.bcast(context, WidgetCommon.RC_WEEK_RESET, WidgetCommon.ACTION_SHIFT_WEEK, 0, null));

        Intent svc = new Intent(context, TwoWeekWidgetService.class);
        svc.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id);
        svc.setData(android.net.Uri.parse(svc.toUri(Intent.URI_INTENT_SCHEME)));
        rv.setRemoteAdapter(WidgetCommon.resId(context, "grid", "id"), svc);
        rv.setEmptyView(WidgetCommon.resId(context, "grid", "id"), WidgetCommon.resId(context, "wg_empty", "id"));

        rv.setPendingIntentTemplate(WidgetCommon.resId(context, "grid", "id"),
            WidgetCommon.openAppTemplate(context, WidgetCommon.RC_OPEN, room != null ? room.id : null));
        return rv;
    }
}

package com.lsung.uricalendar.widget;

/*
 * 우리 캘린더 — Android 월 위젯(large). RemoteViews + GridView 컬렉션.
 * 모든 상태 브로드캐스트(방순환·필터·달이동·새로고침)의 중앙 수신자 →
 * WidgetCommon.applyAction 후 4종 위젯 모두 refreshAll.
 */

import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;
import android.content.Intent;
import android.widget.RemoteViews;

public class UriCalendarWidgetProvider extends AppWidgetProvider {

    @Override
    public void onUpdate(Context context, AppWidgetManager mgr, int[] ids) {
        updateAll(context, mgr, ids);
    }

    // 위젯 크기 변경(리사이즈) 시 실제 높이를 저장 → 셀 높이·표시 줄 수 자동 재산정
    @Override
    public void onAppWidgetOptionsChanged(Context context, AppWidgetManager mgr, int appWidgetId, android.os.Bundle newOptions) {
        try {
            int h = newOptions.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 0);
            if (h > 0) WidgetCommon.setGridHeightDp(context, GridWidgetFactory.MONTH, h);
        } catch (Throwable t) { /* 무시 */ }
        updateAll(context, mgr, new int[]{ appWidgetId });
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        super.onReceive(context, intent);
        if (WidgetCommon.applyAction(context, intent)) {
            if (WidgetCommon.ACTION_REFRESH.equals(intent.getAction())) {
                // 새로고침 깜빡임: 아이콘 강조로 즉시 렌더 → 300ms 후 원복.
                // (실제 데이터 새로고침은 refreshAll의 notifyAppWidgetViewDataChanged로 이미 수행됨)
                WidgetCommon.setFlash(context, true);
                WidgetCommon.refreshAll(context);
                final Context ctx = context.getApplicationContext();
                final PendingResult pr = goAsync();
                new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(new Runnable() {
                    @Override public void run() {
                        try { WidgetCommon.setFlash(ctx, false); WidgetCommon.refreshAll(ctx); }
                        catch (Throwable t) { /* 무시 */ }
                        finally { try { pr.finish(); } catch (Throwable t) {} }
                    }
                }, 300);
            } else {
                WidgetCommon.refreshAll(context);
            }
        }
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
        // 초기 배치 시에도 위젯 높이 확보(리사이즈 이벤트 전) → 셀 높이 자동 산정
        try {
            android.os.Bundle o = AppWidgetManager.getInstance(context).getAppWidgetOptions(id);
            int h = o != null ? o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 0) : 0;
            if (h > 0) WidgetCommon.setGridHeightDp(context, GridWidgetFactory.MONTH, h);
        } catch (Throwable t) { /* 무시 */ }
        RemoteViews rv = new RemoteViews(context.getPackageName(), WidgetCommon.resId(context, "widget_month", "layout"));
        WidgetData.WData data = WidgetData.load(context);
        WidgetData.Room room = data != null ? data.pickRoom(WidgetCommon.selectedRoomId(context)) : null;

        WidgetCommon.wireGridHeader(context, rv, data, room);

        // 달 이동
        int off = WidgetCommon.monthOffset(context);
        java.util.Calendar cal = java.util.Calendar.getInstance();
        cal.add(java.util.Calendar.MONTH, off);
        int month = cal.get(java.util.Calendar.MONTH) + 1;
        int mId = WidgetCommon.resId(context, "wg_month", "id");
        rv.setTextViewText(mId, month + "월");
        rv.setTextColor(mId, off != 0 ? 0xFFC0503F : 0xFF2A1C0F);
        rv.setOnClickPendingIntent(WidgetCommon.resId(context, "wg_prev", "id"),
            WidgetCommon.bcast(context, WidgetCommon.RC_MONTH_PREV, WidgetCommon.ACTION_SHIFT_MONTH, -1, null));
        rv.setOnClickPendingIntent(WidgetCommon.resId(context, "wg_next", "id"),
            WidgetCommon.bcast(context, WidgetCommon.RC_MONTH_NEXT, WidgetCommon.ACTION_SHIFT_MONTH, 1, null));
        rv.setOnClickPendingIntent(mId,
            WidgetCommon.bcast(context, WidgetCommon.RC_MONTH_RESET, WidgetCommon.ACTION_SHIFT_MONTH, 0, null));

        // GridView 어댑터
        Intent svc = new Intent(context, MonthWidgetService.class);
        svc.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id);
        svc.setData(android.net.Uri.parse(svc.toUri(Intent.URI_INTENT_SCHEME)));
        rv.setRemoteAdapter(WidgetCommon.resId(context, "grid", "id"), svc);
        rv.setEmptyView(WidgetCommon.resId(context, "grid", "id"), WidgetCommon.resId(context, "wg_empty", "id"));

        // 셀 탭 → 앱(날짜 fill-in)
        rv.setPendingIntentTemplate(WidgetCommon.resId(context, "grid", "id"),
            WidgetCommon.openAppTemplate(context, WidgetCommon.RC_OPEN, room != null ? room.id : null));
        return rv;
    }
}

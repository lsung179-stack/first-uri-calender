package com.lsung.uricalendar.widget;

/*
 * 우리 캘린더 — Android 월 위젯 (RemoteViews + GridView 컬렉션)
 * iOS(WidgetKit)와 동일한 데이터 계약: 앱이 SharedPreferences "CapacitorStorage"의
 * "widget.data" 키에 JSON을 기록(@capacitor/preferences) → 위젯 팩토리가 읽어 렌더.
 *
 * Capacitor 안드로이드가 Java 전용이라 Glance(Kotlin+Compose) 대신 RemoteViews를 씀
 * (추가 gradle 의존성 0 → 매 빌드 재생성 프로젝트에서도 안정적).
 *
 * ⚠️ Codemagic이 매 빌드마다 android를 새로 생성하므로 이 파일/리소스는
 *    widget/android/inject_android_widget.sh 가 주입한다.
 */

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.widget.RemoteViews;

public class UriCalendarWidgetProvider extends AppWidgetProvider {

    public static final String ACTION_REFRESH = "com.lsung.uricalendar.widget.REFRESH";

    @Override
    public void onUpdate(Context context, AppWidgetManager mgr, int[] ids) {
        for (int id : ids) updateWidget(context, mgr, id);
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        super.onReceive(context, intent);
        if (ACTION_REFRESH.equals(intent.getAction())) {
            AppWidgetManager mgr = AppWidgetManager.getInstance(context);
            int[] ids = mgr.getAppWidgetIds(new ComponentName(context, UriCalendarWidgetProvider.class));
            mgr.notifyAppWidgetViewDataChanged(ids, resId(context, "grid", "id"));
            for (int id : ids) updateWidget(context, mgr, id);
        }
    }

    static int resId(Context c, String name, String type) {
        return c.getResources().getIdentifier(name, type, c.getPackageName());
    }

    private void updateWidget(Context context, AppWidgetManager mgr, int id) {
        String pkg = context.getPackageName();
        RemoteViews rv = new RemoteViews(pkg, resId(context, "widget_month", "layout"));

        // 헤더: 방 이름 + 이번 달
        WidgetData.WData data = WidgetData.load(context);
        WidgetData.Room room = data != null ? data.pickRoom() : null;
        java.util.Calendar cal = java.util.Calendar.getInstance();
        int month = cal.get(java.util.Calendar.MONTH) + 1;
        rv.setTextViewText(resId(context, "wg_room", "id"), room != null ? room.name : "우리 캘린더");
        rv.setTextViewText(resId(context, "wg_month", "id"), month + "월");

        // GridView 어댑터 → 팩토리 서비스
        Intent svc = new Intent(context, MonthWidgetService.class);
        svc.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id);
        svc.setData(android.net.Uri.parse(svc.toUri(Intent.URI_INTENT_SCHEME)));
        rv.setRemoteAdapter(resId(context, "grid", "id"), svc);
        rv.setEmptyView(resId(context, "grid", "id"), resId(context, "wg_empty", "id"));

        // 셀 탭 → 앱 열기 (날짜는 fill-in intent의 extra로 전달, 앱이 라우팅)
        Intent open = new Intent();
        open.setClassName(context, "com.lsung.uricalendar.MainActivity");
        open.setAction(Intent.ACTION_MAIN);
        open.addCategory(Intent.CATEGORY_LAUNCHER);
        PendingIntent tmpl = PendingIntent.getActivity(
            context, 0, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
        rv.setPendingIntentTemplate(resId(context, "grid", "id"), tmpl);

        // 헤더(방 이름) 탭 = 앱 열기
        rv.setOnClickPendingIntent(resId(context, "wg_header", "id"),
            PendingIntent.getActivity(context, 1, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));

        // 새로고침 버튼
        Intent refresh = new Intent(context, UriCalendarWidgetProvider.class);
        refresh.setAction(ACTION_REFRESH);
        rv.setOnClickPendingIntent(resId(context, "wg_refresh", "id"),
            PendingIntent.getBroadcast(context, 2, refresh, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));

        mgr.updateAppWidget(id, rv);
        mgr.notifyAppWidgetViewDataChanged(id, resId(context, "grid", "id"));
    }
}

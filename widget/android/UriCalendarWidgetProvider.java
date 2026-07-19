package com.lsung.uricalendar.widget;

/*
 * 우리 캘린더 — Android 월 위젯 (RemoteViews + GridView 컬렉션)
 * iOS(WidgetKit)와 동일한 데이터 계약: 앱이 SharedPreferences "CapacitorStorage"의
 * "widget.data" 키에 JSON을 기록(@capacitor/preferences) → 위젯 팩토리가 읽어 렌더.
 *
 * 위젯-로컬 UI 상태(사용자가 위젯에서 고른 방/달 이동)는 별도 prefs("uri_widget_state")에 저장.
 *  - roomId: 헤더 방 이름 탭으로 순환 선택한 방 id(iOS CycleRoomIntent 대응)
 *  - monthOffset: ‹ › 로 이동한 달 오프셋(iOS ShiftMonthIntent 대응). 새로고침/방순환 시 0으로.
 *
 * Capacitor 안드로이드가 Java 전용이라 Glance(Kotlin+Compose) 대신 RemoteViews를 씀.
 * ⚠️ Codemagic이 매 빌드마다 android를 새로 생성 → widget/android/inject_android_widget.sh 가 주입.
 */

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.widget.RemoteViews;

public class UriCalendarWidgetProvider extends AppWidgetProvider {

    public static final String ACTION_REFRESH = "com.lsung.uricalendar.widget.REFRESH";
    public static final String ACTION_CYCLE_ROOM = "com.lsung.uricalendar.widget.CYCLE_ROOM";
    public static final String ACTION_SHIFT_MONTH = "com.lsung.uricalendar.widget.SHIFT_MONTH";
    public static final String EXTRA_DELTA = "delta";  // -1/+1 이동, 0=오늘로 리셋

    static final String STATE_PREFS = "uri_widget_state";

    // ── 위젯-로컬 상태 ──
    static String selectedRoomId(Context c) {
        return c.getSharedPreferences(STATE_PREFS, Context.MODE_PRIVATE).getString("roomId", null);
    }
    static void setSelectedRoomId(Context c, String id) {
        SharedPreferences.Editor e = c.getSharedPreferences(STATE_PREFS, Context.MODE_PRIVATE).edit();
        if (id == null) e.remove("roomId"); else e.putString("roomId", id);
        e.apply();
    }
    static int monthOffset(Context c) {
        return c.getSharedPreferences(STATE_PREFS, Context.MODE_PRIVATE).getInt("monthOffset", 0);
    }
    static void setMonthOffset(Context c, int n) {
        c.getSharedPreferences(STATE_PREFS, Context.MODE_PRIVATE).edit().putInt("monthOffset", n).apply();
    }

    @Override
    public void onUpdate(Context context, AppWidgetManager mgr, int[] ids) {
        for (int id : ids) updateWidget(context, mgr, id);
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        super.onReceive(context, intent);
        String action = intent.getAction();
        if (ACTION_REFRESH.equals(action)) {
            setMonthOffset(context, 0);              // 새로고침 = 오늘 달로 복귀
            refreshAll(context);
        } else if (ACTION_CYCLE_ROOM.equals(action)) {
            cycleRoom(context);
            setMonthOffset(context, 0);              // 방 바꾸면 이번 달로
            refreshAll(context);
        } else if (ACTION_SHIFT_MONTH.equals(action)) {
            int delta = intent.getIntExtra(EXTRA_DELTA, 0);
            setMonthOffset(context, delta == 0 ? 0 : monthOffset(context) + delta);
            refreshAll(context);
        }
    }

    // 데이터 다시 그리고 그리드 컬렉션도 갱신
    private void refreshAll(Context context) {
        AppWidgetManager mgr = AppWidgetManager.getInstance(context);
        int[] ids = mgr.getAppWidgetIds(new ComponentName(context, UriCalendarWidgetProvider.class));
        mgr.notifyAppWidgetViewDataChanged(ids, resId(context, "grid", "id"));
        for (int id : ids) updateWidget(context, mgr, id);
    }

    // 방 목록에서 현재 선택 방의 다음 방으로 순환
    private void cycleRoom(Context context) {
        WidgetData.WData data = WidgetData.load(context);
        if (data == null || data.rooms.size() < 2) return;
        WidgetData.Room cur = data.pickRoom(selectedRoomId(context));
        int idx = 0;
        for (int i = 0; i < data.rooms.size(); i++) {
            if (cur != null && cur.id != null && cur.id.equals(data.rooms.get(i).id)) { idx = i; break; }
        }
        WidgetData.Room next = data.rooms.get((idx + 1) % data.rooms.size());
        setSelectedRoomId(context, next.id);
    }

    static int resId(Context c, String name, String type) {
        return c.getResources().getIdentifier(name, type, c.getPackageName());
    }

    private void updateWidget(Context context, AppWidgetManager mgr, int id) {
        String pkg = context.getPackageName();
        RemoteViews rv = new RemoteViews(pkg, resId(context, "widget_month", "layout"));

        // 헤더: 선택 방 이름 + 표시 중인 달(오프셋 반영)
        WidgetData.WData data = WidgetData.load(context);
        WidgetData.Room room = data != null ? data.pickRoom(selectedRoomId(context)) : null;
        int off = monthOffset(context);
        java.util.Calendar cal = java.util.Calendar.getInstance();
        cal.add(java.util.Calendar.MONTH, off);
        int month = cal.get(java.util.Calendar.MONTH) + 1;
        rv.setTextViewText(resId(context, "wg_room", "id"), room != null ? room.name : "우리 캘린더");
        rv.setTextViewText(resId(context, "wg_month", "id"), month + "월");
        // 오프셋 이동 중이면 달 라벨을 강조(terra), 오늘 달이면 기본 갈색
        rv.setTextColor(resId(context, "wg_month", "id"), off != 0 ? 0xFFC0503F : 0xFF8B3A2A);

        // GridView 어댑터 → 팩토리 서비스 (id를 data에 넣어 인스턴스별 캐시 무효화)
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

        // 방 이름 탭 = 다음 방으로 순환
        rv.setOnClickPendingIntent(resId(context, "wg_room", "id"),
            broadcast(context, 3, ACTION_CYCLE_ROOM, 0));

        // ‹ 이전 달 / › 다음 달 / 달 라벨 탭 = 오늘 달로 복귀
        rv.setOnClickPendingIntent(resId(context, "wg_prev", "id"),
            broadcast(context, 4, ACTION_SHIFT_MONTH, -1));
        rv.setOnClickPendingIntent(resId(context, "wg_next", "id"),
            broadcast(context, 5, ACTION_SHIFT_MONTH, 1));
        rv.setOnClickPendingIntent(resId(context, "wg_month", "id"),
            broadcast(context, 6, ACTION_SHIFT_MONTH, 0));

        // 새로고침 버튼
        rv.setOnClickPendingIntent(resId(context, "wg_refresh", "id"),
            broadcast(context, 2, ACTION_REFRESH, 0));

        mgr.updateAppWidget(id, rv);
        mgr.notifyAppWidgetViewDataChanged(id, resId(context, "grid", "id"));
    }

    // 이 위젯 프로바이더로 보내는 브로드캐스트 PendingIntent (요청코드는 액션별 고유)
    private PendingIntent broadcast(Context context, int reqCode, String action, int delta) {
        Intent i = new Intent(context, UriCalendarWidgetProvider.class);
        i.setAction(action);
        if (ACTION_SHIFT_MONTH.equals(action)) i.putExtra(EXTRA_DELTA, delta);
        return PendingIntent.getBroadcast(context, reqCode, i,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
}

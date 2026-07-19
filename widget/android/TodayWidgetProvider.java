package com.lsung.uricalendar.widget;

/*
 * 오늘 위젯(small). 컬렉션 없이 고정 행(이벤트 3 + 할일 2). 기본 필터 = 나(내 일정만).
 * 명시 필터(멤버 탭)가 있으면 그걸 우선. 탭=앱 열기(오늘).
 */

import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;
import android.content.Intent;
import android.widget.RemoteViews;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class TodayWidgetProvider extends AppWidgetProvider {

    @Override
    public void onUpdate(Context context, AppWidgetManager mgr, int[] ids) {
        updateAll(context, mgr, ids);
    }

    static void updateAll(Context context, AppWidgetManager mgr, int[] ids) {
        for (int id : ids) mgr.updateAppWidget(id, build(context, id));
    }

    private static RemoteViews build(Context context, int id) {
        RemoteViews rv = new RemoteViews(context.getPackageName(), WidgetCommon.resId(context, "widget_today", "layout"));
        WidgetData.WData data = WidgetData.load(context);
        WidgetData.Room room = data != null ? data.pickRoom(WidgetCommon.selectedRoomId(context)) : null;
        String myUid = data != null ? data.myUserId : null;
        String filter = WidgetCommon.filterUser(context);
        String eff = filter != null ? filter : myUid;
        String today = WidgetCommon.todayKey();

        // 새로고침 + ＋추가
        rv.setOnClickPendingIntent(WidgetCommon.resId(context, "tw_refresh", "id"),
            WidgetCommon.bcast(context, WidgetCommon.RC_REFRESH, WidgetCommon.ACTION_REFRESH, Integer.MIN_VALUE, null));
        rv.setOnClickPendingIntent(WidgetCommon.resId(context, "tw_add", "id"),
            WidgetCommon.openApp(context, WidgetCommon.RC_ADD, room != null ? room.id : null, "__add__"));
        // 헤더/전체 탭 = 앱 열기(오늘)
        rv.setOnClickPendingIntent(WidgetCommon.resId(context, "tw_head", "id"),
            WidgetCommon.openApp(context, WidgetCommon.RC_OPEN, room != null ? room.id : null, today));

        List<WidgetData.Event> todays = new ArrayList<>();
        List<WidgetData.Todo> todos = new ArrayList<>();
        if (room != null) {
            List<WidgetData.Event> pool = new ArrayList<>();
            for (WidgetData.Event e : room.events) {
                if (!today.equals(e.date)) continue;
                if (eff != null && !eff.equals(e.userId)) continue;
                pool.add(e);
            }
            todays = WidgetCommon.dedupeEvents(pool);
            Collections.sort(todays, (a, b) -> {
                String at = (a.time == null || a.time.isEmpty()) ? "zz" : a.time;
                String bt = (b.time == null || b.time.isEmpty()) ? "zz" : b.time;
                return at.compareTo(bt);
            });
            for (WidgetData.Todo t : room.todos) {
                if (today.equals(t.date) && WidgetCommon.todoVisibleFor(t, eff)) todos.add(t);
            }
        }

        // 카운트 배지
        int cId = WidgetCommon.resId(context, "tw_count", "id");
        if (!todays.isEmpty()) {
            rv.setViewVisibility(cId, android.view.View.VISIBLE);
            rv.setTextViewText(cId, String.valueOf(todays.size()));
        } else {
            rv.setViewVisibility(cId, android.view.View.GONE);
        }

        boolean empty = todays.isEmpty() && todos.isEmpty();
        rv.setViewVisibility(WidgetCommon.resId(context, "tw_empty", "id"),
            empty ? android.view.View.VISIBLE : android.view.View.GONE);

        // 이벤트 3행
        for (int i = 0; i < 3; i++) {
            int rowId = WidgetCommon.resId(context, "tw_evrow" + i, "id");
            if (i < todays.size()) {
                WidgetData.Event e = todays.get(i);
                rv.setViewVisibility(rowId, android.view.View.VISIBLE);
                rv.setInt(WidgetCommon.resId(context, "tw_evbar" + i, "id"), "setBackgroundColor", WidgetCommon.parseColor(e.color));
                rv.setTextViewText(WidgetCommon.resId(context, "tw_evtitle" + i, "id"), e.title);
                rv.setTextViewText(WidgetCommon.resId(context, "tw_evtime" + i, "id"), e.time == null ? "" : e.time);
            } else {
                rv.setViewVisibility(rowId, android.view.View.GONE);
            }
        }
        // 할일 2행
        for (int i = 0; i < 2; i++) {
            int rowId = WidgetCommon.resId(context, "tw_tdrow" + i, "id");
            if (i < todos.size()) {
                WidgetData.Todo t = todos.get(i);
                boolean done = WidgetCommon.todoDone(context, t);
                rv.setViewVisibility(rowId, android.view.View.VISIBLE);
                int tId = WidgetCommon.resId(context, "tw_tdtitle" + i, "id");
                String mark = done ? "☑ " : "☐ ";
                if (done) {
                    android.text.SpannableString sp = new android.text.SpannableString(mark + t.title);
                    sp.setSpan(new android.text.style.StrikethroughSpan(), mark.length(), sp.length(), 0);
                    rv.setTextViewText(tId, sp);
                    rv.setTextColor(tId, 0xFF8A6C52);
                } else {
                    rv.setTextViewText(tId, mark + t.title);
                    rv.setTextColor(tId, 0xFF2A1C0F);
                }
                rv.setTextViewText(WidgetCommon.resId(context, "tw_tdtime" + i, "id"), t.time == null ? "" : t.time);
                // 행 탭 = 완료 토글(낙관 반영 → 앱 복귀 시 DB flush)
                rv.setOnClickPendingIntent(rowId, WidgetCommon.toggleTodo(context, t.id));
            } else {
                rv.setViewVisibility(rowId, android.view.View.GONE);
            }
        }
        return rv;
    }
}

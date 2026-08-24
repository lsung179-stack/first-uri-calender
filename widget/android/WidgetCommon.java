package com.lsung.uricalendar.widget;

/*
 * 위젯 공용 로직 — iOS(WidgetKit) UriCalendarWidget.swift의 헬퍼/모델과 동일 계약.
 *  · 위젯-로컬 UI 상태(prefs "uri_widget_state"): 선택 방/멤버 필터/달·주 오프셋.
 *  · 브로드캐스트 액션(방 순환·필터·달/주 이동·새로고침) + PendingIntent 헬퍼.
 *  · 기간 런(run)/레인(lane) 계산, 다가오는 일정 묶기, 색/날짜 유틸, 씰/아바타 비트맵.
 *
 * 모든 상태 브로드캐스트는 UriCalendarWidgetProvider(항상 Manifest 등록됨)로 보내고,
 * 거기서 상태 갱신 후 4종 위젯을 모두 새로고침(refreshAll)한다.
 */

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Shader;
import android.widget.RemoteViews;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class WidgetCommon {

    static final String STATE_PREFS = "uri_widget_state";

    // ── 브로드캐스트 액션 (모두 UriCalendarWidgetProvider로) ──
    static final String ACTION_REFRESH = "com.lsung.uricalendar.widget.REFRESH";
    static final String ACTION_CYCLE_ROOM = "com.lsung.uricalendar.widget.CYCLE_ROOM";
    static final String ACTION_SHIFT_MONTH = "com.lsung.uricalendar.widget.SHIFT_MONTH";
    static final String ACTION_SHIFT_WEEK = "com.lsung.uricalendar.widget.SHIFT_WEEK";
    static final String ACTION_SHIFT_COMBO = "com.lsung.uricalendar.widget.SHIFT_COMBO";
    static final String ACTION_SET_FILTER = "com.lsung.uricalendar.widget.SET_FILTER";
    static final String ACTION_TOGGLE_TODO = "com.lsung.uricalendar.widget.TOGGLE_TODO";
    static final String EXTRA_DELTA = "delta";
    static final String EXTRA_USER = "userId";
    static final String EXTRA_TODO = "todoId";
    static final int RC_TODO_BASE = 1000;   // 할일 토글(요청코드=base+id해시)

    // 요청코드(액션·슬롯별 고유 — FLAG_UPDATE_CURRENT라 extra는 최신값으로 갱신)
    static final int RC_REFRESH = 2, RC_CYCLE = 3;
    static final int RC_MONTH_PREV = 4, RC_MONTH_NEXT = 5, RC_MONTH_RESET = 6;
    static final int RC_WEEK_PREV = 7, RC_WEEK_NEXT = 8, RC_WEEK_RESET = 9;
    static final int RC_COMBO_PREV = 10, RC_COMBO_NEXT = 11, RC_COMBO_RESET = 12;
    static final int RC_FILTER_ALL = 13, RC_FILTER_BASE = 14; // 14,15,16,17 = 멤버 슬롯 0..3
    static final int RC_ADD = 20, RC_OPEN = 21;

    // ── 위젯-로컬 상태 ──
    static SharedPreferences sp(Context c) { return c.getSharedPreferences(STATE_PREFS, Context.MODE_PRIVATE); }
    static String selectedRoomId(Context c) { return sp(c).getString("roomId", null); }
    static void setSelectedRoomId(Context c, String id) {
        SharedPreferences.Editor e = sp(c).edit();
        if (id == null) e.remove("roomId"); else e.putString("roomId", id);
        e.apply();
    }
    static String filterUser(Context c) {
        String v = sp(c).getString("filterUser", null);
        return (v == null || v.isEmpty()) ? null : v;
    }
    static void setFilterUser(Context c, String uid) {
        SharedPreferences.Editor e = sp(c).edit();
        if (uid == null || uid.isEmpty()) e.remove("filterUser"); else e.putString("filterUser", uid);
        e.apply();
    }
    static int monthOffset(Context c) { return sp(c).getInt("monthOffset", 0); }
    static void setMonthOffset(Context c, int n) { sp(c).edit().putInt("monthOffset", n).apply(); }
    static int weekOffset(Context c) { return sp(c).getInt("weekOffset", 0); }
    static void setWeekOffset(Context c, int n) { sp(c).edit().putInt("weekOffset", n).apply(); }
    static int comboOffset(Context c) { return sp(c).getInt("comboOffset", 0); }
    static void setComboOffset(Context c, int n) { sp(c).edit().putInt("comboOffset", n).apply(); }
    // 위젯 실제 높이(dp) — 종류별(0=월,1=2주). 셀 높이·표시 줄 수를 위젯 크기에 맞춰 자동 산정하는 데 사용.
    static int gridHeightDp(Context c, int kind) { return sp(c).getInt(kind == 1 ? "twHdp" : "mHdp", 0); }
    static void setGridHeightDp(Context c, int kind, int dp) { sp(c).edit().putInt(kind == 1 ? "twHdp" : "mHdp", dp).apply(); }
    // 새로고침 깜빡임 상태(잠깐 아이콘 강조 후 원복)
    static boolean isFlash(Context c) { return sp(c).getBoolean("flash", false); }
    static void setFlash(Context c, boolean on) { sp(c).edit().putBoolean("flash", on).apply(); }

    // 상태 브로드캐스트 적용(UriCalendarWidgetProvider.onReceive에서 호출). 처리하면 true.
    static boolean applyAction(Context c, Intent intent) {
        String a = intent.getAction();
        if (a == null) return false;
        switch (a) {
            case ACTION_REFRESH:
                setMonthOffset(c, 0); setWeekOffset(c, 0); setComboOffset(c, 0);
                return true;
            case ACTION_CYCLE_ROOM:
                cycleRoom(c); setMonthOffset(c, 0); setWeekOffset(c, 0); setComboOffset(c, 0);
                return true;
            case ACTION_SHIFT_MONTH: {
                int d = intent.getIntExtra(EXTRA_DELTA, 0);
                setMonthOffset(c, d == 0 ? 0 : monthOffset(c) + d);
                return true;
            }
            case ACTION_SHIFT_WEEK: {
                int d = intent.getIntExtra(EXTRA_DELTA, 0);
                setWeekOffset(c, d == 0 ? 0 : weekOffset(c) + d);
                return true;
            }
            case ACTION_SHIFT_COMBO: {
                int d = intent.getIntExtra(EXTRA_DELTA, 0);
                setComboOffset(c, d == 0 ? 0 : comboOffset(c) + d);
                return true;
            }
            case ACTION_SET_FILTER:
                setFilterUser(c, intent.getStringExtra(EXTRA_USER));
                return true;
            case ACTION_TOGGLE_TODO:
                appendPending(c, intent.getStringExtra(EXTRA_TODO));
                return true;
        }
        return false;
    }

    // ── 할일 낙관 토글 대기열(앱 @capacitor/preferences와 공유하는 CapacitorStorage) ──
    static SharedPreferences capStore(Context c) { return c.getSharedPreferences("CapacitorStorage", Context.MODE_PRIVATE); }
    static int pendingCount(Context c, String id) {
        if (id == null) return 0;
        try {
            String raw = capStore(c).getString("widget.pendingTodoToggles", null);
            if (raw == null || raw.isEmpty()) return 0;
            org.json.JSONArray arr = new org.json.JSONArray(raw);
            int n = 0;
            for (int i = 0; i < arr.length(); i++) if (id.equals(arr.optString(i, ""))) n++;
            return n;
        } catch (Throwable t) { return 0; }
    }
    static void appendPending(Context c, String id) {
        if (id == null) return;
        try {
            String raw = capStore(c).getString("widget.pendingTodoToggles", null);
            org.json.JSONArray arr;
            try { arr = (raw == null || raw.isEmpty()) ? new org.json.JSONArray() : new org.json.JSONArray(raw); }
            catch (Throwable t) { arr = new org.json.JSONArray(); }
            arr.put(id);
            capStore(c).edit().putString("widget.pendingTodoToggles", arr.toString()).apply();
        } catch (Throwable t) { /* 무시 */ }
    }
    // 서버 done XOR 대기열 홀짝 → 위젯 즉시 반영(누르면 회색 표시). 앱 포그라운드 복귀 시 DB flush.
    static boolean todoDone(Context c, WidgetData.Todo t) {
        if (t == null) return false;
        return (pendingCount(c, t.id) % 2 == 1) ? !t.done : t.done;
    }
    static PendingIntent toggleTodo(Context c, String todoId) {
        int rc = RC_TODO_BASE + (todoId == null ? 0 : (todoId.hashCode() & 0x3fffff));
        Intent i = new Intent(c, UriCalendarWidgetProvider.class);
        i.setAction(ACTION_TOGGLE_TODO);
        i.putExtra(EXTRA_TODO, todoId);
        return PendingIntent.getBroadcast(c, rc, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static void cycleRoom(Context c) {
        WidgetData.WData data = WidgetData.load(c);
        if (data == null || data.rooms.size() < 2) return;
        WidgetData.Room cur = data.pickRoom(selectedRoomId(c));
        int idx = 0;
        for (int i = 0; i < data.rooms.size(); i++) {
            if (cur != null && cur.id != null && cur.id.equals(data.rooms.get(i).id)) { idx = i; break; }
        }
        setSelectedRoomId(c, data.rooms.get((idx + 1) % data.rooms.size()).id);
        setFilterUser(c, null);   // 방 바꾸면 멤버 필터 해제
    }

    // 4종 위젯 모두 새로고침
    static void refreshAll(Context c) {
        safeUpdate(c, UriCalendarWidgetProvider.class);
        safeUpdate(c, TwoWeekWidgetProvider.class);
        safeUpdate(c, TodayWidgetProvider.class);
        safeUpdate(c, ComboWidgetProvider.class);
    }
    private static void safeUpdate(Context c, Class<?> cls) {
        try {
            AppWidgetManager mgr = AppWidgetManager.getInstance(c);
            int[] ids = mgr.getAppWidgetIds(new ComponentName(c, cls));
            if (ids == null || ids.length == 0) return;
            if (cls == UriCalendarWidgetProvider.class) UriCalendarWidgetProvider.updateAll(c, mgr, ids);
            else if (cls == TwoWeekWidgetProvider.class) TwoWeekWidgetProvider.updateAll(c, mgr, ids);
            else if (cls == TodayWidgetProvider.class) TodayWidgetProvider.updateAll(c, mgr, ids);
            else if (cls == ComboWidgetProvider.class) ComboWidgetProvider.updateAll(c, mgr, ids);
        } catch (Throwable t) { /* 한 종류 실패해도 나머지 진행 */ }
    }

    // ── PendingIntent 헬퍼 ──
    static int resId(Context c, String name, String type) {
        int id = c.getResources().getIdentifier(name, type, c.getPackageName());
        if (id == 0) id = c.getResources().getIdentifier(name, type, "com.lsung.uricalendar");
        return id;
    }
    // 위젯 build 중 예외가 나도 절대 빈 화면이 되지 않게 — '탭하여 열기' 폴백 RemoteViews.
    static RemoteViews fallbackRV(Context c) {
        RemoteViews rv = new RemoteViews(c.getPackageName(), resId(c, "widget_fallback", "layout"));
        try { rv.setOnClickPendingIntent(resId(c, "wg_fb_root", "id"), openApp(c, RC_OPEN, null, null)); } catch (Throwable t) {}
        return rv;
    }
    static PendingIntent bcast(Context c, int reqCode, String action, int delta, String user) {
        Intent i = new Intent(c, UriCalendarWidgetProvider.class);
        i.setAction(action);
        if (delta != Integer.MIN_VALUE) i.putExtra(EXTRA_DELTA, delta);
        if (user != null) i.putExtra(EXTRA_USER, user);
        return PendingIntent.getBroadcast(c, reqCode, i,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
    // 앱 열기(딥링크 route는 앱이 처리) — MainActivity를 런처로 띄우고 extra 전달
    static PendingIntent openApp(Context c, int reqCode, String roomId, String date) {
        Intent open = new Intent();
        open.setClassName(c, "com.lsung.uricalendar.MainActivity");
        open.setAction(Intent.ACTION_MAIN);
        open.addCategory(Intent.CATEGORY_LAUNCHER);
        if (roomId != null) open.putExtra("widgetRoom", roomId);
        if (date != null) open.putExtra("widgetDate", date);
        return PendingIntent.getActivity(c, reqCode, open,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
    // 앱을 딥링크(커스텀 스킴)로 열기 — MainActivity를 명시 대상으로 하는 ACTION_VIEW 인텐트라
    //  매니페스트 intent-filter 없이도 Capacitor BridgeActivity가 intent.getData()를 읽어 appUrlOpen 발화.
    //  위젯 '보내기' 버튼 등 앱측 JS 핸들러가 필요한 동작에 사용.
    static PendingIntent openScheme(Context c, int reqCode, String uri) {
        Intent open = new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(uri));
        open.setClassName(c, "com.lsung.uricalendar.MainActivity");
        open.addCategory(Intent.CATEGORY_DEFAULT);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return PendingIntent.getActivity(c, reqCode, open,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
    // 컬렉션 셀 fill-in 템플릿 — ACTION_VIEW(데이터 URI 없음)로 둬야
    //  셀별 fill-in 이 date URI(com.lsung.uricalendar://open?room=&date=)를 채워 넣는다.
    //  (템플릿 data 가 null 이어야 Intent.fillIn 이 셀의 data URI를 적용 → 그 날짜의 '달'로 이동)
    //  ACTION_MAIN+LAUNCHER 였을 땐 앱이 '마지막 화면(설정 등)'으로 그냥 복귀해 날짜가 무시됐음.
    //  MUTABLE 이어야 fill-in 이 채워지고, NEW_TASK|SINGLE_TOP 로 열어야 BridgeActivity 가
    //  intent.getData()를 읽어 appUrlOpen(://open) 을 발화(보내기 버튼 openScheme 과 동일 경로).
    static PendingIntent openAppTemplate(Context c, int reqCode, String roomId) {
        Intent open = new Intent(Intent.ACTION_VIEW);
        open.setClassName(c, "com.lsung.uricalendar.MainActivity");
        open.addCategory(Intent.CATEGORY_DEFAULT);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        if (roomId != null) open.putExtra("widgetRoom", roomId); // 백업(data URI 에도 room 포함)
        return PendingIntent.getActivity(c, reqCode, open,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
    }

    // ── 색/날짜 유틸 ──
    static int parseColor(String hex) {
        try {
            String h = hex == null ? "#8b3a2a" : hex.trim();
            if (!h.startsWith("#")) h = "#" + h;
            return Color.parseColor(h);
        } catch (Exception e) { return 0xFF8B3A2A; }
    }
    static int contrastText(int color) {
        int r = (color >> 16) & 0xff, g = (color >> 8) & 0xff, b = color & 0xff;
        double lum = (0.299 * r + 0.587 * g + 0.114 * b) / 255.0;
        return lum > 0.62 ? 0xFF3A2418 : 0xFFFFFFFF;
    }
    static String key(int y, int m0, int d) { return String.format(Locale.US, "%04d-%02d-%02d", y, m0 + 1, d); }
    static String fmt(Calendar c) { return key(c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)); }
    static Calendar calOf(String ymd) {
        String[] p = ymd.split("-");
        Calendar c = Calendar.getInstance();
        c.clear();
        c.set(Integer.parseInt(p[0]), Integer.parseInt(p[1]) - 1, Integer.parseInt(p[2]));
        return c;
    }
    static String addDays(String ymd, int n) {
        Calendar c = calOf(ymd);
        c.add(Calendar.DAY_OF_MONTH, n);
        return fmt(c);
    }
    static String todayKey() { Calendar c = Calendar.getInstance(); return fmt(c); }

    /* ── 날짜 강조(date_highlights) ──────────────────────────────
       그 날짜에 걸린 강조 1건(겹치면 시작일이 이른 것) — 앱 _dhForDate와 동일 규칙.
       (Room.hls는 파싱 때 시작일 오름차순으로 정렬돼 있어 앞에서 처음 걸리는 게 정답) */
    /* 날짜 강조는 '멤버별' 데코 — 지금 보고 있는 멤버 탭의 주인 것만 보인다(앱 _dhForDate 와 같은 규칙).
         · 필터 없음(전체) → 나(myUserId)의 강조
         · 멤버 선택       → 그 멤버의 강조
       [2026-08-22] */
    static WidgetData.Highlight hlFor(WidgetData.Room room, String key, String filter, String myUserId) {
        if (room == null || room.hls == null) return null;
        String own = (filter != null) ? filter : myUserId;
        if (own == null) return null;
        for (WidgetData.Highlight h : room.hls) {
            if (!own.equals(h.userId)) continue;
            if (key.compareTo(h.start) >= 0 && key.compareTo(h.end) <= 0) return h;
        }
        return null;
    }
    /* 어느 변에 테두리를 그릴지 [top,bottom,left,right] — 위/아래/좌/우 이웃 날짜가 범위 밖이면
       그 변이 바깥 경계. 여러 주에 걸친 범위도 이 규칙만으로 자연스럽게 이어진다(앱 _dhSideFlags와 동일).
       위젯은 항상 일요일 시작이라 col = dow. */
    static boolean[] hlSides(WidgetData.Highlight h, String key, int dow) {
        return new boolean[]{
            !hlIn(h, addDays(key, -7)),
            !hlIn(h, addDays(key, 7)),
            dow == 0 || !hlIn(h, addDays(key, -1)),
            dow == 6 || !hlIn(h, addDays(key, 1))
        };
    }
    /* 칸별 플래그(42칸)를 줄(7칸)별로 접는다.
       ⚠️ 예전엔 공휴일/강조 라벨이 있는 줄 전체에 같은 높이를 예약하는 데 썼는데,
          그러면 공휴일이 없는 칸까지 첫 줄이 빈칸이 돼 일정이 하나밖에 안 보였다
          (실기기 제보 2026-08-22). 지금은 '그 칸에 공휴일/라벨이 있을 때만' 자리를 쓴다.
          이 헬퍼는 남겨두지만 렌더에는 쓰지 않는다. */
    /* 셀 높이(dp)에서 그릴 수 있는 '일정 줄' 수를 산정한다.
       날짜 숫자 박스(17dp)+여백, 그 주가 비워두는 공휴일/강조 라벨 줄, 할일 줄을 먼저 뺀다.
       줄 하나 높이 = 13dp(레이아웃 minHeight와 동일). 순수 계산이라 단위테스트로 검증. [2026-08-19] */
    static int laneBudget(int cellDp, int reserveLines, boolean hasTodos) {
        return laneBudget(cellDp, reserveLines, hasTodos, 13);
    }
    /* lineDp = 줄 하나 높이. 시스템 글자 크기 배율이 크면 sp 글자가 13dp 최소높이를 넘어
       줄이 두꺼워지므로 배율을 반영한 값을 넘긴다. */
    static int laneBudget(int cellDp, int reserveLines, boolean hasTodos, int lineDp) {
        int lh = Math.max(1, lineDp);
        int lines = (cellDp - 18) / lh;          // 날짜 숫자 빼고 남는 줄 수
        return lines - Math.max(0, reserveLines) - (hasTodos ? 1 : 0);
    }
    // 시스템 글자 크기 배율에 맞춘 줄 높이(dp). 배율 1이면 레이아웃 기본 13dp.
    static int lineDpFor(float fontScale) {
        float fs = (fontScale > 0.1f) ? fontScale : 1f;
        return Math.max(13, Math.round(11f * fs) + 2);
    }
    // 배율이 크면 헤더(‹ 8월 › ↻ 등 sp 글자)도 커져 그리드가 좁아진다 → 추정치에 반영.
    static int headerDpFor(float fontScale) {
        float fs = (fontScale > 0.1f) ? fontScale : 1f;
        float extra = Math.max(0f, Math.min(1f, fs - 1f));
        return 52 + Math.round(20f * extra);
    }
    static boolean[] rowFlags(boolean[] perCell, int rows) {
        boolean[] out = new boolean[rows];
        if (perCell == null) return out;
        for (int i = 0; i < perCell.length; i++) {
            int r = i / 7;
            if (r < rows && perCell[i]) out[r] = true;
        }
        return out;
    }
    static boolean hlIn(WidgetData.Highlight h, String key) {
        return key.compareTo(h.start) >= 0 && key.compareTo(h.end) <= 0;
    }
    static boolean disjoint(Set<String> a, Set<String> b) {
        Set<String> small = a.size() <= b.size() ? a : b, big = a.size() <= b.size() ? b : a;
        for (String s : small) if (big.contains(s)) return false;
        return true;
    }
    // "M.d" 또는 오늘/내일
    static String dayLabel(String ds) {
        String t = todayKey();
        if (ds.equals(t)) return "오늘";
        if (ds.equals(addDays(t, 1))) return "내일";
        Calendar c = calOf(ds);
        return (c.get(Calendar.MONTH) + 1) + "." + c.get(Calendar.DAY_OF_MONTH);
    }
    static String mdKo(String ds) {
        Calendar c = calOf(ds);
        return (c.get(Calendar.MONTH) + 1) + "월" + c.get(Calendar.DAY_OF_MONTH) + "일";
    }
    static String rangeLabel(String s, String e) { return s.equals(e) ? dayLabel(s) : (mdKo(s) + " ~ " + mdKo(e)); }

    // ── 할일 필터/완료 (iOS와 동일) ──
    static boolean todoVisibleFor(WidgetData.Todo t, String filter) {
        if (filter == null || filter.isEmpty()) return true;
        if (t.memberKeys != null && !t.memberKeys.isEmpty()) return t.memberKeys.contains(filter);
        if (filter.startsWith("v-")) return false;
        return filter.equals(t.userId);
    }

    // 함께 일정 복사본 중복 제거 (같은 날+제목+시간 = 하나)
    static List<WidgetData.Event> dedupeEvents(List<WidgetData.Event> evs) {
        Set<String> seen = new HashSet<>();
        List<WidgetData.Event> out = new ArrayList<>();
        for (WidgetData.Event e : evs) {
            String k = e.date + "|" + e.title + "|" + (e.time == null ? "" : e.time);
            if (seen.add(k)) out.add(e);
        }
        return out;
    }

    // ── 기간 런(run) / 레인(lane) — iOS GridView.runs + occupancy와 동일 ──
    static class DayBar { String title; int color; boolean outline, contLeft, contRight, shared; }
    static class Run {
        String title; int color; boolean outline, shared; int ord; String start, end; int lane;
        Set<String> days = new HashSet<>();
    }

    // startKey~endKey 범위의 이벤트를 gid 런으로 묶고 레인 배정. filter=null=전체.
    static List<Run> buildRuns(List<WidgetData.Event> events, String filter, String startKey, String endKey) {
        return buildRuns(events, filter, startKey, endKey, null);
    }
    /* reserve = 날짜 → 그 칸이 먼저 쓰는 줄 수(공휴일 바·강조 라벨).
       ⚠️ 이 줄들을 레인으로 '미리 점유'해야 기간 바가 공휴일 칸에서 어긋나지 않는다.
          예) 8/15에 공휴일이 있고 8/14~16 기간 일정이면 → 기간 바는 세 날 모두 두 번째 줄,
              공휴일이 없는 8/14·8/16의 첫 줄은 비어 있으니 하루짜리 일정이 그 자리를 채운다.
          (사용자 요청 2026-08-23) */
    static List<Run> buildRuns(List<WidgetData.Event> events, String filter, String startKey, String endKey,
                               Map<String, Integer> reserve) {
        List<Run> all = new ArrayList<>();
        if (events == null) return all;
        Map<String, Run> byGid = new HashMap<>();
        for (WidgetData.Event e : events) {
            if (e.date == null || e.date.compareTo(startKey) < 0 || e.date.compareTo(endKey) > 0) continue;
            if (filter != null && !filter.equals(e.userId)) continue;
            boolean ol = "outline".equals(e.style);
            if (e.gid != null && !e.gid.isEmpty()) {
                Run acc = byGid.get(e.gid);
                if (acc == null) {
                    acc = new Run();
                    acc.title = e.title; acc.color = parseColor(e.color); acc.outline = ol; acc.ord = e.ord; acc.shared = e.shared;
                    byGid.put(e.gid, acc);
                }
                acc.days.add(e.date);
            } else {
                Run r = new Run();
                r.title = e.title; r.color = parseColor(e.color); r.outline = ol; r.ord = e.ord; r.shared = e.shared;
                r.start = e.date; r.end = e.date; r.days.add(e.date);
                all.add(r);
            }
        }
        for (Run acc : byGid.values()) {
            List<String> ds = new ArrayList<>(acc.days);
            Collections.sort(ds);
            int i = 0;
            while (i < ds.size()) {
                int j = i;
                while (j + 1 < ds.size() && addDays(ds.get(j), 1).equals(ds.get(j + 1))) j++;
                Run r = new Run();
                r.title = acc.title; r.color = acc.color; r.outline = acc.outline; r.ord = acc.ord; r.shared = acc.shared;
                r.start = ds.get(i); r.end = ds.get(j);
                for (int k = i; k <= j; k++) r.days.add(ds.get(k));
                all.add(r);
                i = j + 1;
            }
        }
        Collections.sort(all, (a, b) -> {
            boolean ar = a.end.compareTo(a.start) > 0, br = b.end.compareTo(b.start) > 0;
            if (ar != br) return ar ? -1 : 1;
            if (a.ord != b.ord) return Integer.compare(a.ord, b.ord);
            if (!a.start.equals(b.start)) return a.start.compareTo(b.start);
            String at = a.title == null ? "" : a.title, bt = b.title == null ? "" : b.title;
            return at.compareTo(bt);
        });
        /* 레인 배정 — 앱(_computeMonthLanes)과 같은 규칙.
           ① 같은 일정(제목·색·소유 동일)의 런들 중 '날짜가 붙어 있는 것'만 한 덩어리(item)로 본다.
              여러 날(is_multi) 일정은 gid 가 없어 날마다 별개 Run 이 되는데, 붙어 있는 날을 묶어
              같은 줄에 놓아야 한 줄이 두 색으로 섞여 보이지 않는다(실기기 제보 2026-08-22).
              ⚠️ 예전엔 창 전체에서 제목·색만 같으면 전부 한 덩어리로 묶었는데, 그러면 서로 무관한
                 다른 날의 하루 일정까지 같은 줄을 강제로 써서 — 한 곳에서 밀리면 전부 밀린다.
                 공휴일도 없는 날의 첫 줄이 비고 일정이 아래로 내려가고, 같은 일정이 7월 위젯과
                 8월 위젯에서 다른 줄에 뜨던 원인(실기기 제보 2026-08-24).
           ② 줄을 미리 잡는 건 '여러 날에 걸친 덩어리'뿐. 하루짜리는 lane=-1(float)로 두고
              cellBars 가 칸마다 빈 줄을 위에서부터 채운다 — 앱의 float 패킹과 같다. */
        List<String> order = new ArrayList<>();
        Map<String, List<Run>> byGroup = new HashMap<>();
        for (Run r : all) {
            String gk = (r.title == null ? "" : r.title) + "|" + r.color + "|" + r.outline + "|" + r.shared;
            List<Run> g = byGroup.get(gk);
            if (g == null) { g = new ArrayList<>(); byGroup.put(gk, g); order.add(gk); }
            g.add(r);
        }
        // 그룹 안에서 '붙어 있는 런'끼리만 묶어 item 을 만든다.
        List<List<Run>> items = new ArrayList<>();
        for (String gk : order) {
            List<Run> g = byGroup.get(gk);
            Collections.sort(g, (a, b) -> a.start.compareTo(b.start));
            List<Run> cur = new ArrayList<>();
            String curEnd = null;
            for (Run r : g) {
                if (!cur.isEmpty() && r.start.compareTo(addDays(curEnd, 1)) > 0) { items.add(cur); cur = new ArrayList<>(); curEnd = null; }
                cur.add(r);
                if (curEnd == null || r.end.compareTo(curEnd) > 0) curEnd = r.end;
            }
            if (!cur.isEmpty()) items.add(cur);
        }
        List<Set<String>> occ = new ArrayList<>();
        if (reserve != null) {
            for (Map.Entry<String, Integer> e : reserve.entrySet()) {
                int r = e.getValue() == null ? 0 : e.getValue();
                for (int L = 0; L < r; L++) {
                    while (occ.size() <= L) occ.add(new HashSet<String>());
                    occ.get(L).add(e.getKey());
                }
            }
        }
        for (List<Run> item : items) {
            Set<String> days = new HashSet<>();
            for (Run r : item) days.addAll(r.days);
            if (days.size() <= 1) { for (Run r : item) r.lane = -1; continue; }  // 하루짜리는 칸마다 자유 배치
            int lane = 0;
            while (lane < occ.size() && !disjoint(occ.get(lane), days)) lane++;
            if (lane == occ.size()) occ.add(new HashSet<>(days));
            else occ.get(lane).addAll(days);
            for (Run r : item) r.lane = lane;
        }
        return all;
    }

    static DayBar[] cellBars(List<Run> runs, String cellKey, int dow, int maxLanes, int[] overflow) {
        return cellBars(runs, cellKey, dow, maxLanes, overflow, 0);
    }
    /* 한 날짜(cellKey)의 레인별 바 + 넘침. maxLanes 초과분은 overflow[0]에 누적.
       lane<0 인 런(하루짜리)은 이 칸에서 비어 있는 가장 위 줄을 채운다 — 앱의 float 패킹과 동일.
       reserveForCell = 이 칸이 공휴일 바로 먼저 쓰는 줄 수(그 아래부터 채운다). */
    static DayBar[] cellBars(List<Run> runs, String cellKey, int dow, int maxLanes, int[] overflow, int reserveForCell) {
        DayBar[] bars = new DayBar[maxLanes];
        List<Run> floats = new ArrayList<>();
        for (Run r : runs) {
            if (r.start.compareTo(cellKey) <= 0 && cellKey.compareTo(r.end) <= 0) {
                if (r.lane < 0) { floats.add(r); continue; }
                if (r.lane < maxLanes) bars[r.lane] = mkBar(r, cellKey);
                else if (overflow != null) overflow[0]++;
            }
        }
        int next = Math.max(0, reserveForCell);
        for (Run r : floats) {
            while (next < maxLanes && bars[next] != null) next++;
            if (next < maxLanes) bars[next] = mkBar(r, cellKey);
            else if (overflow != null) overflow[0]++;
        }
        return bars;
    }
    private static DayBar mkBar(Run r, String cellKey) {
        DayBar b = new DayBar();
        b.title = r.title; b.color = r.color; b.outline = r.outline; b.shared = r.shared;
        b.contLeft = r.start.compareTo(cellKey) < 0;
        b.contRight = r.end.compareTo(cellKey) > 0;
        return b;
    }

    // ── 다가오는 일정(콤보) — gid 연속 묶기 ──
    static class UpRun { String title; int color; String start, end, time; }
    static List<UpRun> upcoming(List<WidgetData.Event> events, String filter) {
        List<UpRun> result = new ArrayList<>();
        if (events == null) return result;
        String t = todayKey();
        List<WidgetData.Event> pool = new ArrayList<>();
        for (WidgetData.Event e : events) {
            if (e.date == null || e.date.compareTo(t) < 0) continue;
            if (filter != null && !filter.equals(e.userId)) continue;
            pool.add(e);
        }
        pool = dedupeEvents(pool);
        Map<String, String[]> gidMeta = new HashMap<>();          // gid → [title,color]
        Map<String, Map<String, String>> gidDates = new HashMap<>(); // gid → date→time
        for (WidgetData.Event e : pool) {
            if (e.gid != null && !e.gid.isEmpty()) {
                if (!gidMeta.containsKey(e.gid)) { gidMeta.put(e.gid, new String[]{e.title, e.color}); gidDates.put(e.gid, new HashMap<>()); }
                Map<String, String> dm = gidDates.get(e.gid);
                String prev = dm.get(e.date);
                String tm = e.time == null ? "" : e.time;
                if (prev == null || (!tm.isEmpty() && tm.compareTo(prev.isEmpty() ? "zz" : prev) < 0)) dm.put(e.date, tm);
            } else {
                UpRun u = new UpRun();
                u.title = e.title; u.color = parseColor(e.color); u.start = e.date; u.end = e.date; u.time = e.time == null ? "" : e.time;
                result.add(u);
            }
        }
        for (Map.Entry<String, String[]> en : gidMeta.entrySet()) {
            Map<String, String> dm = gidDates.get(en.getKey());
            List<String> ds = new ArrayList<>(dm.keySet());
            Collections.sort(ds);
            int i = 0;
            while (i < ds.size()) {
                int j = i;
                while (j + 1 < ds.size() && addDays(ds.get(j), 1).equals(ds.get(j + 1))) j++;
                UpRun u = new UpRun();
                u.title = en.getValue()[0]; u.color = parseColor(en.getValue()[1]);
                u.start = ds.get(i); u.end = ds.get(j);
                u.time = (i == j) ? dm.get(ds.get(i)) : "";
                result.add(u);
                i = j + 1;
            }
        }
        Collections.sort(result, (a, b) -> !a.start.equals(b.start) ? a.start.compareTo(b.start)
            : (a.title == null ? "" : a.title).compareTo(b.title == null ? "" : b.title));
        return result;
    }

    // 씰/아바타 비트맵 (실패 시 null → 폴백 원 렌더)
    static Bitmap sealBitmap(WidgetData.Room room) {
        if (room == null) return null;
        return WidgetData.decodeDataUrl(room.sealPng);
    }
    static Bitmap avatarBitmap(WidgetData.Member m) {
        if (m == null) return null;
        return WidgetData.decodeDataUrl(m.avatarPng);
    }
    // 실멤버(가상 제외) '나 먼저' 정렬
    static List<WidgetData.Member> orderedMembers(WidgetData.Room room, String myUserId) {
        List<WidgetData.Member> reals = new ArrayList<>();
        if (room != null) for (WidgetData.Member m : room.members) if (m.userId != null && !m.userId.isEmpty()) reals.add(m);
        if (myUserId != null && !myUserId.isEmpty()) {
            Collections.sort(reals, (a, b) -> Integer.compare(myUserId.equals(a.userId) ? 0 : 1, myUserId.equals(b.userId) ? 0 : 1));
        }
        return reals;
    }

    static int dp(Context c, float d) { return Math.round(d * c.getResources().getDisplayMetrics().density); }

    // 원형 아바타 비트맵: 사진 있으면 원형 클립, 없으면 색 원 + 이니셜(iOS 폴백과 동일).
    static Bitmap circleBitmap(Bitmap src, int bgColor, String name, int sizePx) {
        try {
            if (sizePx <= 0) sizePx = 48;
            Bitmap out = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888);
            Canvas cv = new Canvas(out);
            float r = sizePx / 2f;
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            if (src != null) {
                Bitmap sq = Bitmap.createScaledBitmap(src, sizePx, sizePx, true);
                BitmapShader sh = new BitmapShader(sq, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
                p.setShader(sh);
                cv.drawCircle(r, r, r, p);
            } else {
                p.setColor(bgColor);
                cv.drawCircle(r, r, r, p);
                Paint tp = new Paint(Paint.ANTI_ALIAS_FLAG);
                tp.setColor(0xFFFFFFFF);
                tp.setTextAlign(Paint.Align.CENTER);
                tp.setFakeBoldText(true);
                tp.setTextSize(sizePx * 0.46f);
                String s = (name == null || name.isEmpty()) ? "" : name.substring(0, 1);
                cv.drawText(s, r, r - (tp.descent() + tp.ascent()) / 2f, tp);
            }
            return out;
        } catch (Throwable t) { return null; }
    }

    // 그리드/콤보 헤더 공통: 씰(탭=방순환) + 전체·멤버 필터 칩 + 새로고침 + ＋추가.
    // (nav 라벨/버튼은 위젯별로 provider가 별도 설정)
    static void wireGridHeader(Context c, RemoteViews rv, WidgetData.WData data, WidgetData.Room room) {
        String myUid = data != null ? data.myUserId : null;
        String roomId = room != null ? room.id : null;
        String filter = filterUser(c);

        // 씰 = 방 프로필(탭=다음 방)
        int sealId = resId(c, "wg_seal", "id");
        Bitmap seal = sealBitmap(room);
        if (seal == null) seal = circleBitmap(null, 0xFF566F8F, room != null ? room.name : "방", dp(c, 26));
        if (seal != null) rv.setImageViewBitmap(sealId, seal);
        rv.setOnClickPendingIntent(sealId, bcast(c, RC_CYCLE, ACTION_CYCLE_ROOM, Integer.MIN_VALUE, null));

        // '전체' 칩 = 필터 해제
        int allId = resId(c, "wg_all", "id");
        rv.setTextColor(allId, filter == null ? 0xFFF6EFE0 : 0xFF8A6C52);
        rv.setInt(allId, "setBackgroundResource", resId(c, filter == null ? "chip_on" : "chip_off", "drawable"));
        rv.setOnClickPendingIntent(allId, bcast(c, RC_FILTER_ALL, ACTION_SET_FILTER, Integer.MIN_VALUE, ""));

        // 멤버 아바타(최대 4, 나 먼저). 넘치면 마지막 칸 '+N'.
        List<WidgetData.Member> ordered = orderedMembers(room, myUid);
        int maxAv = 4;
        boolean overflow = ordered.size() > maxAv;
        int shownCount = overflow ? (maxAv - 1) : Math.min(ordered.size(), maxAv);
        int avPx = dp(c, 21);
        int[] avIds = { resId(c, "wg_av0", "id"), resId(c, "wg_av1", "id"), resId(c, "wg_av2", "id"), resId(c, "wg_av3", "id") };
        for (int i = 0; i < 4; i++) {
            if (i < shownCount) {
                WidgetData.Member m = ordered.get(i);
                Bitmap circ = circleBitmap(avatarBitmap(m), parseColor(m.color), m.name, avPx);
                rv.setViewVisibility(avIds[i], android.view.View.VISIBLE);
                if (circ != null) rv.setImageViewBitmap(avIds[i], circ);
                boolean sel = filter != null && filter.equals(m.userId);
                rv.setInt(avIds[i], "setBackgroundResource", resId(c, sel ? "av_sel" : "av_none", "drawable"));
                rv.setOnClickPendingIntent(avIds[i], bcast(c, RC_FILTER_BASE + i, ACTION_SET_FILTER, Integer.MIN_VALUE, sel ? "" : m.userId));
            } else {
                rv.setViewVisibility(avIds[i], android.view.View.GONE);
            }
        }
        int moreId = resId(c, "wg_more", "id");
        int hidden = overflow ? (ordered.size() - shownCount) : 0;
        if (hidden > 0) {
            rv.setViewVisibility(moreId, android.view.View.VISIBLE);
            rv.setTextViewText(moreId, "+" + hidden);
            rv.setOnClickPendingIntent(moreId, openApp(c, RC_OPEN, roomId, null));
        } else {
            rv.setViewVisibility(moreId, android.view.View.GONE);
        }

        // 새로고침 (새로고침 중이면 아이콘 강조 = 깜빡임 피드백)
        rv.setTextColor(resId(c, "wg_refresh", "id"), isFlash(c) ? 0xFFC0503F : 0xFF8A6C52);
        rv.setOnClickPendingIntent(resId(c, "wg_refresh", "id"), bcast(c, RC_REFRESH, ACTION_REFRESH, Integer.MIN_VALUE, null));
    }
}

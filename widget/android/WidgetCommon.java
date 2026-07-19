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
        return c.getResources().getIdentifier(name, type, c.getPackageName());
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
    // 컬렉션 셀 fill-in 템플릿 — MUTABLE 이어야 fill-in extra가 채워짐
    static PendingIntent openAppTemplate(Context c, int reqCode, String roomId) {
        Intent open = new Intent();
        open.setClassName(c, "com.lsung.uricalendar.MainActivity");
        open.setAction(Intent.ACTION_MAIN);
        open.addCategory(Intent.CATEGORY_LAUNCHER);
        if (roomId != null) open.putExtra("widgetRoom", roomId);
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
    static class DayBar { String title; int color; boolean outline, contLeft, contRight; }
    static class Run {
        String title; int color; boolean outline; int ord; String start, end; int lane;
        Set<String> days = new HashSet<>();
    }

    // startKey~endKey 범위의 이벤트를 gid 런으로 묶고 레인 배정. filter=null=전체.
    static List<Run> buildRuns(List<WidgetData.Event> events, String filter, String startKey, String endKey) {
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
                    acc.title = e.title; acc.color = parseColor(e.color); acc.outline = ol; acc.ord = e.ord;
                    byGid.put(e.gid, acc);
                }
                acc.days.add(e.date);
            } else {
                Run r = new Run();
                r.title = e.title; r.color = parseColor(e.color); r.outline = ol; r.ord = e.ord;
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
                r.title = acc.title; r.color = acc.color; r.outline = acc.outline; r.ord = acc.ord;
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
        List<Set<String>> occ = new ArrayList<>();
        for (Run r : all) {
            int lane = 0;
            while (lane < occ.size() && !disjoint(occ.get(lane), r.days)) lane++;
            if (lane == occ.size()) occ.add(new HashSet<>(r.days));
            else occ.get(lane).addAll(r.days);
            r.lane = lane;
        }
        return all;
    }

    // 한 날짜(cellKey)의 레인별 바 + 넘침. maxLanes 초과분은 overflow[0]에 누적.
    static DayBar[] cellBars(List<Run> runs, String cellKey, int dow, int maxLanes, int[] overflow) {
        DayBar[] bars = new DayBar[maxLanes];
        for (Run r : runs) {
            if (r.start.compareTo(cellKey) <= 0 && cellKey.compareTo(r.end) <= 0) {
                if (r.lane < maxLanes) {
                    DayBar b = new DayBar();
                    b.title = r.title; b.color = r.color; b.outline = r.outline;
                    b.contLeft = r.start.compareTo(cellKey) < 0;
                    b.contRight = r.end.compareTo(cellKey) > 0;
                    bars[r.lane] = b;
                } else if (overflow != null) overflow[0]++;
            }
        }
        return bars;
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

        // 새로고침 + ＋추가
        rv.setOnClickPendingIntent(resId(c, "wg_refresh", "id"), bcast(c, RC_REFRESH, ACTION_REFRESH, Integer.MIN_VALUE, null));
        rv.setOnClickPendingIntent(resId(c, "wg_add", "id"), openApp(c, RC_ADD, roomId, "__add__"));
    }
}

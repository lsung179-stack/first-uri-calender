package com.lsung.uricalendar.widget;

/*
 * '이번 주' 위젯(large, 4×4) — 오늘이 든 한 주 7일을 하루 한 줄로 세운 목록. [2026-09-26]
 *   · 첫 줄 요일 = payload.weekStart(0=일, 1=월 — 앱 설정 '주 시작 요일').
 *   · 줄마다: 왼쪽 날짜 숫자(굵게)+요일 글자 / 오른쪽 항목 최대 2개 + 넘치면 '+N'.
 *     항목 순서 = 공휴일 이름 → 일정(여러 날 먼저, 그다음 사용자 배치 순서·시간·제목) → 할 일.
 *   · 일정: 색 바(테두리 일정이면 테두리 바) + 시간(HH:MM) + 제목 + 올린 사람 첫 글자 동그라미.
 *   · 할 일: 할 일 색 체크 상자(완료면 채움 + 제목 줄 긋기) + 올린 사람(함께 할 일이면 '함').
 *     ⚠️ 이 위젯에서는 할 일 체크를 하지 않는다(보기 전용 — 승인된 설계). 줄을 누르면 앱이 그 날짜로 열린다.
 *   · 멤버 필터(WidgetCommon.filterUser)·gid 중복 합치기는 2주/월 위젯과 같은 규칙.
 *   · 헤더 오른쪽 = 새로고침 ↻(다른 위젯과 동일). 보내기 버튼은 없음(사용자 요청 v30).
 *   · 오늘 줄은 옅게 칠하고 숫자를 강조색으로. 일요일·공휴일 빨강, 토요일 파랑.
 * 컬렉션(ListView) 없이 고정 7줄(RemoteViews) — 스크롤이 없어 홈 화면에서 밀려 잘릴 일이 없다.
 * iOS 짝: UriCalendarWidget.swift 의 WeekView / WeekWidget(kind "UriWeek").
 */

import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.widget.RemoteViews;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class WeekWidgetProvider extends AppWidgetProvider {

    // 이번 주 위젯 색(승인 시안) — 다른 위젯보다 한 톤 밝은 카드
    static final int C_TEXT = 0xFF3A2418, C_SUB = 0xFF8A6A4D, C_MUTE = 0xFFB8A285;
    static final int C_ACCENT = 0xFF8B3A2A, C_SUN = 0xFFD4382A, C_SAT = 0xFF1F5B9A;

    @Override
    public void onUpdate(Context context, AppWidgetManager mgr, int[] ids) {
        updateAll(context, mgr, ids);
    }

    // 크기 변경 시 줄 높이에 맞춰 '한 줄에 몇 개'·'+N 위치'를 다시 정한다
    @Override
    public void onAppWidgetOptionsChanged(Context context, AppWidgetManager mgr, int appWidgetId, android.os.Bundle newOptions) {
        updateAll(context, mgr, new int[]{ appWidgetId });
    }

    static void updateAll(Context context, AppWidgetManager mgr, int[] ids) {
        for (int id : ids) {
            try { mgr.updateAppWidget(id, build(context, id)); }
            catch (Throwable t) { try { mgr.updateAppWidget(id, WidgetCommon.fallbackRV(context)); } catch (Throwable t2) {} }
        }
    }

    // ── 순수 계산(단위 테스트 WeekWidgetTest 에서 검증) ─────────────────────────

    /* 오늘이 든 주의 7일 키('YYYY-MM-DD'), weekStart 요일부터. */
    static List<String> weekKeys(Calendar today, int ws) {
        Calendar s = WidgetCommon.weekStartOf(today, ws);
        List<String> out = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            out.add(WidgetCommon.fmt(s));
            s.add(Calendar.DAY_OF_MONTH, 1);
        }
        return out;
    }
    // 'M.d – M.d' (헤더 날짜 범위)
    static String rangeLabel(List<String> keys) {
        Calendar a = WidgetCommon.calOf(keys.get(0)), b = WidgetCommon.calOf(keys.get(keys.size() - 1));
        return (a.get(Calendar.MONTH) + 1) + "." + a.get(Calendar.DAY_OF_MONTH)
            + " – " + (b.get(Calendar.MONTH) + 1) + "." + b.get(Calendar.DAY_OF_MONTH);
    }
    // '19:00' / '19:00~20:30' / '9:30 …' → 앞의 시각만(숫자와 ':'). 없으면 "".
    static String shortTime(String t) {
        if (t == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < t.length(); i++) {
            char ch = t.charAt(i);
            if ((ch >= '0' && ch <= '9') || ch == ':') sb.append(ch); else break;
        }
        return sb.indexOf(":") > 0 ? sb.toString() : "";
    }

    static final int K_HOLIDAY = 0, K_EVENT = 1, K_TODO = 2;
    static class Item {
        int kind; String title = "", time = ""; int color; boolean outline, multi; int ordKey;
        String userId; WidgetData.Todo todo;   // 할 일이면 원본(완료 표시는 Context 필요 → 그릴 때 계산)
        boolean together;                        // 함께 할 일(memberKeys 2명 이상) → '함'
    }

    /* 그 날짜의 일정 — 멤버 필터 적용, 같은 gid 는 하나로(함께 일정 복사본 합치기, 2주/월 위젯과 동일).
       정렬: 여러 날 일정 먼저(2주/월 위젯이 기간 바를 위 줄에 두는 것과 같은 순서) → ord(사용자 배치 순서)
             → 시간(종일 먼저) → 제목.
       '여러 날' 판정 = 앞/뒷날에 같은 gid 가 있거나, 같은 일정(제목·색·테두리·함께 여부)이 붙어 있음
       — 2주/월 위젯 레인 배정(buildRuns 의 item 묶음)과 같은 기준. */
    static List<Item> dayEvents(WidgetData.Room room, String filter, String key) {
        List<Item> out = new ArrayList<>();
        if (room == null || room.events == null) return out;
        String prev = WidgetCommon.addDays(key, -1), next = WidgetCommon.addDays(key, 1);
        Set<String> neigh = new HashSet<>();
        for (WidgetData.Event e : room.events) {
            if (e.date == null || !(e.date.equals(prev) || e.date.equals(next))) continue;
            if (filter != null && !filter.equals(e.userId)) continue;
            neigh.add(groupKey(e));
            if (e.gid != null && !e.gid.isEmpty()) neigh.add("g:" + e.gid);
        }
        Set<String> seenGid = new HashSet<>();
        for (WidgetData.Event e : room.events) {
            if (!key.equals(e.date)) continue;
            if (filter != null && !filter.equals(e.userId)) continue;
            boolean hasGid = e.gid != null && !e.gid.isEmpty();
            if (hasGid && !seenGid.add(e.gid)) continue;   // 같은 gid = 한 번만(먼저 들어온 것 = 앱이 내 것을 앞에 둠)
            Item it = new Item();
            it.kind = K_EVENT;
            it.title = e.title == null ? "" : e.title;
            it.time = shortTime(e.time);
            it.color = WidgetCommon.parseColor(e.color);
            it.outline = "outline".equals(e.style);
            it.userId = e.userId;
            it.multi = neigh.contains(groupKey(e)) || (hasGid && neigh.contains("g:" + e.gid));
            it.ordKey = e.ord;
            out.add(it);
        }
        Collections.sort(out, (a, b) -> {
            if (a.multi != b.multi) return a.multi ? -1 : 1;
            if (a.ordKey != b.ordKey) return Integer.compare(a.ordKey, b.ordKey);
            if (!a.time.equals(b.time)) return a.time.compareTo(b.time);   // "" (종일) 이 먼저
            return a.title.compareTo(b.title);
        });
        return out;
    }
    private static String groupKey(WidgetData.Event e) {
        return (e.title == null ? "" : e.title) + "|" + WidgetCommon.parseColor(e.color) + "|" + "outline".equals(e.style) + "|" + e.shared;
    }

    /* 한 줄(하루)의 전체 항목 — 공휴일 → 일정 → 할 일(멤버 필터는 2주/월 위젯과 같은 todoVisibleFor). */
    static List<Item> dayItems(WidgetData.Room room, String filter, String key, Map<String, String> holidays) {
        List<Item> out = new ArrayList<>();
        String hn = holidays != null ? holidays.get(key) : null;
        if (hn != null && !hn.isEmpty()) {
            Item h = new Item(); h.kind = K_HOLIDAY; h.title = hn; h.color = C_SUN; out.add(h);
        }
        out.addAll(dayEvents(room, filter, key));
        if (room != null && room.todos != null) for (WidgetData.Todo t : room.todos) {
            if (!key.equals(t.date) || !WidgetCommon.todoVisibleFor(t, filter)) continue;
            Item it = new Item();
            it.kind = K_TODO;
            it.title = t.title == null ? "" : t.title;
            it.color = WidgetCommon.parseColor(t.color);
            it.userId = t.userId;
            it.todo = t;
            it.together = t.memberKeys != null && t.memberKeys.size() >= 2;
            out.add(it);
        }
        return out;
    }

    /* 줄 높이(dp)에 맞춘 배치: [한 줄 최대 항목 수(1~2), '+N'을 아래 줄에 둘 수 있는지(1/0)].
       항목 줄 = 11sp 글자, '+N' 줄 = 9.5sp. 자리가 없으면 '+N'은 마지막 항목 끝에 붙인다. */
    static int[] rowPlan(float rowDp, float fontScale) {
        float fs = fontScale > 0.1f ? fontScale : 1f;
        float item = 11f * fs * 1.33f + 2f;     // 항목 한 줄(+간격)
        float more = 9.5f * fs * 1.33f + 1f;
        float avail = rowDp - 6f;               // 줄 위아래 여백 3+3
        int max = avail >= item * 2 ? 2 : 1;
        int below = (avail >= item * max + more) ? 1 : 0;
        return new int[]{ max, below };
    }

    // ── 그리기 ─────────────────────────────────────────────────────────

    private static RemoteViews build(Context c, int widgetId) {
        RemoteViews rv = new RemoteViews(c.getPackageName(), WidgetCommon.resId(c, "widget_week", "layout"));
        WidgetData.WData data = WidgetData.load(c);
        WidgetData.Room room = data != null ? data.pickRoom(WidgetCommon.selectedRoomId(c)) : null;
        String filter = WidgetCommon.filterUser(c);
        int ws = WidgetCommon.weekStart(data);
        String today = WidgetCommon.todayKey();
        String roomId = room != null ? room.id : null;
        List<String> keys = weekKeys(Calendar.getInstance(), ws);

        // 헤더: 제목 · 날짜 범위 · 방 이름 · 새로고침(↻, 2주/월 위젯과 같은 동작·피드백)
        // (보내기 버튼은 사용자 요청으로 모든 위젯에서 뺐다 — v30. 여기도 두지 않는다)
        rv.setTextViewText(id(c, "wk_range"), rangeLabel(keys));
        rv.setTextViewText(id(c, "wk_room"), room != null ? room.name : "");
        String rq = roomId != null ? roomId : "";
        int refId = id(c, "wk_refresh");
        rv.setTextViewText(refId, WidgetCommon.refreshGlyph(c));
        rv.setTextColor(refId, WidgetCommon.isFlash(c) ? 0xFFC0503F : 0xFF8A6C52);
        rv.setOnClickPendingIntent(refId,
            WidgetCommon.bcast(c, WidgetCommon.RC_REFRESH, WidgetCommon.ACTION_REFRESH, Integer.MIN_VALUE, null));
        // 줄 밖(헤더·여백)을 누르면 앱(그 방)을 연다 — 줄·새로고침은 각자 자기 동작이 우선
        rv.setOnClickPendingIntent(id(c, "wk_root"),
            WidgetCommon.openScheme(c, WidgetCommon.RC_OPEN, "com.lsung.uricalendar://open?room=" + rq));

        boolean noRoom = room == null;
        rv.setViewVisibility(id(c, "wk_empty"), noRoom ? android.view.View.VISIBLE : android.view.View.GONE);
        rv.setViewVisibility(id(c, "wk_list"), noRoom ? android.view.View.GONE : android.view.View.VISIBLE);
        if (noRoom) {
            rv.setOnClickPendingIntent(id(c, "wk_empty"), WidgetCommon.openApp(c, WidgetCommon.RC_OPEN, null, null));
            return rv;
        }

        // 줄 높이 → 한 줄에 보일 항목 수
        float fs = 1f;
        int hDp = 0;
        try {
            fs = c.getResources().getConfiguration().fontScale;
            android.os.Bundle o = AppWidgetManager.getInstance(c).getAppWidgetOptions(widgetId);
            if (o != null) {
                boolean land = c.getResources().getConfiguration().orientation
                    == android.content.res.Configuration.ORIENTATION_LANDSCAPE;
                hDp = o.getInt(land ? AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT
                                    : AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 0);
            }
        } catch (Throwable t) { /* 기본값 */ }
        if (hDp <= 0) hDp = 330;                                           // 4×4 대략값
        float headDp = 20f + 14f * (fs > 0.1f ? fs : 1f) * 1.33f + 6f;     // 루트 위아래 여백 + 제목 줄 + 간격
        int[] plan = rowPlan((hDp - headDp) / 7f, fs);
        int maxItems = plan[0];
        boolean moreBelow = plan[1] == 1;

        // 혼자 쓰는 방이면 사람 동그라미를 숨긴다(시안: 멤버 1명 방은 배지 없이 가볍게)
        Map<String, String> initialByUid = new HashMap<>();
        int realMembers = 0;
        for (WidgetData.Member m : room.members) {
            if (m.userId == null || m.userId.isEmpty()) continue;
            if (!initialByUid.containsKey(m.userId)) realMembers++;
            String n = m.name == null ? "" : m.name.trim();
            initialByUid.put(m.userId, n.isEmpty() ? "" : n.substring(0, n.offsetByCodePoints(0, 1)));
        }
        boolean showWho = realMembers >= 2;
        Map<String, Bitmap> avCache = new HashMap<>();
        float density = c.getResources().getDisplayMetrics().density;

        for (int r = 0; r < 7; r++) {
            String key = keys.get(r);
            Calendar d = WidgetCommon.calOf(key);
            int dow = d.get(Calendar.DAY_OF_WEEK) - 1;
            boolean isToday = key.equals(today);
            String hol = data.holidays != null ? data.holidays.get(key) : null;
            boolean red = dow == 0 || (hol != null && !hol.isEmpty());
            int dayColor = red ? C_SUN : (dow == 6 ? C_SAT : C_TEXT);
            int dowColor = red ? C_SUN : (dow == 6 ? C_SAT : C_SUB);

            int rowId = id(c, "wk_row" + r);
            rv.setInt(rowId, "setBackgroundResource", isToday ? WidgetCommon.resId(c, "week_today_bg", "drawable") : 0);
            rv.setTextViewText(id(c, "wk_num" + r), String.valueOf(d.get(Calendar.DAY_OF_MONTH)));
            rv.setTextColor(id(c, "wk_num" + r), isToday ? C_ACCENT : dayColor);
            rv.setTextViewText(id(c, "wk_dow" + r), WidgetCommon.DOW_KO[dow]);
            rv.setTextColor(id(c, "wk_dow" + r), dowColor);
            // 줄 탭 → 그 방·그 날짜로 앱 열기(2주/월 위젯 날짜 칸과 같은 ://open 딥링크)
            rv.setOnClickPendingIntent(rowId, WidgetCommon.openScheme(c, WidgetCommon.RC_WEEK_DAY_BASE + r,
                "com.lsung.uricalendar://open?" + (roomId != null ? "room=" + roomId + "&" : "") + "date=" + key));

            List<Item> items = dayItems(room, filter, key, data.holidays);
            int shown = Math.min(maxItems, items.size());
            int more = items.size() - shown;
            rv.setViewVisibility(id(c, "wk_emp" + r), items.isEmpty() ? android.view.View.VISIBLE : android.view.View.GONE);

            for (int k = 0; k < 2; k++) {
                int slot = id(c, "wk_it" + r + k);
                if (k >= shown) { rv.setViewVisibility(slot, android.view.View.GONE); continue; }
                rv.setViewVisibility(slot, android.view.View.VISIBLE);
                drawItem(c, rv, r, k, items.get(k), showWho, initialByUid, avCache, density);
            }
            // '+N' — 자리가 있으면 아래 줄(시안), 없으면 마지막 항목 줄 끝에 붙인다(줄 높이를 안 넘게)
            boolean inline = more > 0 && !moreBelow;
            boolean below = more > 0 && moreBelow;
            for (int k = 0; k < 2; k++) {
                int miId = id(c, "wk_mi" + r + k);
                boolean on = inline && k == shown - 1;
                rv.setViewVisibility(miId, on ? android.view.View.VISIBLE : android.view.View.GONE);
                if (on) rv.setTextViewText(miId, "+" + more);
            }
            int moreId = id(c, "wk_more" + r);
            rv.setViewVisibility(moreId, below ? android.view.View.VISIBLE : android.view.View.GONE);
            if (below) rv.setTextViewText(moreId, "+" + more);
        }
        return rv;
    }

    private static void drawItem(Context c, RemoteViews rv, int r, int k, Item it, boolean showWho,
                                 Map<String, String> initialByUid, Map<String, Bitmap> avCache, float density) {
        int mk = id(c, "wk_mk" + r + k), tm = id(c, "wk_tm" + r + k), tx = id(c, "wk_tx" + r + k), av = id(c, "wk_av" + r + k);
        if (it.kind == K_HOLIDAY) {
            rv.setViewVisibility(mk, android.view.View.GONE);
            rv.setViewVisibility(tm, android.view.View.GONE);
            rv.setViewVisibility(av, android.view.View.GONE);
            android.text.SpannableString sp = new android.text.SpannableString(it.title);
            sp.setSpan(new android.text.style.StyleSpan(android.graphics.Typeface.BOLD), 0, sp.length(), 0);
            rv.setTextViewText(tx, sp);
            rv.setTextViewTextSize(tx, android.util.TypedValue.COMPLEX_UNIT_SP, 9.5f);
            rv.setTextColor(tx, C_SUN);
            return;
        }
        rv.setTextViewTextSize(tx, android.util.TypedValue.COMPLEX_UNIT_SP, 11f);
        boolean done = it.kind == K_TODO && WidgetCommon.todoDone(c, it.todo);
        Bitmap m = (it.kind == K_EVENT) ? barBitmap(it.color, it.outline, density) : boxBitmap(it.color, done, density);
        if (m != null) { rv.setViewVisibility(mk, android.view.View.VISIBLE); rv.setImageViewBitmap(mk, m); }
        else rv.setViewVisibility(mk, android.view.View.GONE);

        if (it.kind == K_EVENT && !it.time.isEmpty()) {
            rv.setViewVisibility(tm, android.view.View.VISIBLE);
            rv.setTextViewText(tm, it.time);
        } else {
            rv.setViewVisibility(tm, android.view.View.GONE);
        }

        if (done) {
            android.text.SpannableString sp = new android.text.SpannableString(it.title);
            sp.setSpan(new android.text.style.StrikethroughSpan(), 0, sp.length(), 0);
            rv.setTextViewText(tx, sp);
            rv.setTextColor(tx, C_MUTE);
        } else {
            rv.setTextViewText(tx, it.title);
            rv.setTextColor(tx, C_TEXT);
        }

        String who = null;
        if (showWho) {
            if (it.kind == K_TODO && it.together) who = "함";
            else if (it.userId != null) who = initialByUid.get(it.userId);
        }
        if (who != null && !who.isEmpty()) {
            Bitmap b = avCache.get(who);
            if (b == null) {
                b = WidgetCommon.circleBitmap(null, C_ACCENT, who, Math.round(13f * density));
                if (b != null) avCache.put(who, b);
            }
            if (b != null) { rv.setViewVisibility(av, android.view.View.VISIBLE); rv.setImageViewBitmap(av, b); }
            else rv.setViewVisibility(av, android.view.View.GONE);
        } else {
            rv.setViewVisibility(av, android.view.View.GONE);
        }
    }

    // 일정 색 바 3×12dp — 테두리 일정은 채움 대신 같은 색 테두리
    static Bitmap barBitmap(int color, boolean outline, float density) {
        try {
            int w = Math.max(2, Math.round(3f * density)), h = Math.max(6, Math.round(12f * density));
            Bitmap bm = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            Canvas cv = new Canvas(bm);
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setColor(color);
            float rad = 1.5f * density;
            if (outline) {
                float sw = Math.max(1f, 1f * density);
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(sw);
                cv.drawRoundRect(sw / 2f, sw / 2f, w - sw / 2f, h - sw / 2f, rad, rad, p);
            } else {
                cv.drawRoundRect(0f, 0f, w, h, rad, rad, p);
            }
            return bm;
        } catch (Throwable t) { return null; }
    }
    // 할 일 체크 상자 10dp — 할 일 색 테두리, 완료면 같은 색(옅게)으로 채움
    static Bitmap boxBitmap(int color, boolean done, float density) {
        try {
            int s = Math.max(6, Math.round(10f * density));
            Bitmap bm = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888);
            Canvas cv = new Canvas(bm);
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            float sw = 1.4f * density, rad = 3f * density;
            if (done) {
                p.setColor((color & 0x00FFFFFF) | 0x8C000000);   // 55%
                cv.drawRoundRect(0f, 0f, s, s, rad, rad, p);
            }
            p.setColor(done ? ((color & 0x00FFFFFF) | 0x8C000000) : color);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(sw);
            cv.drawRoundRect(sw / 2f, sw / 2f, s - sw / 2f, s - sw / 2f, rad, rad, p);
            return bm;
        } catch (Throwable t) { return null; }
    }

    private static int id(Context c, String name) { return WidgetCommon.resId(c, name, "id"); }
}

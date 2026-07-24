package com.lsung.uricalendar.widget;

/*
 * 위젯 데이터 로더 — SharedPreferences "CapacitorStorage"의 "widget.data"(JSON)를 읽어 파싱.
 * iOS WGData와 동일 계약(rooms/members/events/todos/gid...).
 */

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Base64;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public class WidgetData {

    // "data:image/png;base64,XXXX" → Bitmap (씰/아바타 PNG). 실패 시 null.
    public static Bitmap decodeDataUrl(String dataUrl) {
        try {
            if (dataUrl == null || dataUrl.isEmpty()) return null;
            int comma = dataUrl.indexOf(',');
            String b64 = comma >= 0 ? dataUrl.substring(comma + 1) : dataUrl;
            byte[] bytes = Base64.decode(b64, Base64.DEFAULT);
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
        } catch (Throwable t) { return null; }
    }

    public static WData load(Context context) {
        try {
            android.content.SharedPreferences prefs =
                context.getSharedPreferences("CapacitorStorage", Context.MODE_PRIVATE);
            String raw = prefs.getString("widget.data", null);
            if (raw == null) return null;
            return WData.parse(new JSONObject(raw));
        } catch (Exception e) {
            return null;
        }
    }

    // ── 데이터 모델 ──
    public static class WData {
        public String currentRoomId;
        public String myUserId;
        public boolean gridV, gridH;
        public List<Room> rooms = new ArrayList<>();

        public Room pickRoom() { return pickRoom(null); }

        // 위젯에서 사용자가 순환 선택한 방(override) 우선 → 앱 지정 방 → 첫 방
        public Room pickRoom(String overrideId) {
            if (rooms.isEmpty()) return null;
            if (overrideId != null) {
                for (Room r : rooms) if (overrideId.equals(r.id)) return r;
            }
            if (currentRoomId != null) {
                for (Room r : rooms) if (currentRoomId.equals(r.id)) return r;
            }
            return rooms.get(0);
        }

        static WData parse(JSONObject o) {
            WData d = new WData();
            d.currentRoomId = optStr(o, "currentRoomId");
            d.myUserId = optStr(o, "myUserId");
            d.gridV = o.optBoolean("gridV", false);
            d.gridH = o.optBoolean("gridH", false);
            JSONArray ra = o.optJSONArray("rooms");
            if (ra != null) for (int i = 0; i < ra.length(); i++) {
                JSONObject r = ra.optJSONObject(i);
                if (r != null) d.rooms.add(Room.parse(r));
            }
            return d;
        }
    }

    public static class Room {
        public String id, name, seal, sealPng;
        public List<Member> members = new ArrayList<>();
        public List<Event> events = new ArrayList<>();
        public List<Todo> todos = new ArrayList<>();

        static Room parse(JSONObject r) {
            Room room = new Room();
            room.id = r.optString("id", "");
            room.name = r.optString("name", "방");
            room.seal = optStr(r, "seal");
            room.sealPng = optStr(r, "sealPng");
            JSONArray ms = r.optJSONArray("members");
            if (ms != null) for (int i = 0; i < ms.length(); i++) {
                JSONObject m = ms.optJSONObject(i);
                if (m != null) {
                    Member mm = new Member();
                    mm.userId = optStr(m, "userId");
                    mm.name = m.optString("name", "멤버");
                    mm.color = m.optString("color", "#8b3a2a");
                    mm.avatarPng = optStr(m, "avatarPng");
                    room.members.add(mm);
                }
            }
            JSONArray es = r.optJSONArray("events");
            if (es != null) for (int i = 0; i < es.length(); i++) {
                JSONObject e = es.optJSONObject(i);
                if (e != null) {
                    Event ev = new Event();
                    ev.date = e.optString("date", "");
                    ev.title = e.optString("title", "");
                    ev.time = e.optString("time", "");
                    ev.color = e.optString("color", "#8b3a2a");
                    ev.userId = optStr(e, "userId");
                    ev.gid = optStr(e, "gid");
                    ev.style = e.optString("style", "solid");
                    ev.ord = e.optInt("ord", 0);
                    ev.shared = e.optBoolean("shared", false);
                    room.events.add(ev);
                }
            }
            JSONArray ts = r.optJSONArray("todos");
            if (ts != null) for (int i = 0; i < ts.length(); i++) {
                JSONObject t = ts.optJSONObject(i);
                if (t != null) {
                    Todo td = new Todo();
                    td.id = optStr(t, "id");
                    td.date = t.optString("date", "");
                    td.title = t.optString("title", "");
                    td.time = t.optString("time", "");
                    td.done = t.optBoolean("done", false);
                    td.color = t.optString("color", "#8b3a2a");
                    td.userId = optStr(t, "userId");
                    JSONArray mk = t.optJSONArray("memberKeys");
                    if (mk != null) {
                        td.memberKeys = new ArrayList<>();
                        for (int k = 0; k < mk.length(); k++) {
                            String s = mk.optString(k, null);
                            if (s != null && !s.isEmpty()) td.memberKeys.add(s);
                        }
                    }
                    room.todos.add(td);
                }
            }
            return room;
        }
    }

    public static class Member { public String userId, name, color, avatarPng; }
    public static class Event { public String date, title, time, color, userId, gid, style; public int ord; public boolean shared; }
    public static class Todo {
        public String id, date, title, time, color, userId;
        public boolean done;
        public List<String> memberKeys;   // 함께 할일 대상(없으면 null=혼자 할일)
    }

    static String optStr(JSONObject o, String key) {
        if (o.isNull(key)) return null;
        String v = o.optString(key, null);
        return (v == null || v.isEmpty()) ? null : v;
    }
}

#!/usr/bin/env bash
# 우리 캘린더 — Android 위젯 주입 (Codemagic android 워크플로에서 cap add/sync 후 실행)
# RemoteViews(순수 Java)라 gradle 의존성 추가 없이 파일 복사 + Manifest 등록만 하면 됨.
#   bash widget/android/inject_android_widget.sh
# 위젯 4종: 오늘(small)·2주(medium)·콤보(large)·월(large). 상태 브로드캐스트 중앙수신=UriCalendarWidgetProvider.
set -e

WSRC="widget/android"
APP="android/app/src/main"
JAVA_DIR="$APP/java/com/lsung/uricalendar/widget"

# 1) Java 소스
mkdir -p "$JAVA_DIR"
cp "$WSRC"/*.java "$JAVA_DIR/"
echo "✅ 위젯 Java 파일 복사: $(ls "$WSRC"/*.java | wc -l)개"

# 2) 리소스 (layout / xml / drawable)
mkdir -p "$APP/res/layout" "$APP/res/xml" "$APP/res/drawable"
cp "$WSRC/res/layout/"*.xml "$APP/res/layout/"
cp "$WSRC/res/xml/"*.xml "$APP/res/xml/"
cp "$WSRC/res/drawable/"*.xml "$APP/res/drawable/"
echo "✅ 위젯 리소스 복사 완료"

# 3) AndroidManifest에 receiver 4종 + service 3종 등록 (</application> 앞)
MANIFEST="$APP/AndroidManifest.xml"
if ! grep -q "UriCalendarWidgetProvider" "$MANIFEST"; then
  read -r -d '' BLOCK <<'XML' || true
    <receiver android:name=".widget.UriCalendarWidgetProvider" android:exported="false">
        <intent-filter>
            <action android:name="android.appwidget.action.APPWIDGET_UPDATE" />
            <action android:name="com.lsung.uricalendar.widget.REFRESH" />
            <action android:name="com.lsung.uricalendar.widget.CYCLE_ROOM" />
            <action android:name="com.lsung.uricalendar.widget.SHIFT_MONTH" />
            <action android:name="com.lsung.uricalendar.widget.SHIFT_WEEK" />
            <action android:name="com.lsung.uricalendar.widget.SHIFT_COMBO" />
            <action android:name="com.lsung.uricalendar.widget.SET_FILTER" />
            <action android:name="com.lsung.uricalendar.widget.TOGGLE_TODO" />
        </intent-filter>
        <meta-data android:name="android.appwidget.provider" android:resource="@xml/uri_widget_month_info" />
    </receiver>
    <receiver android:name=".widget.TwoWeekWidgetProvider" android:exported="false">
        <intent-filter>
            <action android:name="android.appwidget.action.APPWIDGET_UPDATE" />
        </intent-filter>
        <meta-data android:name="android.appwidget.provider" android:resource="@xml/uri_widget_twoweek_info" />
    </receiver>
    <receiver android:name=".widget.TodayWidgetProvider" android:exported="false">
        <intent-filter>
            <action android:name="android.appwidget.action.APPWIDGET_UPDATE" />
        </intent-filter>
        <meta-data android:name="android.appwidget.provider" android:resource="@xml/uri_widget_today_info" />
    </receiver>
    <receiver android:name=".widget.ComboWidgetProvider" android:exported="false">
        <intent-filter>
            <action android:name="android.appwidget.action.APPWIDGET_UPDATE" />
        </intent-filter>
        <meta-data android:name="android.appwidget.provider" android:resource="@xml/uri_widget_combo_info" />
    </receiver>
    <service android:name=".widget.MonthWidgetService" android:permission="android.permission.BIND_REMOTEVIEWS" android:exported="false" />
    <service android:name=".widget.TwoWeekWidgetService" android:permission="android.permission.BIND_REMOTEVIEWS" android:exported="false" />
    <service android:name=".widget.ComboMiniService" android:permission="android.permission.BIND_REMOTEVIEWS" android:exported="false" />
XML
  # </application> 앞에 삽입
  BLOCK="$BLOCK" perl -0pi -e 's#([ \t]*)</application>#$ENV{BLOCK}."\n$1</application>"#e' "$MANIFEST"
  echo "✅ Manifest에 위젯 receiver 4종 + service 3종 주입 완료"
else
  echo "위젯이 이미 Manifest에 있음 — 스킵"
fi

echo "🎉 Android 위젯 주입 끝 (파일 $(ls "$WSRC"/*.java | wc -l)개 Java)"

#!/usr/bin/env bash
# 우리 캘린더 — Android 위젯 주입 (Codemagic android 워크플로에서 cap add/sync 후 실행)
# RemoteViews(순수 Java)라 gradle 의존성 추가 없이 파일 복사 + Manifest 등록만 하면 됨.
#   bash widget/android/inject_android_widget.sh
# 위젯 4종: 오늘(small)·2주(medium)·콤보(large)·월(large). 헤더 버튼 실행=WidgetActionActivity(투명·즉시 finish).
set -e

WSRC="widget/android"
APP="android/app/src/main"
JAVA_DIR="$APP/java/com/lsung/uricalendar/widget"

# 1) Java 소스
mkdir -p "$JAVA_DIR"
cp "$WSRC"/*.java "$JAVA_DIR/"
echo "✅ 위젯 Java 파일 복사: $(ls "$WSRC"/*.java | wc -l)개"

# 2) 리소스 (layout / xml / drawable + 위젯 피커 미리보기 PNG)
mkdir -p "$APP/res/layout" "$APP/res/xml" "$APP/res/drawable" "$APP/res/drawable-nodpi"
cp "$WSRC/res/layout/"*.xml "$APP/res/layout/"
cp "$WSRC/res/xml/"*.xml "$APP/res/xml/"
cp "$WSRC/res/drawable/"*.xml "$APP/res/drawable/"
# 위젯 피커 미리보기 이미지(previewImage) — 삼성/안드로이드 위젯 추가화면 썸네일
if ls "$WSRC/res/drawable-nodpi/"*.png >/dev/null 2>&1; then
  cp "$WSRC/res/drawable-nodpi/"*.png "$APP/res/drawable-nodpi/"
fi
echo "✅ 위젯 리소스 복사 완료"

# 3) AndroidManifest에 receiver 4종 + activity 1종 + service 3종 등록 (</application> 앞)
MANIFEST="$APP/AndroidManifest.xml"
if ! grep -q "UriCalendarWidgetProvider" "$MANIFEST"; then
  read -r -d '' BLOCK <<'XML' || true
    <receiver android:name=".widget.UriCalendarWidgetProvider" android:exported="false" android:label="우리 캘린더 · 월">
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
    <receiver android:name=".widget.TwoWeekWidgetProvider" android:exported="false" android:label="우리 캘린더 · 2주">
        <intent-filter>
            <action android:name="android.appwidget.action.APPWIDGET_UPDATE" />
        </intent-filter>
        <meta-data android:name="android.appwidget.provider" android:resource="@xml/uri_widget_twoweek_info" />
    </receiver>
    <receiver android:name=".widget.TodayWidgetProvider" android:exported="false" android:label="우리 캘린더 · 오늘">
        <intent-filter>
            <action android:name="android.appwidget.action.APPWIDGET_UPDATE" />
        </intent-filter>
        <meta-data android:name="android.appwidget.provider" android:resource="@xml/uri_widget_today_info" />
    </receiver>
    <receiver android:name=".widget.ComboWidgetProvider" android:exported="false" android:label="우리 캘린더 · 콤보">
        <intent-filter>
            <action android:name="android.appwidget.action.APPWIDGET_UPDATE" />
        </intent-filter>
        <meta-data android:name="android.appwidget.provider" android:resource="@xml/uri_widget_combo_info" />
    </receiver>
    <!-- 위젯 헤더 버튼(방 프로필·멤버 필터·달/주 이동·새로고침·할일 토글) 실행 대상.
         보이지 않는 투명 액티비티 — 삼성 앱 절전 등에서 브로드캐스트가 버려지는 상황에도
         사용자 탭에서 시작되는 액티비티는 항상 전달되므로 헤더가 죽지 않는다. -->
    <activity android:name=".widget.WidgetActionActivity"
        android:exported="false"
        android:theme="@android:style/Theme.Translucent.NoTitleBar"
        android:excludeFromRecents="true"
        android:noHistory="true"
        android:taskAffinity="" />
    <service android:name=".widget.MonthWidgetService" android:permission="android.permission.BIND_REMOTEVIEWS" android:exported="false" />
    <service android:name=".widget.TwoWeekWidgetService" android:permission="android.permission.BIND_REMOTEVIEWS" android:exported="false" />
    <service android:name=".widget.ComboMiniService" android:permission="android.permission.BIND_REMOTEVIEWS" android:exported="false" />
XML
  # </application> 앞에 삽입
  BLOCK="$BLOCK" perl -0pi -e 's#([ \t]*)</application>#$ENV{BLOCK}."\n$1</application>"#e' "$MANIFEST"
  echo "✅ Manifest에 위젯 receiver 4종 + activity 1종 + service 3종 주입 완료"
else
  echo "위젯이 이미 Manifest에 있음 — 스킵"
fi

echo "🎉 Android 위젯 주입 끝 (파일 $(ls "$WSRC"/*.java | wc -l)개 Java)"

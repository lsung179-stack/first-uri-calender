#!/usr/bin/env bash
# 우리 캘린더 — Android 위젯 주입 (Codemagic android 워크플로에서 cap add/sync 후 실행)
# RemoteViews(순수 Java)라 gradle 의존성 추가 없이 파일 복사 + Manifest 등록만 하면 됨.
#   bash widget/android/inject_android_widget.sh
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

# 3) AndroidManifest에 receiver + service 등록 (</application> 앞)
MANIFEST="$APP/AndroidManifest.xml"
if ! grep -q "UriCalendarWidgetProvider" "$MANIFEST"; then
  perl -0pi -e 's#([ \t]*)</application>#$1    <receiver android:name=".widget.UriCalendarWidgetProvider" android:exported="false">\n$1        <intent-filter>\n$1            <action android:name="android.appwidget.action.APPWIDGET_UPDATE" />\n$1            <action android:name="com.lsung.uricalendar.widget.REFRESH" />\n$1        </intent-filter>\n$1        <meta-data android:name="android.appwidget.provider" android:resource="\@xml/uri_widget_month_info" />\n$1    </receiver>\n$1    <service android:name=".widget.MonthWidgetService" android:permission="android.permission.BIND_REMOTEVIEWS" android:exported="false" />\n$1</application>#' "$MANIFEST"
  echo "✅ Manifest에 위젯 receiver/service 주입 완료"
else
  echo "위젯이 이미 Manifest에 있음 — 스킵"
fi

echo "🎉 Android 위젯 주입 끝"

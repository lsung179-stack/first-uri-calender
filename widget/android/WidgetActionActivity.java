package com.lsung.uricalendar.widget;

/*
 * 위젯 헤더 버튼(방 프로필·멤버 필터·달/주 이동·새로고침·할일 토글)의 실행 대상.
 *
 * ⚠️ 왜 브로드캐스트가 아니라 '보이지 않는 액티비티'인가 [2026-09-06]
 *   원래는 PendingIntent.getBroadcast 로 UriCalendarWidgetProvider 에 직접 쏘고 있었는데,
 *   갤럭시에서 헤더 버튼이 전부 무반응이라는 제보가 있었다. 같은 위젯의 '날짜 칸 탭'(액티비티
 *   PendingIntent)은 정상 동작한다는 게 확인돼 있어, 앱이 삼성 '앱 절전/잠자는 앱' 같은
 *   제한 버킷에 들어가면 브로드캐스트만 조용히 버려지는 상황이 유력하다.
 *   액티비티 시작은 사용자 탭에서 비롯되면 항상 허용되므로, 상태 변경을 전부 이 액티비티로 돌린다.
 *
 * 화면에는 아무것도 안 나온다: 투명 테마 + onCreate 에서 바로 finish + 전환 애니메이션 0.
 * (taskAffinity="" + noHistory + excludeFromRecents 로 앱 본체 화면/최근앱에도 안 끼어든다.)
 */

import android.app.Activity;
import android.os.Bundle;

public class WidgetActionActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try { WidgetCommon.handleAction(this, getIntent()); } catch (Throwable t) { /* 무시 */ }
        finish();
        try { overridePendingTransition(0, 0); } catch (Throwable t) { /* 무시 */ }
    }
}

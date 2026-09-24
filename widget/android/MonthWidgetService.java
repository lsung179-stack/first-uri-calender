package com.lsung.uricalendar.widget;

import android.content.Intent;
import android.widget.RemoteViewsService;

public class MonthWidgetService extends RemoteViewsService {
    @Override
    public RemoteViewsFactory onGetViewFactory(Intent intent) {
        return new GridWidgetFactory(getApplicationContext(), GridWidgetFactory.MONTH,
            intent.getIntExtra(android.appwidget.AppWidgetManager.EXTRA_APPWIDGET_ID,
                               android.appwidget.AppWidgetManager.INVALID_APPWIDGET_ID));   // 위젯마다 실제 높이로 칸 산정
    }
}

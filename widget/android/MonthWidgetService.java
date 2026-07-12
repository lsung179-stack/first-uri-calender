package com.lsung.uricalendar.widget;

import android.content.Intent;
import android.widget.RemoteViewsService;

public class MonthWidgetService extends RemoteViewsService {
    @Override
    public RemoteViewsFactory onGetViewFactory(Intent intent) {
        return new MonthWidgetFactory(getApplicationContext());
    }
}

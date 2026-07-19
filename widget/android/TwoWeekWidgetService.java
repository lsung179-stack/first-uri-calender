package com.lsung.uricalendar.widget;

import android.content.Intent;
import android.widget.RemoteViewsService;

public class TwoWeekWidgetService extends RemoteViewsService {
    @Override
    public RemoteViewsFactory onGetViewFactory(Intent intent) {
        return new GridWidgetFactory(getApplicationContext(), GridWidgetFactory.TWOWEEK);
    }
}

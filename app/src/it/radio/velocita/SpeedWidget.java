package it.radio.velocita;

import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;

/** Widget "km/h": the SpeedService (always active as a notification listener) keeps it updated. */
public class SpeedWidget extends AppWidgetProvider {

    @Override
    public void onUpdate(Context c, AppWidgetManager m, int[] ids) {
        SpeedService.start(c);
    }
}

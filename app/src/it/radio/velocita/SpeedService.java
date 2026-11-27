package it.radio.velocita;

import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.RemoteException;
import android.service.notification.NotificationListenerService;
import android.view.View;
import android.widget.RemoteViews;

import java.lang.reflect.Method;

/**
 * Reads the speed from the radio's MainUI service (the same one the stock launcher uses for "KM/h")
 * and writes it into the widget every half second, with the outside temperature from the CAN bus;
 * it also hosts the Bluetooth music bridge.
 *
 * It is a NotificationListenerService only so that Android keeps it always running (also after a reboot)
 * without a foreground-service notification and its status-bar icon. It does nothing with notifications.
 * Enabled with: cmd notification allow_listener it.radio.velocita/it.radio.velocita.SpeedService
 */
public class SpeedService extends NotificationListenerService implements ServiceConnection {

    // ITsCommon interface of com.ts.MainUI 1.0.75 (transaction numbers from its decompiled Stub)
    private static final String TOKEN = "com.ts.main.common.ITsCommon";
    private static final int TX_SEND_MCU_KEY = 6;      // (int key): the stock launcher's prev/next buttons
    private static final int TX_WORK_MODE = 10;        // active source: 1 = radio
    private static final int TX_GET_SPEED = 23;        // km/h as a float
    private static final int TX_GET_BAND = 33;         // "FM" or "AM"
    private static final int TX_GET_FREQ = 34;         // "98,70" (FM, MHz) or "1008" (AM, kHz)
    private static final int TX_SPECIAL_BINDER = 55;   // ("Radio") returns the ITsRadioCommon binder
    private static final String RADIO_TOKEN = "com.ts.main.common.ITsRadioCommon";
    private static final int TX_RADIO_PS_NAME = 30;    // RDS station name, 15 chars, blank without RDS
    private static final int WORKMODE_RADIO = 1;
    static final int KEY_NEXT = 44;
    static final int KEY_PREV = 45;
    private static final long PERIOD_MS = 500;
    // MainUI writes the CAN outside temperature here (e.g. "14,5℃"); it is what the hidden status bar shows
    private static final String TEMP_PROP = "forfan.user.info";

    private static volatile boolean redraw;  // a widget was added: redraw even if the speed is unchanged

    private final Handler handler = new Handler(Looper.getMainLooper());
    private IBinder mainUi;
    private IBinder radio;  // ITsRadioCommon, asked to MainUI once
    private String shown;
    private Method getProp;  // android.os.SystemProperties.get(String): hidden API, still allowed on Android 10
    private BtMediaBridge btMedia;

    /** Called by the widget: redraw it, and ask Android to bind the service again if it was unbound. */
    static void start(Context c) {
        redraw = true;
        requestRebind(new ComponentName(c, SpeedService.class));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        bindService(new Intent("android.intent.action.MAIN_UI").setPackage("com.ts.MainUI"), this, BIND_AUTO_CREATE);
        handler.post(tick);
        btMedia = new BtMediaBridge(this, this::sendKey);  // same always-on service: BT music and radio in the launcher
        btMedia.start();
    }

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            show(readSpeed(), readTemp());
            btMedia.setRadio(readRadio());
            handler.postDelayed(this, PERIOD_MS);
        }
    };

    private interface Reply<T> { T read(Parcel reply); }

    /** One raw binder call to MainUI: interface token, optional argument (String or Integer), typed reply. */
    private static <T> T call(IBinder b, String token, int code, Object arg, Reply<T> r) throws RemoteException {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(token);
            if (arg instanceof String) data.writeString((String) arg);
            if (arg instanceof Integer) data.writeInt((Integer) arg);
            b.transact(code, data, reply, 0);
            reply.readException();
            return r.read(reply);
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    private String readSpeed() {
        IBinder b = mainUi;
        if (b == null) return "--";
        try {
            return format(call(b, TOKEN, TX_GET_SPEED, null, Parcel::readFloat));
        } catch (Exception e) {
            return "--"; // MainUI restarting: the binding reconnects by itself
        }
    }

    /** {title, subtitle} of the radio station when the radio is the active source, otherwise null. */
    private String[] readRadio() {
        IBinder b = mainUi;
        if (b == null) return null;
        try {
            if (call(b, TOKEN, TX_WORK_MODE, null, Parcel::readInt) != WORKMODE_RADIO) return null;
            String band = call(b, TOKEN, TX_GET_BAND, null, Parcel::readString);
            String freq = call(b, TOKEN, TX_GET_FREQ, null, Parcel::readString);
            if (radio == null) radio = call(b, TOKEN, TX_SPECIAL_BINDER, "Radio", Parcel::readStrongBinder);
            String ps = radio == null ? null : call(radio, RADIO_TOKEN, TX_RADIO_PS_NAME, null, Parcel::readString);
            return station(band, freq, ps);
        } catch (Exception e) {
            radio = null;  // MainUI restarted: ask for the radio binder again
            return null;
        }
    }

    /** Same text as the stock launcher ("FM 98.70 MHz"), under the RDS name when there is one. */
    static String[] station(String band, String freq, String ps) {
        if (band == null || freq == null) return null;
        String f = band + " " + freq.replace(',', '.') + (band.contains("FM") ? " MHz" : " kHz");
        String name = ps == null ? "" : ps.replace('\0', ' ').trim();
        return name.isEmpty() ? new String[]{f, "Radio"} : new String[]{name, f};
    }

    /** A key as if pressed on the head unit (the stock launcher uses it for radio prev/next). */
    private void sendKey(int key) {
        IBinder b = mainUi;
        if (b == null) return;
        try {
            call(b, TOKEN, TX_SEND_MCU_KEY, key, p -> null);
        } catch (Exception ignored) {
            // MainUI restarting: the key is lost, the user presses again
        }
    }

    private String readTemp() {
        try {
            if (getProp == null) getProp = Class.forName("android.os.SystemProperties").getMethod("get", String.class);
            return ((String) getProp.invoke(null, TEMP_PROP)).trim();
        } catch (Exception e) {
            return "";
        }
    }

    static String format(float kmh) {
        if (Float.isNaN(kmh) || kmh < 0 || kmh > 400) return "--";
        return String.valueOf(Math.round(kmh));
    }

    private void show(String speed, String temp) {
        String text = speed + "|" + temp;
        if (text.equals(shown) && !redraw) return;
        shown = text;
        redraw = false;
        RemoteViews v = new RemoteViews(getPackageName(), R.layout.widget);
        v.setTextViewText(R.id.speed, speed);
        v.setTextViewText(R.id.temp, temp);
        v.setViewVisibility(R.id.temp, temp.isEmpty() ? View.GONE : View.VISIBLE);
        AppWidgetManager.getInstance(this).updateAppWidget(new ComponentName(this, SpeedWidget.class), v);
    }

    @Override
    public void onServiceConnected(ComponentName name, IBinder binder) {
        mainUi = binder;
    }

    @Override
    public void onServiceDisconnected(ComponentName name) {
        mainUi = null;
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacks(tick);
        btMedia.stop();
        unbindService(this);
        super.onDestroy();
    }
}

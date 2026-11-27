package it.radio.velocita;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.media.MediaMetadata;
import android.media.browse.MediaBrowser;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import java.util.Arrays;
import java.util.function.IntConsumer;

/**
 * The radio's Bluetooth music has a media session that is never "active" and posts no notification,
 * so launchers (Kvaesitso) don't see it. This bridge connects to the Bluetooth MediaBrowserService,
 * mirrors it into a session of its own and posts a media notification with that session.
 *
 * Why its own session instead of the Bluetooth one: the radio often misses the phone's
 * "paused" status and keeps reporting "playing", so the launcher kept showing the pause button.
 * Here, after a play/pause command, the state we expect is shown right away, until the phone
 * reports something newer.
 *
 * When the radio is the active source, the same session and notification show the station instead
 * (the stock launcher showed it on the home screen); prev/next then seek like the stock launcher's buttons.
 */
class BtMediaBridge extends MediaBrowser.ConnectionCallback {

    private final ComponentName BT_BROWSER = new ComponentName(
            "com.android.bluetooth", "com.android.bluetooth.avrcpcontroller.BluetoothMediaBrowserService");
    private final ComponentName BT_MUSIC_SCREEN = new ComponentName(
            "com.ts.MainUI", "com.ts.bt.BtMusicActivity");
    private final ComponentName RADIO_SCREEN = new ComponentName(
            "com.ts.MainUI", "com.ts.main.radio.RadioMainActivity");
    private static final int NOTIFICATION_ID = 2;
    private static final long RETRY_MS = 10_000;
    // after one of our commands, ignore contradicting Bluetooth states for this long (updates already in flight)
    private static final long COMMAND_GRACE_MS = 2_000;
    private static final long ACTIONS = PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE
            | PlaybackState.ACTION_PLAY_PAUSE | PlaybackState.ACTION_SKIP_TO_NEXT
            | PlaybackState.ACTION_SKIP_TO_PREVIOUS | PlaybackState.ACTION_STOP;

    private final Context ctx;
    private final IntConsumer radioKey;  // sends a head-unit key through MainUI
    private final NotificationManager nm;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final MediaSession session;
    private MediaBrowser browser;
    private MediaController bt;
    private int commanded = -1;      // state requested by our last command, -1 = follow Bluetooth
    private long commandedAt;
    private String shownTrack;       // "title|artist" already given to the session
    private String posted;           // "title|artist|playing" of the notification on screen
    private String[] radio;          // {title, subtitle} of the station while the radio is the source, else null

    private final MediaController.Callback btCallback = new MediaController.Callback() {
        @Override public void onMetadataChanged(MediaMetadata metadata) { publish(); }
        @Override public void onPlaybackStateChanged(PlaybackState state) {
            boolean agrees = state != null && state.getState() == commanded;
            if (agrees || SystemClock.elapsedRealtime() - commandedAt > COMMAND_GRACE_MS) commanded = -1;
            publish();
        }
        @Override public void onSessionDestroyed() { reconnectLater(); }
    };

    BtMediaBridge(Context ctx, IntConsumer radioKey) {
        this.ctx = ctx;
        this.radioKey = radioKey;
        nm = ctx.getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel("media", ctx.getString(R.string.channel_media), NotificationManager.IMPORTANCE_MIN));
        session = new MediaSession(ctx, "BtMediaBridge");
        session.setCallback(new MediaSession.Callback() {
            // play/pause do nothing on the radio: it has no pause, and they must not start the phone's music
            @Override public void onPlay() { if (radio == null) command(PlaybackState.STATE_PLAYING); }
            @Override public void onPause() { if (radio == null) command(PlaybackState.STATE_PAUSED); }
            @Override public void onStop() { if (radio == null) command(PlaybackState.STATE_PAUSED); }
            @Override public void onSkipToNext() {
                if (radio != null) radioKey.accept(SpeedService.KEY_NEXT);
                else if (bt != null) bt.getTransportControls().skipToNext();
            }
            @Override public void onSkipToPrevious() {
                if (radio != null) radioKey.accept(SpeedService.KEY_PREV);
                else if (bt != null) bt.getTransportControls().skipToPrevious();
            }
        }, handler);
    }

    void start() {
        browser = new MediaBrowser(ctx, BT_BROWSER, this, null);
        browser.connect();
    }

    void stop() {
        disconnect();
        session.release();
    }

    private void disconnect() {
        handler.removeCallbacksAndMessages(null);
        if (bt != null) bt.unregisterCallback(btCallback);
        bt = null;
        if (browser != null) browser.disconnect();
        clear();
    }

    /** Called every half second by the service: the station, or null when the radio is not the source. */
    void setRadio(String[] station) {
        if (Arrays.equals(station, radio)) return;
        radio = station;
        shownTrack = null;  // the session metadata now belongs to the other source
        publish();
    }

    private void clear() {
        nm.cancel(NOTIFICATION_ID);
        shownTrack = null;
        posted = null;
    }

    @Override
    public void onConnected() {
        bt = new MediaController(ctx, browser.getSessionToken());
        bt.registerCallback(btCallback, handler);
        publish();
    }

    @Override public void onConnectionSuspended() { reconnectLater(); }
    @Override public void onConnectionFailed() { reconnectLater(); }

    private void reconnectLater() {
        disconnect();
        handler.postDelayed(this::start, RETRY_MS);
    }

    private void command(int state) {
        if (bt == null) return;
        if (state == PlaybackState.STATE_PLAYING) bt.getTransportControls().play();
        else bt.getTransportControls().pause();
        commanded = state;
        commandedAt = SystemClock.elapsedRealtime();
        publish();
    }

    /** State to show: the one we just commanded, otherwise what the Bluetooth reports. */
    static int shownState(int btState, int commanded) {
        return commanded != -1 ? commanded : btState;
    }

    private void publishRadio() {
        String track = radio[0] + "|" + radio[1];
        if (!track.equals(shownTrack)) {
            session.setMetadata(new MediaMetadata.Builder()
                    .putString(MediaMetadata.METADATA_KEY_TITLE, radio[0])
                    .putString(MediaMetadata.METADATA_KEY_ARTIST, radio[1])
                    .build());
            shownTrack = track;
        }
        session.setPlaybackState(new PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_SKIP_TO_NEXT | PlaybackState.ACTION_SKIP_TO_PREVIOUS)
                .setState(PlaybackState.STATE_PLAYING, PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1f)
                .build());
        post(radio[0], radio[1], true, RADIO_SCREEN);
    }

    private void publish() {
        if (radio != null) {
            publishRadio();
            return;
        }
        MediaMetadata m = bt == null ? null : bt.getMetadata();
        String title = m == null ? null : m.getString(MediaMetadata.METADATA_KEY_TITLE);
        if (title == null || title.isEmpty()) {  // no phone connected / nothing playing
            clear();
            return;
        }
        String artist = m.getString(MediaMetadata.METADATA_KEY_ARTIST);
        PlaybackState s = bt.getPlaybackState();
        int state = shownState(s == null ? PlaybackState.STATE_NONE : s.getState(), commanded);
        boolean playing = state == PlaybackState.STATE_PLAYING;
        long position = s == null ? 0 : s.getPosition();
        String track = title + "|" + artist;
        if (!track.equals(shownTrack)) {
            session.setMetadata(m);
            shownTrack = track;
        }
        session.setPlaybackState(new PlaybackState.Builder()
                .setActions(ACTIONS)
                .setState(state, position, playing ? 1f : 0f,
                        s == null ? SystemClock.elapsedRealtime() : s.getLastPositionUpdateTime())
                .build());

        post(title, artist, playing, BT_MUSIC_SCREEN);
    }

    private void post(String title, String text, boolean playing, ComponentName screen) {
        // the Bluetooth sends a position update about every second: repost only when something visible changes
        String key = title + "|" + text + "|" + playing;
        if (key.equals(posted)) return;
        posted = key;
        PendingIntent open = PendingIntent.getActivity(ctx, 0,
                new Intent().setComponent(screen).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_UPDATE_CURRENT);
        session.setSessionActivity(open);  // a tap on the launcher's media widget opens the source's screen
        nm.notify(NOTIFICATION_ID, new Notification.Builder(ctx, "media")
                .setSmallIcon(R.drawable.ic_music)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(open)
                .setOngoing(playing)
                .setShowWhen(false)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setStyle(new Notification.MediaStyle().setMediaSession(session.getSessionToken()))
                .build());
    }
}

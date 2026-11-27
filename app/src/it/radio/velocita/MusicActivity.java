package it.radio.velocita;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Bundle;

/** "BT Music" icon: opens the radio's BT Music screen. It also makes Kvaesitso list this app among the media players. */
public class MusicActivity extends Activity {
    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        startActivity(new Intent().setComponent(new ComponentName("com.ts.MainUI", "com.ts.bt.BtMusicActivity"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        finish();
    }
}

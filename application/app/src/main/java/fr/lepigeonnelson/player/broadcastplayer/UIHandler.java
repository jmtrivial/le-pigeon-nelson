package fr.lepigeonnelson.player.broadcastplayer;

import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.util.ArrayMap;
import android.util.Log;
import android.util.Pair;

import java.util.ArrayList;

public class UIHandler extends Handler {
    public static final int END_OF_BROADCAST = 0;
    public static final int SERVER_ERROR = 1;
    public static final int SERVER_CONTENT_ERROR = 2;
    public static final int NO_GPS = 3;
    public static final int NEW_SERVER_DESCRIPTION = 4;
    public static final int UPDATE_LIST = 5;
    public static final int CURRENT_SERVER = 6;
    public static final int STATUS_PLAYING = 7;
    public static final int STATUS_NOT_PLAYING = 8;
    public static final int SENSOR_SETTINGS_RESULT = 9;
    public static final int NEW_PUBLIC_SERVER = 10;
    public static final int INTERNAL_VALUES = 11;

    private BroadcastPlayer.BroadcastPlayerListener listener;



    public UIHandler(Looper looper) {
        super(looper);
        listener = null;
    }

    public void handleMessage(Message msg) {
        final int what = msg.what;
        if (what == END_OF_BROADCAST) {
            if (listener != null) {
                Log.d("BroadcastPlayer", "End of broadcast sent to UI");
                if (listener != null)
                    listener.onEndOfBroadcast();
            }
        }
        else if (what == SERVER_ERROR) {
            Log.d("BroadcastPlayer", "Error while reading server");
            if (listener != null)
                listener.onServerError();
        }
        else if (what == SERVER_CONTENT_ERROR) {
            Log.d("BroadcastPlayer", "Error while reading server (content error)");
            if (listener != null)
                listener.onServerContentError();
        }
        else if (what == NO_GPS) {
            Log.d("BroadcastPlayer", "Cannot get GPS coordinate");
            if (listener != null)
                listener.onServerGPSError();
        }
        else if (what == NEW_SERVER_DESCRIPTION) {
            Log.d("BroadcastPlayer", "New server description received");
            ServerDescription description = (ServerDescription) msg.obj;
            if (listener != null)
                listener.onServerDescriptionUpdate(description);
        }
        else if (what == UPDATE_LIST) {
            if (listener != null)
                listener.onServerListUpdated();
        }
        else if (what == CURRENT_SERVER) {
            Log.d("BroadcastPlayer", "Asking for current server");
            ServerDescription description = (ServerDescription) msg.obj;
            if (listener != null)
                listener.onCurrentServerRequest(description);
        }
        else if (what == NEW_PUBLIC_SERVER) {
            String url = (String) msg.obj;
            if (listener != null)
                listener.onNewPublicServer(url);
        }
        else if (what == STATUS_NOT_PLAYING) {
            if (listener != null)
                listener.onStatusNotPlaying();
        }
        else if (what == STATUS_PLAYING) {
            if (listener != null)
                listener.onStatusPlaying();
        }
        else if (what == SENSOR_SETTINGS_RESULT) {
            int error = (int) msg.obj;
            if (listener != null)
                listener.onSensorSettingsInit(error);
        }
        else if (what == INTERNAL_VALUES) {
            ArrayList<Pair<String, String>> values = (ArrayList<Pair<String, String>>) msg.obj;
            if (listener != null)
                listener.onInternalValues(values);
        }
    }

    public void setListener(BroadcastPlayer.BroadcastPlayerListener listener) {
        this.listener = listener;
    }
}

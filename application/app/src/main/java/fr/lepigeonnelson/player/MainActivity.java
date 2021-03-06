package fr.lepigeonnelson.player;

import android.Manifest;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;

import fr.lepigeonnelson.player.broadcastplayer.SensorsService;
import fr.lepigeonnelson.player.broadcastplayer.ServerDescription;
import fr.lepigeonnelson.player.db_storage.AppDatabase;
import fr.lepigeonnelson.player.ui.EditServerFragment;
import fr.lepigeonnelson.player.ui.ListenBroadcastFragment;
import fr.lepigeonnelson.player.ui.ScannerFragment;
import fr.lepigeonnelson.player.ui.ServerSelectionFragment;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.navigation.Navigation;
import androidx.navigation.fragment.NavHostFragment;
import androidx.preference.PreferenceManager;
import androidx.room.Room;

import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.util.ArrayMap;
import android.util.Log;
import android.util.Pair;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Iterator;

public class MainActivity extends AppCompatActivity implements AppDatabase.AppDataBaseListener,
        PigeonNelsonService.ServiceCallbacks {

    public static final int EDIT_SERVER_FRAGMENT = 1;
    public static final int SERVER_SELECTION_FRAGMENT = 2;
    public static final int SETTINGS_FRAGMENT = 3;
    public static final int LISTEN_BROADCAST_FRAGMENT = 4;
    public static final int ADD_SERVER_FRAGMENT = 5;

    public ArrayList<ServerDescription> debugServers;
    public ArrayList<ServerDescription> userDefinedServers;
    public ArrayList<ServerDescription> servers;

    public ArrayList<ServerDescription> publicServers;

    private AppDatabase db;
    private int activeFragmentType;
    private MenuItem itemSettings;
    private ServerDescription editedServer;
    private Fragment activeFragment;

    PigeonNelsonService mService;
    boolean mBound = false;
    private PigeonNelsonServiceConnection connection;
    private int currentSensorSettingResult;
    private MenuItem reload;
    private boolean showVerboseMessages;


    public boolean isMainFragment() {
        return activeFragmentType == SERVER_SELECTION_FRAGMENT;
    }

    public boolean isListenBroadcastFragment() {
        return activeFragmentType == LISTEN_BROADCAST_FRAGMENT;
    }

    public boolean isAddFragment() {
        return activeFragmentType == ADD_SERVER_FRAGMENT;
    }

    private final int REQUEST_PERMISSION_FINE_LOCATION = 2;
    private final int REQUEST_PERMISSION_CAMERA = 3;
    private boolean showDebugServers;
    private Toolbar toolbar;

    // a function to request permissions
    private void requestPermission(String permissionName, int permissionRequestCode) {
        ActivityCompat.requestPermissions(this,
                new String[]{permissionName}, permissionRequestCode);
    }

    // a function to show explanation when asking permission
    private void showExplanation(String title,
                                 String message,
                                 final String permission,
                                 final int permissionRequestCode) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(title)
                .setMessage(message)
                .setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int id) {
                        requestPermission(permission, permissionRequestCode);
                    }
                });
        builder.create().show();
    }

    // show a small message depending on the permission result
    @Override
    public void onRequestPermissionsResult(
            int requestCode,
            String permissions[],
            int[] grantResults) {
        switch (requestCode) {
            case REQUEST_PERMISSION_FINE_LOCATION:
                if (grantResults.length > 0
                        && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    Toast.makeText(this, "Accès localisation précise accordée.", Toast.LENGTH_SHORT).show();
                    checkSensorsSettings();
               } else {
                    Toast.makeText(this, "Accès localisation précise refusée.", Toast.LENGTH_SHORT).show();
                }
                break;
            case REQUEST_PERMISSION_CAMERA:
                if (grantResults.length > 0
                        && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    Toast.makeText(this, "Accès caméra accordée.", Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(this, "Accès caméra refusée.", Toast.LENGTH_SHORT).show();
                }
                break;

        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        activeFragmentType = SERVER_SELECTION_FRAGMENT;
        super.onCreate(savedInstanceState);

        currentSensorSettingResult = -1;
        editedServer = null;
        activeFragment = null;

        db = Room.databaseBuilder(getApplicationContext(),
                AppDatabase.class, "pigeon-nelson-database").build();
        db.setListener(this);

        // first of all, check permissions for location
        checkGPSPermission();

        setContentView(R.layout.activity_main);
        toolbar = findViewById(R.id.toolbar);
        if (toolbar != null) {
            setSupportActionBar(toolbar);
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }


        // load preferences
        loadPreferences();

        // load servers
        loadServers();

    }

    private void checkGPSPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            if (ActivityCompat.shouldShowRequestPermissionRationale(this,
                    Manifest.permission.ACCESS_FINE_LOCATION)) {
                showExplanation("Accès localisation précise requise", "Le Pigeon Nelson a besoin d'un accès au GPS pour fonctionner.",
                        Manifest.permission.ACCESS_FINE_LOCATION, REQUEST_PERMISSION_FINE_LOCATION);
            } else {
                requestPermission(Manifest.permission.ACCESS_FINE_LOCATION, REQUEST_PERMISSION_FINE_LOCATION);
            }
        }
    }

    @Override
    protected void onStart() {
        super.onStart();

        // start service
        connection = new PigeonNelsonServiceConnection(this);
        Intent intent = new Intent(this, PigeonNelsonService.class);
        bindService(intent, connection, Context.BIND_AUTO_CREATE);

    }

    @Override
    protected void onDestroy() {
        if (mBound) {
            mService.stopBroadcast();
            mService.reset();
            mService.destroy();
        }
        Log.d("MainActivity", "on destroy, stop broadcasting");
        super.onDestroy();
    }

    public void checkSensorsSettings() {
        if (mBound)
            mService.checkSensorsSettings();
    }

    public boolean isEditedServerNew() {
        if (editedServer == null)
            return false;
        else
            return !editedServer.getInEdition();
    }

    public void checkCameraPermission(ScannerFragment scannerFragment) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                scannerFragment.setPermission(false);
                requestPermissions(new String[] {Manifest.permission.CAMERA}, REQUEST_PERMISSION_CAMERA);
            } else {
                scannerFragment.setPermission(true);
            }
        } else {
            scannerFragment.setPermission(true);
        }

    }

    public void setShowVerboseMessages(boolean value) {
        showVerboseMessages = value;
    }

    /** Defines callbacks for service binding, passed to bindService() */
    private class PigeonNelsonServiceConnection implements ServiceConnection {

        private final MainActivity activity;

        public PigeonNelsonServiceConnection(MainActivity activity) {
            this.activity = activity;
        }

        @Override
        public void onServiceConnected(ComponentName className,
                                       IBinder service) {
            // We've bound to LocalService, cast the IBinder and get LocalService instance
            PigeonNelsonService.PigeonNelsonBinder binder = (PigeonNelsonService.PigeonNelsonBinder) service;
            mService = binder.getService();
            mService.setCallbacks(activity);
            mBound = true;
        }

        @Override
        public void onServiceDisconnected(ComponentName arg0) {
            mBound = false;
        }
    };


    @Override
    public boolean onSupportNavigateUp() {
        // first close keyboard
        View view = findViewById(android.R.id.content);
        if (view != null) {
            InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            imm.hideSoftInputFromWindow(view.getWindowToken(), 0);
        }

        // then check if the active fragment is the editor
        if (activeFragmentType == EDIT_SERVER_FRAGMENT) {
            final EditServerFragment editActiveFragment = (EditServerFragment) activeFragment;
            if (editActiveFragment.isModified()) {
                DialogInterface.OnClickListener dialogClickListener = new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        switch (which){
                            case DialogInterface.BUTTON_POSITIVE:
                                editedServer = null;
                                onBackPressed();
                                break;

                            case DialogInterface.BUTTON_NEGATIVE:
                                break;
                        }
                    }
                };

                AlertDialog.Builder builder = new AlertDialog.Builder(this);
                builder.setMessage("Les modifications seront perdues. Voulez-vous revenir à la liste?").setPositiveButton("Oui", dialogClickListener)
                        .setNegativeButton("Annuler", dialogClickListener).show();

            }
            else
                onBackPressed();
        }
        else if (activeFragmentType == LISTEN_BROADCAST_FRAGMENT) {
            stopBroadcast();
            onBackPressed();
        }
        else
            onBackPressed();
        return true;
    }

    private void loadServers() {
        servers = new ArrayList<>();
        debugServers = new ArrayList<>();
        userDefinedServers = new ArrayList<>();

        publicServers = new ArrayList<>();


        createDebugServers();

        // load server descriptions from room
        db.loadAll(userDefinedServers);
        Log.d("LoadServers", "number of user defined servers: " + userDefinedServers.size());

        buildServerList();


        populatePublicServers();



    }

    private void populatePublicServers() {
        if (mBound) {
            publicServers.clear();
            mService.getPublicServers();
        }
        else {
            Log.d("PublicServerCollect", "Service not ready to collect public servers. Retry later.");
            Handler handler = new Handler();
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    populatePublicServers();
                }

            }, 1000);
        }
    }


    private void createDebugServers() {

        // a server to test robustness
        ServerDescription server1 = new ServerDescription("https://http://exemple.fr/");
        server1.setName("Défectueux 1").setDescription("Un serveur injoignable")
                .setPeriod(15).setEncoding("UTF-8").setIsEditable(false);
        debugServers.add(server1);

        // a server to test robustness
        ServerDescription server2 = new ServerDescription("https://raw.githubusercontent.com/Le-Pigeon-Nelson/le-pigeon-nelson/maste//servers/jsontests/broken.json");
        server2.setName("Défectueux 2").setDescription("Un json malformé").
                setEncoding("UTF-8").setPeriod(15).setIsEditable(false);
        debugServers.add(server2);

        // a server to test robustness
        ServerDescription server3 = new ServerDescription("https://raw.githubusercontent.com/Le-Pigeon-Nelson/le-pigeon-nelson/master/servers/jsontests/missing-parts.json");
        server3.setName("Défectueux 3").setDescription("Un json avec des champs manquants")
                .setEncoding("UTF-8").setPeriod(15).setIsEditable(false);
        debugServers.add(server3);

        ServerDescription server4 = new ServerDescription("https://raw.githubusercontent.com/Le-Pigeon-Nelson/le-pigeon-nelson/master/servers/helloworld/message.json");
        server4.setIsEditable(false);
        debugServers.add(server4);

        // add an "bonjour le monde" (fr) server
        ServerDescription server5 = new ServerDescription("https://raw.githubusercontent.com/Le-Pigeon-Nelson/le-pigeon-nelson/master/servers/helloworld/message-fr.json");
        server5.setIsEditable(false);
        debugServers.add(server5);

        // add an "bonjour le monde" (fr) server
        ServerDescription server6 = new ServerDescription("https://raw.githubusercontent.com/Le-Pigeon-Nelson/le-pigeon-nelson/master/servers/helloworld/audiomessage-fr.json");
        server6.setIsEditable(false);
        debugServers.add(server6);

        // add a blabla / "bip" server
        ServerDescription server7 = new ServerDescription("https://lepigeonnelson.jmfavreau.info/blabla-bip.php");
        server7.setName("Blabla bip").setDescription("Un serveur qui raconte du blabla toutes les 15 secondes, mais qui est coupé par un bip")
                .setEncoding("UTF-8").setPeriod(1).setIsEditable(false);
        debugServers.add(server7);

        // a server to test forgetting constraints
        ServerDescription server8 = new ServerDescription("https://raw.githubusercontent.com/Le-Pigeon-Nelson/le-pigeon-nelson/master/servers/prioritytests/5-messages.json");
        server8.setIsEditable(false);
        debugServers.add(server8);

        // a server to test playable constraints
        ServerDescription server9 = new ServerDescription("https://raw.githubusercontent.com/Le-Pigeon-Nelson/le-pigeon-nelson/master/servers/prioritytests/echo.json");
        server9.setIsEditable(false);
        debugServers.add(server9);

        // a server to test changing periods
        ServerDescription server10 = new ServerDescription("https://lepigeonnelson.jmfavreau.info/random-period.php");
        server10.setIsEditable(false);
        debugServers.add(server10);
    }

    private void loadPreferences() {
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(this);
        showDebugServers = preferences.getBoolean("debug_servers", false);

        showVerboseMessages = preferences.getBoolean("verbose", false);
    }

    public boolean getShowVerboseMessages() {
        return showVerboseMessages;
    }
    private void buildServerList() {
        servers.clear();

        if (showDebugServers) {
            servers.addAll(debugServers);
        }
        servers.addAll(userDefinedServers);

        buildServerListInternal();
    }

    private void buildServerListInternal() {
        if(mBound) {
            // refresh descriptions from server
            for (ServerDescription server : this.servers) {
                if (server.isSelfDescribed()) {
                    Log.d("DefaultServers", "load self description for " + server.getUrl());
                    if (mBound)
                        mService.collectServerDescription(server);
                    else {
                        Log.d("DefaultServers", "Service not ready");
                    }
                } else
                    Log.d("DefaultServers", "not self described " + server.getUrl());
            }
        }
        else {
            Log.d("DefaultServers", "Service not available, waiting for it.");
            Handler handler = new Handler();
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    buildServerListInternal();
                }

            }, 1000);
        }

    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.menu_main, menu);

        itemSettings = menu.findItem(R.id.action_settings);

        itemSettings.setVisible(activeFragmentType == SERVER_SELECTION_FRAGMENT);

        reload = menu.findItem(R.id.action_reload);
        reload.setVisible(activeFragmentType == SERVER_SELECTION_FRAGMENT || activeFragmentType == ADD_SERVER_FRAGMENT);

        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        int id = item.getItemId();

        //noinspection SimplifiableIfStatement
        if (id == R.id.action_settings) {
            Navigation.findNavController(this, R.id.nav_host_fragment).navigate(R.id.action_settings);
            return true;
        }
        else if (id == R.id.action_reload) {
            if (isMainFragment()) {
                buildServerList();
            }
            else if (isAddFragment()) {
                populatePublicServers();
            }

        }

        return super.onOptionsItemSelected(item);
    }

    public void getActiveServer() {
        if (mBound)
            mService.getCurrentServer();

    }

    public void setActiveServer(ServerDescription activeServer) {
        if (mBound)
            mService.setCurrentServer(activeServer);
    }

    public void playBroadcast() {
        if (mBound)
            mService.playBroadcast();
    }

    public void stopBroadcast() {
        Log.d("MainActivity", "stop broadcast");
        if (mBound)
            mService.stopBroadcast();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (!isMainFragment()) {
            // back to the main fragment
            onBackPressed();
        }

    }

    @Override
    protected void onResume() {
        super.onResume();
        if (mService != null) {
            mService.askForStatus();
            Log.d("MainActivity", "ask for status");
        }
        else {
            Log.d("MainActivity", "pas de services");
        }
    }


    public void enableDebugServers(boolean showDebugServers) {
        this.showDebugServers = showDebugServers;
        buildServerList();
    }


    @Override  protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        switch (requestCode) {
            case SensorsService.REQUEST_CHECK_SETTINGS:
                if (mBound)
                    mService.checkSensorsSettings();
                break;
            default:
                break;
        }
    }

    public void saveServerDescription(ServerDescription description, String previousURL) {
        Log.d("PigeonNelson", "Save server description " + description.getUrl());
        // save in room this server description
        if (description.isEditable())
            db.add(description);
        if (previousURL != "" && previousURL != description.getUrl()) {
            db.deleteByURL(previousURL);
        }

    }

    public void onEndOfBroadcast() {
        if (!isMainFragment()) {
            try {
                onBackPressed();
                stopBroadcast();
            }
            catch (Exception e) {
                // ignore it: the fragment has been deleted before
            }
        }
    }

    public void onServerError() {
        Toast.makeText(this, R.string.server_access_error, Toast.LENGTH_SHORT).show();
        if (!isMainFragment())
            onBackPressed();
    }

    public void onServerContentError() {
        Toast.makeText(this, R.string.server_content_error, Toast.LENGTH_SHORT).show();
        if (!isMainFragment())
            onBackPressed();
    }

    public void onServerGPSError() {
        Toast.makeText(this, R.string.no_GPS_connection, Toast.LENGTH_SHORT).show();
        if (!isMainFragment())
            onBackPressed();
    }

    public void onServerDescriptionUpdate(ServerDescription description) {
        Log.d("DefaultServers", "new description for " + description.getUrl());

        // check if the server is in the user list
        for (ServerDescription server : servers) {
            if (description.getUrl().equals(server.getUrl())) {
                server.update(description);
                break;
            }
        }

        // check if the server is in the list of public servers
        for (ServerDescription server : publicServers) {
            if (description.getUrl().equals(server.getUrl())) {
                server.update(description);
                break;
            }
        }
    }


    public void onServerListUpdated() {
        buildServerList();
        if (isMainFragment()) {
            ServerSelectionFragment fragment = (ServerSelectionFragment)activeFragment;
            fragment.notifyDataSetChanged();
        }
    }

    @Override
    public void onCurrentServerRequest(ServerDescription description) {
        Log.d("UIHandling", "current server: " + description.getName());
        if (activeFragmentType == LISTEN_BROADCAST_FRAGMENT) {
            ListenBroadcastFragment fragment = (ListenBroadcastFragment) activeFragment;
            fragment.setActiveServer(description.getName());
            fragment.setActiveServerDescription(description.getDescription());
        }
    }

    @Override
    public void onStatusPlaying() {
        Log.d("MainActivity", "onStatusPlaying & " + activeFragmentType);
        if (activeFragmentType == SERVER_SELECTION_FRAGMENT) {
            NavHostFragment.findNavController(activeFragment)
                    .navigate(R.id.action_ListFragment_to_ListenFragment);
            // TODO
        }
    }

    @Override
    public void onStatusNotPlaying() {
        Log.d("MainActivity", "onStatusNotPlaying & " + activeFragmentType);
        if (activeFragmentType == LISTEN_BROADCAST_FRAGMENT) {
            onBackPressed();
        }
    }

    @Override
    public void onSensorSettingsInit(int result) {
        currentSensorSettingResult = result;
        if (isMainFragment()) {
            ServerSelectionFragment fragment = (ServerSelectionFragment)activeFragment;
            fragment.updateGPSMessage();
        }
        switch (result) {
            case SensorsService.RESOLUTION_REQUIRED:
                // Location requirements are not satisfied. Redirect user to system settings for resolution.
                final AlertDialog.Builder builder =  new AlertDialog.Builder(this);
                final String message = "Voulez-vous activer le GPS?";

                builder.setMessage(message);
                builder.setPositiveButton("OK",
                        new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface d, int id) {
                                startActivityForResult(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS), SensorsService.REQUEST_CHECK_SETTINGS);
                                d.dismiss();
                            }
                        });
                builder.setNegativeButton("Cancel",
                        new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface d, int id) {
                                d.cancel();
                            }
                        });
                builder.create().show();
                break;
            case SensorsService.INIT_OK:
                Toast.makeText(this, "Localisation GPS active.", Toast.LENGTH_SHORT).show();
                break;
            case SensorsService.MISSING_PERMISSIONS:
                checkGPSPermission();
                break;
        }
    }

    @Override
    public void onNewPublicServer(String url) {
        Log.d("PublicServerCollect", "Receive a new server: " + url);
        ServerDescription newServer = new ServerDescription(url);
        publicServers.add(newServer);
        if (mBound)
            mService.collectServerDescription(newServer);
    }

    @Override
    public void onInternalValues(ArrayList<Pair<String, String>> values) {
        if (isListenBroadcastFragment()) {
            ListenBroadcastFragment fragment = (ListenBroadcastFragment) activeFragment;
            fragment.setInternalValues(values);
            Log.d("onInternalValues", "Broadcast Fragment");
        }
        else
            Log.d("onInternalValues", "not Broadcast Fragment");
    }

    public int getCurrentSensorSettingResult() {
        return currentSensorSettingResult;
    }


    public void updateServer(ServerDescription description) {
        // if this description is a new one, add it to the list
        String previousURL = "";
        boolean save = false;
        if (!description.getInEdition()) {
            Log.d("UpdateEditedServer", "not from edition");
            userDefinedServers.add(description);
            save = true;
        }
        else {
            if (editedServer != null) {
                Log.d("UpdateEditedServer", "from edition");
                // update the edited server
                previousURL = editedServer.getUrl();
                editedServer.update(description);
                save = true;
            }
        }
        if (save) {

            saveServerDescription(description, previousURL);


            if (description.isSelfDescribed()) {
                Log.d("PigeonNelson", "self descripted server, ask for its description");
                if (mBound)
                    mService.collectServerDescription(description);
            }

            Log.d("PigeonNelson", "build server list");
            // update view
            buildServerList();
        }

        editedServer = null;

    }


    public void setActiveFragment(int active, Fragment fragment) {
        switch (active) {
            case ADD_SERVER_FRAGMENT:
                toolbar.setTitle(R.string.add_server_fragment);
                toolbar.setNavigationIcon(R.drawable.ic_baseline_close_24);
                break;
            case EDIT_SERVER_FRAGMENT:
                toolbar.setTitle(R.string.edit_server_fragment);
                toolbar.setNavigationIcon(R.drawable.ic_baseline_close_24);
                break;
            case SERVER_SELECTION_FRAGMENT:

                toolbar.setTitle(R.string.app_name);
                toolbar.setNavigationIcon(R.drawable.ic_baseline_power_off_24);

                break;
            case LISTEN_BROADCAST_FRAGMENT:
                toolbar.setTitle(R.string.app_name);
                toolbar.setNavigationIcon(R.drawable.ic_baseline_arrow_back_24);


                break;
            case SETTINGS_FRAGMENT:
                toolbar.setTitle(R.string.settings_fragment);
                toolbar.setNavigationIcon(R.drawable.ic_baseline_arrow_back_24);
                break;
        }
        if (itemSettings != null)
            itemSettings.setVisible(active == SERVER_SELECTION_FRAGMENT);
        if (reload != null)
            reload.setVisible(active == SERVER_SELECTION_FRAGMENT || active == ADD_SERVER_FRAGMENT);
        activeFragmentType = active;
        activeFragment = fragment;
    }

    public void setEditNewServer() {
        editedServer = new ServerDescription("");
    }
    public void setEditServer(ServerDescription server) {
        editedServer = server;
        editedServer.setInEdition(true);

    }
    public ServerDescription getEditedServer() {
        return editedServer;
    }


    @Override
    public void onServerListUpdatedFromDatabase() {
        if (mBound)
            mService.updateList();
        else {
            Log.d("AppDatabase", "service not ready, waiting for it");
            new Handler(Looper.getMainLooper()).post(new Runnable() {
                @Override
                public void run() {
                    Handler handler = new Handler();
                    handler.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            mService.updateList();
                        }

                    }, 1000);
                }
            });

        }

    }

    public void deleteSelectedServer() {
        // remove this server from the list of editable servers
        if (editedServer != null) {
            for (Iterator<ServerDescription> iter = userDefinedServers.listIterator(); iter.hasNext(); ) {
                ServerDescription es = iter.next();
                if (es.getUrl().equals(editedServer.getUrl())) {
                    db.delete(es);
                    iter.remove();
                    break;
                }
            }
        }



        // rebuild the server list
        buildServerList();
    }


    public boolean hasServerWithAddress(String address) {
        for(ServerDescription server: servers) {
            if (address.equals(server.getUrl())) {
                return true;
            }
        }
        return false;

    }
}

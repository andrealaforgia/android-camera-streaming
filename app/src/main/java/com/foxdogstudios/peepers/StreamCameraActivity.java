/* Copyright 2013 Foxdog Studios Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.foxdogstudios.peepers;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.SharedPreferences.OnSharedPreferenceChangeListener;
import android.content.pm.PackageManager;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.PowerManager;
import android.os.PowerManager.WakeLock;
import android.preference.PreferenceManager;
import android.view.Menu;
import android.view.MenuItem;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.widget.TextView;

import org.apache.http.conn.util.InetAddressUtils;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Enumeration;

public class StreamCameraActivity extends Activity implements SurfaceHolder.Callback {
    private static final String TAG = StreamCameraActivity.class.getSimpleName();

    private static final String WAKE_LOCK_TAG = "peepers";

    private static final String PREF_CAMERA = "camera";
    private static final int PREF_CAMERA_INDEX_DEF = 0;
    private static final String PREF_FLASH_LIGHT = "flash_light";
    private static final boolean PREF_FLASH_LIGHT_DEF = false;
    private static final String PREF_PORT = "port";
    private static final int PREF_PORT_DEF = 8080;
    private static final String PREF_JPEG_SIZE = "size";
    private static final String PREF_JPEG_QUALITY = "jpeg_quality";
    private static final int PREF_JPEG_QUALITY_DEF = 40;
    // preview sizes will always have at least one element, so this is safe
    private static final int PREF_PREVIEW_SIZE_INDEX_DEF = 0;

    private boolean running = false;
    private boolean previewDisplayCreated = false;
    private SurfaceHolder previewDisplay = null;
    private CameraStreamer cameraStreamer = null;

    private String mIpAddress = "";
    private int cameraIndex = PREF_CAMERA_INDEX_DEF;
    private boolean useFlashLight = PREF_FLASH_LIGHT_DEF;
    private int port = PREF_PORT_DEF;
    private int jpegQuality = PREF_JPEG_QUALITY_DEF;
    private int previeSizeIndex = PREF_PREVIEW_SIZE_INDEX_DEF;
    private TextView ipAddressView = null;
    private SharedPreferences prefs = null;
    private MenuItem settingsMenuItem = null;
    private WakeLock wakeLock = null;

    public StreamCameraActivity() {
        super();
    }

    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main);

        new LoadPreferencesTask().execute();

        previewDisplay = ((SurfaceView) findViewById(R.id.camera)).getHolder();
        previewDisplay.setType(SurfaceHolder.SURFACE_TYPE_PUSH_BUFFERS);
        previewDisplay.addCallback(this);

        mIpAddress = tryGetIpV4Address();
        ipAddressView = (TextView) findViewById(R.id.ip_address);
        updatePrefCacheAndUi();

        final PowerManager powerManager = (PowerManager) getSystemService(POWER_SERVICE);

        wakeLock = powerManager.newWakeLock(PowerManager.SCREEN_DIM_WAKE_LOCK, WAKE_LOCK_TAG);
    }

    @Override
    protected void onResume() {
        super.onResume();
        running = true;
        if (prefs != null) {
            prefs.registerOnSharedPreferenceChangeListener(sharedPreferenceListener);
        }
        updatePrefCacheAndUi();
        tryStartCameraStreamer();
        wakeLock.acquire();
    }

    @Override
    protected void onPause() {
        wakeLock.release();
        super.onPause();
        running = false;
        if (prefs != null) {
            prefs.unregisterOnSharedPreferenceChangeListener(sharedPreferenceListener);
        }
        ensureCameraStreamerStopped();
    }

    @Override
    public void surfaceChanged(final SurfaceHolder holder, final int format,
                               final int width, final int height) {
        // Ignored
    }

    @Override
    public void surfaceCreated(final SurfaceHolder holder) {
        previewDisplayCreated = true;
        tryStartCameraStreamer();
    }

    @Override
    public void surfaceDestroyed(final SurfaceHolder holder) {
        previewDisplayCreated = false;
        ensureCameraStreamerStopped();
    }

    private void tryStartCameraStreamer() {
        if (running && previewDisplayCreated && prefs != null) {
            cameraStreamer = new CameraStreamer(cameraIndex, useFlashLight, port,
                    previeSizeIndex, jpegQuality, previewDisplay);
            cameraStreamer.start();
        }
    }

    private void ensureCameraStreamerStopped() {
        if (cameraStreamer != null) {
            cameraStreamer.stop();
            cameraStreamer = null;
        }
    }

    @Override
    public boolean onCreateOptionsMenu(final Menu menu) {
        super.onCreateOptionsMenu(menu);
        settingsMenuItem = menu.add(R.string.settings);
        settingsMenuItem.setIcon(android.R.drawable.ic_menu_manage);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(final MenuItem item) {
        if (item != settingsMenuItem) {
            return super.onOptionsItemSelected(item);
        }
        startActivity(new Intent(this, PeepersPreferenceActivity.class));
        return true;
    }

    private class LoadPreferencesTask extends AsyncTask<Void, Void, SharedPreferences> {
        private LoadPreferencesTask() {
            super();
        }

        @Override
        protected SharedPreferences doInBackground(final Void... noParams) {
            return PreferenceManager.getDefaultSharedPreferences(
                    StreamCameraActivity.this);
        }

        @Override
        protected void onPostExecute(final SharedPreferences prefs) {
            StreamCameraActivity.this.prefs = prefs;
            prefs.registerOnSharedPreferenceChangeListener(sharedPreferenceListener);
            updatePrefCacheAndUi();
            tryStartCameraStreamer();
        }
    }

    private final OnSharedPreferenceChangeListener sharedPreferenceListener =
            new OnSharedPreferenceChangeListener() {
                @Override
                public void onSharedPreferenceChanged(final SharedPreferences prefs,
                                                      final String key) {
                    updatePrefCacheAndUi();
                }
            };

    private int getPrefInt(final String key, final int defValue) {
        // We can't just call getInt because the preference activity
        // saves everything as a string.
        try {
            return Integer.parseInt(prefs.getString(key, null));
        } catch (NullPointerException e) {
            return defValue;
        } catch (NumberFormatException e) {
            return defValue;
        }
    }

    private void updatePrefCacheAndUi() {
        cameraIndex = getPrefInt(PREF_CAMERA, PREF_CAMERA_INDEX_DEF);
        if (hasFlashLight()) {
            if (prefs != null) {
                useFlashLight = prefs.getBoolean(PREF_FLASH_LIGHT, PREF_FLASH_LIGHT_DEF);
            } else {
                useFlashLight = PREF_FLASH_LIGHT_DEF;
            }
        } else {
            useFlashLight = false;
        }

        // This validation should really be in the preferences activity.
        port = getPrefInt(PREF_PORT, PREF_PORT_DEF);
        // The port must be in the range [1024 65535]
        if (port < 1024) {
            port = 1024;
        } else if (port > 65535) {
            port = 65535;
        }

        previeSizeIndex = getPrefInt(PREF_JPEG_SIZE, PREF_PREVIEW_SIZE_INDEX_DEF);
        jpegQuality = getPrefInt(PREF_JPEG_QUALITY, PREF_JPEG_QUALITY_DEF);
        // The JPEG quality must be in the range [0 100]
        if (jpegQuality < 0) {
            jpegQuality = 0;
        } else if (jpegQuality > 100) {
            jpegQuality = 100;
        }
        ipAddressView.setText("http://" + mIpAddress + ":" + port + "/");
    }

    private boolean hasFlashLight() {
        return getPackageManager().hasSystemFeature(PackageManager.FEATURE_CAMERA_FLASH);
    }

    private static String tryGetIpV4Address() {
        try {
            final Enumeration<NetworkInterface> en =
                    NetworkInterface.getNetworkInterfaces();
            while (en.hasMoreElements()) {
                final NetworkInterface intf = en.nextElement();
                final Enumeration<InetAddress> enumIpAddr =
                        intf.getInetAddresses();
                while (enumIpAddr.hasMoreElements()) {
                    final InetAddress inetAddress = enumIpAddr.nextElement();
                    if (!inetAddress.isLoopbackAddress()) {
                        final String addr = inetAddress.getHostAddress().toUpperCase();
                        if (InetAddressUtils.isIPv4Address(addr)) {
                            return addr;
                        }
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }
}


/*
 * Copyright (C) 2011 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.rex.proxy.wsl;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.VpnService;
import android.os.Bundle;
import android.util.Log;
import android.widget.TextView;

public class WslVpnClient extends Activity {

    private static final String TAG = WslVpnClient.class.getSimpleName();

    public interface Prefs {
        String NAME = "connection";
        String SOCKS_ADDRESS = "socks.address";
        String SOCKS_PORT = "socks.port";
        String SOCKS_USER = "socks.user";
        String SOCKS_PASSWORD = "socks.password";
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.form);

        final SharedPreferences prefs = getSharedPreferences(Prefs.NAME, MODE_PRIVATE);

        final TextView textSocksAddress = findViewById(R.id.socks_address);
        final TextView textSocksPort = findViewById(R.id.socks_port);
        final TextView textSocksUser = findViewById(R.id.socks_user);
        final TextView textSocksPassword = findViewById(R.id.socks_password);

        textSocksAddress.setText(prefs.getString(Prefs.SOCKS_ADDRESS, ""));
        textSocksPort.setText(String.valueOf(prefs.getInt(Prefs.SOCKS_PORT, 1080)));
        textSocksUser.setText(prefs.getString(Prefs.SOCKS_USER, ""));
        textSocksPassword.setText(prefs.getString(Prefs.SOCKS_PASSWORD, ""));

        findViewById(R.id.connect).setOnClickListener(v -> {
            int socksPortNum = 1080;
            try {
                socksPortNum = Integer.parseInt(textSocksPort.getText().toString());
            } catch (NumberFormatException e) {
                Log.w(TAG, "Failed to parse port\n", e);
            }
            prefs.edit()
                    .putString(Prefs.SOCKS_ADDRESS, textSocksAddress.getText().toString())
                    .putInt(Prefs.SOCKS_PORT, socksPortNum)
                    .putString(Prefs.SOCKS_USER, textSocksUser.getText().toString())
                    .putString(Prefs.SOCKS_PASSWORD, textSocksPassword.getText().toString())
                    .apply();
            Intent intent = VpnService.prepare(WslVpnClient.this);
            if (intent != null) {
                // Not prepare, will start activity to prepare (request for permission)
                startActivityForResult(intent, 0);
            } else {
                // Already prepared, start it directly
                onActivityResult(0, RESULT_OK, null);
            }
        });
        findViewById(R.id.disconnect).setOnClickListener(v -> {
            startService(getServiceIntent().setAction(WslVpnService.ACTION_DISCONNECT));
        });
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        if (result == RESULT_OK) {
            startService(getServiceIntent().setAction(WslVpnService.ACTION_CONNECT));
        }
    }

    private Intent getServiceIntent() {
        return new Intent(this, WslVpnService.class);
    }
}

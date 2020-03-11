package com.rex.proxy.wsl;

import android.content.Intent;
import android.content.SharedPreferences;
import android.net.VpnService;
import android.os.Bundle;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = MainActivity.class.getSimpleName();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.d(TAG, "onCreate");

        setContentView(R.layout.activity_main);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        final SharedPreferences prefs = getSharedPreferences(WslPrefs.NAME, MODE_PRIVATE);

        final TextView textSocksAddress = findViewById(R.id.socks_address);
        final TextView textSocksPort = findViewById(R.id.socks_port);
        final TextView textSocksUser = findViewById(R.id.socks_user);
        final TextView textSocksPassword = findViewById(R.id.socks_password);

        textSocksAddress.setText(prefs.getString(WslPrefs.SOCKS_ADDRESS, ""));
        textSocksPort.setText(String.valueOf(prefs.getInt(WslPrefs.SOCKS_PORT, 1080)));
        textSocksUser.setText(prefs.getString(WslPrefs.SOCKS_USER, ""));
        textSocksPassword.setText(prefs.getString(WslPrefs.SOCKS_PASSWORD, ""));

        findViewById(R.id.connect).setOnClickListener(v -> {
            int socksPortNum = 1080;
            try {
                socksPortNum = Integer.parseInt(textSocksPort.getText().toString());
            } catch (NumberFormatException e) {
                Log.w(TAG, "Failed to parse port\n", e);
            }
            prefs.edit()
                    .putString(WslPrefs.SOCKS_ADDRESS, textSocksAddress.getText().toString())
                    .putInt(WslPrefs.SOCKS_PORT, socksPortNum)
                    .putString(WslPrefs.SOCKS_USER, textSocksUser.getText().toString())
                    .putString(WslPrefs.SOCKS_PASSWORD, textSocksPassword.getText().toString())
                    .apply();
            Intent intent = VpnService.prepare(getApplicationContext());
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
    protected void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "onDestroy");
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
        case R.id.action_settings:
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        Log.d(TAG, "onActivityResult request:" + request + " result:" + result + " data:" + data);

        if (result == RESULT_OK) {
            startService(getServiceIntent().setAction(WslVpnService.ACTION_CONNECT));
        }
    }

    private Intent getServiceIntent() {
        return new Intent(this, WslVpnService.class);
    }
}

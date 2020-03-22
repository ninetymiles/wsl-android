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

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.VpnService;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import com.rex.proxy.WslLocal;

import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.ByteBuffer;

import tun2socks.PacketFlow;
import tun2socks.Tun2socks;

public class WslVpnService extends VpnService {

    private static final String TAG = WslVpnService.class.getSimpleName();

    private static final int VPN_MTU = 1500;

    public static final String ACTION_CONNECT = "com.rex.proxy.wsl.START";
    public static final String ACTION_DISCONNECT = "com.rex.proxy.wsl.STOP";

    private PendingIntent mConfigureIntent;
    private InputStreamReaderThread mTunReaderThread;
    private FileDescriptor mTunFileDescriptor;

    private WslLocal mProxy;

    @Override
    public void onCreate() {
        super.onCreate(); // Empty
        Log.d(TAG, "onCreate");
        mConfigureIntent = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "onStartCommand intent=" + ((intent != null) ? intent.getAction() : "null"));
        if (intent != null && ACTION_DISCONNECT.equals(intent.getAction())) {
            stop();
            return START_NOT_STICKY;
        } else {
            start();
            return START_STICKY;
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy(); // Empty
        Log.d(TAG, "onDestroy");
        stop();
    }

    @Override
    public void onRevoke() {
        super.onRevoke(); // Default stopSelf()
        Log.d(TAG, "onRevoke");
        stop();
    }

    private void start() {
        Log.i(TAG, "Start VPN");

        // Extract information from the shared preferences.
        final SharedPreferences prefs = getSharedPreferences(WslPrefs.NAME, MODE_PRIVATE);
        String socks_address = prefs.getString(WslPrefs.SOCKS_ADDRESS, "");
        int socks_port = prefs.getInt(WslPrefs.SOCKS_PORT, 1080);
        String socks_user = prefs.getString(WslPrefs.SOCKS_USER, "");
        byte[] socks_password = prefs.getString(WslPrefs.SOCKS_PASSWORD, "").getBytes();

        WslLocal.Configuration conf = new WslLocal.Configuration(0);
        conf.callback = new WslLocal.SocketCallback() {
            @Override
            public void onConnect(Socket socket) {
                if (!protect(socket)) {
                    Log.w(TAG, "Cannot protect the socket:" + socket);
                }
            }
        };
        mProxy = new WslLocal()
                .config(conf)
                .start();
        socks_address = "127.0.0.1";
        socks_port = mProxy.port();

        // LinkLocal address 169.254.1.0 - 169.254.254.255
        // Tethering address 192.168.43.0/24
        VpnService.Builder builder = new VpnService.Builder();
        builder.setSession(getString(R.string.app_name));
        builder.setConfigureIntent(mConfigureIntent);
        builder.setMtu(VPN_MTU);

        builder.addAddress("192.168.43.1", 24);
        builder.addRoute("0.0.0.0", 0);
        builder.addDnsServer("8.8.8.8");
        builder.addDnsServer("8.8.4.4");

        // Convert 192.168.43.1 to IPv6 format by https://dnschecker.org/ipv4-to-ipv6.php
        //builder.addAddress("::ffff:c0a8:2b01", 120);
        //builder.addRoute("::", 0);
        //builder.addDnsServer("2001:4860:4860::8888");
        //builder.addDnsServer("2001:4860:4860::8844");

        // ifconfig
        // tun0     Link encap:UNSPEC
        //          inet addr:192.168.43.1  P-t-P:192.168.43.1  Mask:255.255.255.0
        //          UP POINTOPOINT RUNNING  MTU:1500  Metric:1
        //          RX packets:143759 errors:0 dropped:0 overruns:0 frame:0
        //          TX packets:179022 errors:0 dropped:111128 overruns:0 carrier:0
        //          collisions:0 txqueuelen:500
        //          RX bytes:6104584 TX bytes:7874928

        // netstat -nr
        // Kernel IP routing table
        // Destination     Gateway         Genmask         Flags   MSS Window  irtt Iface
        // 192.168.43.0    0.0.0.0         255.255.255.0   U         0 0          0 tun0
        // 192.168.200.0   0.0.0.0         255.255.255.0   U         0 0          0 radio0
        // 192.168.232.0   0.0.0.0         255.255.248.0   U         0 0          0 wlan0

        final ParcelFileDescriptor pfd = builder.establish();
        if (pfd == null) {
            Log.w(TAG, "Failed to establish VPN");
            return;
        }
        startForeground();
        Log.i(TAG, "Start interface: " + pfd);
        mTunFileDescriptor = pfd.getFileDescriptor();

        // FIXME: How to stop the Tun2socks from writing the PacketFlow, let the OutputStreamPacketFlow can be release correctly after TUN interface closed
        Tun2socks.startSocks(new OutputStreamPacketFlow(new FileOutputStream(mTunFileDescriptor)), socks_address, socks_port);
        mTunReaderThread = new InputStreamReaderThread(new FileInputStream(mTunFileDescriptor));
        mTunReaderThread.setName("TunReader");
        mTunReaderThread.start();
        Log.v(TAG, "Start-");
    }

    private void stop() {
        Log.i(TAG, "Stop");
        if (mTunReaderThread != null) {
            mTunReaderThread.shutdown();
            mTunReaderThread = null;
        }
        stopForeground(true);
        Log.v(TAG, "Stop-");
    }

    private void startForeground() {
        final String NOTIFY_CHANNEL_ID = "WslVpn";
        final int NOTIFY_ID = 100;

        NotificationManager notifyMgr = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(NOTIFY_CHANNEL_ID,
                    "General notifications",
                    NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("General notification category");
            notifyMgr.createNotificationChannel(channel);
        }

        Notification notify = new NotificationCompat.Builder(this, NOTIFY_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_vpn)
                .setContentText(getString(R.string.connected))
                .setContentIntent(mConfigureIntent)
                .build();

        startForeground(NOTIFY_ID, notify);
    }

    // Write socks received data back to fd for TUN interface
    private class OutputStreamPacketFlow implements PacketFlow {
        private OutputStream mOutput;
        public OutputStreamPacketFlow(OutputStream out) {
            mOutput = out;
        }
        @Override
        public void writePacket(byte[] bytes) {
            try {
                Log.v(TAG, "Output size=" + bytes.length);
                mOutput.write(bytes);
            } catch (IOException ex) {
                Log.w(TAG, "Failed to write TUN " + ex.getMessage());
            }
        }
    }

    // Read from TUN interface fd and send to socks
    private class InputStreamReaderThread extends Thread {
        private InputStream mInput;
        public InputStreamReaderThread(InputStream input) {
            mInput = input;
        }
        @Override
        public void run() {
            Log.i(TAG, "TunReader+");
            try {
                // Larger then MTU should be enough
                ByteBuffer buffer = ByteBuffer.allocate(2048);
                while (!isInterrupted()) {
                    int size = mInput.read(buffer.array());
                    Log.v(TAG, "Input size=" + size);
                    if (size < 0) { // EOS
                        Log.d(TAG, "Input EOS");
                        break;
                    }
                    Tun2socks.inputPacket(ByteBuffer.wrap(buffer.array(), 0, size).array());
                }
            } catch (IOException ex) {
                Log.w(TAG, "Failed to read TUN " + ex.getMessage());
            }
            Log.i(TAG, "TunReader-");
        }
        public void shutdown() {
            Log.d(TAG, "TunReader shutdown");
            try {
                mInput.close();
            } catch (IOException ex) {
                Log.w(TAG, "Failed to close", ex);
            }
            try {
                interrupt();
                join();
            } catch (InterruptedException ex) {
                Log.w(TAG, "Failed to join", ex);
            }
        }
    }
}

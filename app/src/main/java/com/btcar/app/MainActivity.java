package com.btcar.app;

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public class MainActivity extends AppCompatActivity {

    private static final UUID SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");

    private BluetoothAdapter bluetoothAdapter;
    private BluetoothSocket btSocket;
    private OutputStream outputStream;
    private boolean isConnected = false;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private Button btnConnect, btnF, btnB, btnL, btnR, btnS;
    private TextView tvStatus;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();

        btnConnect = findViewById(R.id.btnConnect);
        btnF = findViewById(R.id.btnF);
        btnB = findViewById(R.id.btnB);
        btnL = findViewById(R.id.btnL);
        btnR = findViewById(R.id.btnR);
        btnS = findViewById(R.id.btnS);
        tvStatus = findViewById(R.id.tvStatus);

        btnConnect.setOnClickListener(v -> {
            if (isConnected) disconnect();
            else showDeviceList();
        });

        bind(btnF, "F");
        bind(btnB, "B");
        bind(btnL, "L");
        bind(btnR, "R");
        btnS.setOnClickListener(v -> send("S"));

        requestPerms();
    }

    @SuppressLint("ClickableViewAccessibility")
    private void bind(Button btn, String cmd) {
        btn.setOnTouchListener((v, e) -> {
            if (!isConnected) return false;
            if (e.getAction() == MotionEvent.ACTION_DOWN) {
                send(cmd);
                v.setPressed(true);
            } else if (e.getAction() == MotionEvent.ACTION_UP || e.getAction() == MotionEvent.ACTION_CANCEL) {
                send("S");
                v.setPressed(false);
            }
            return true;
        });
    }

    private void requestPerms() {
        List<String> p = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 31) {
            p.add(Manifest.permission.BLUETOOTH_CONNECT);
            p.add(Manifest.permission.BLUETOOTH_SCAN);
        } else {
            p.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }
        ActivityCompat.requestPermissions(this, p.toArray(new String[0]), 1);
    }

    @SuppressLint("MissingPermission")
    private void showDeviceList() {
        if (bluetoothAdapter == null) {
            Toast.makeText(this, "不支持蓝牙", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!bluetoothAdapter.isEnabled()) {
            Toast.makeText(this, "请开启蓝牙", Toast.LENGTH_SHORT).show();
            return;
        }
        Set<BluetoothDevice> paired = bluetoothAdapter.getBondedDevices();
        if (paired.isEmpty()) {
            Toast.makeText(this, "无配对设备，请先在系统中配对", Toast.LENGTH_LONG).show();
            return;
        }
        List<BluetoothDevice> list = new ArrayList<>(paired);
        String[] names = new String[list.size()];
        for (int i = 0; i < list.size(); i++) {
            names[i] = list.get(i).getName() + " (" + list.get(i).getAddress() + ")";
        }
        new AlertDialog.Builder(this)
                .setTitle("选择设备")
                .setItems(names, (d, w) -> connect(list.get(w)))
                .setNegativeButton("取消", null)
                .show();
    }

    @SuppressLint("MissingPermission")
    private void connect(BluetoothDevice device) {
        tvStatus.setText("连接中...");
        new Thread(() -> {
            try {
                btSocket = device.createRfcommSocketToServiceRecord(SPP_UUID);
                bluetoothAdapter.cancelDiscovery();
                btSocket.connect();
                outputStream = btSocket.getOutputStream();
                isConnected = true;
                mainHandler.post(() -> {
                    tvStatus.setText("已连接: " + device.getName());
                    btnConnect.setText("断开");
                });
            } catch (IOException e) {
                mainHandler.post(() -> tvStatus.setText("连接失败: " + e.getMessage()));
                close();
            }
        }).start();
    }

    private void disconnect() {
        close();
        isConnected = false;
        tvStatus.setText("未连接");
        btnConnect.setText("连接蓝牙");
    }

    private void close() {
        try { if (outputStream != null) outputStream.close(); } catch (IOException ignored) {}
        try { if (btSocket != null) btSocket.close(); } catch (IOException ignored) {}
        outputStream = null;
        btSocket = null;
    }

    private void send(String cmd) {
        if (!isConnected || outputStream == null) return;
        new Thread(() -> {
            try {
                outputStream.write(cmd.getBytes());
                outputStream.flush();
            } catch (IOException e) {
                mainHandler.post(this::disconnect);
            }
        }).start();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        close();
    }
}

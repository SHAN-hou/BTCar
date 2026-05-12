package com.btcar.app;

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

public class MainActivity extends AppCompatActivity {

    private static final UUID SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");

    private BluetoothAdapter bluetoothAdapter;
    private BluetoothSocket btSocket;
    private OutputStream outputStream;
    private InputStream inputStream;
    private boolean isConnected = false;
    private volatile boolean receiving = false;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());

    private Button btnConnect, btnF, btnB, btnL, btnR, btnS, btnSend, btnClearLog;
    private Button qF, qB, qL, qR, qS;
    private TextView tvStatus, tvCurrentCmd, tvLog;
    private TextView tabControl, tabDebug;
    private View statusDot;
    private View pageControl, pageDebug;
    private ScrollView logScroll;
    private EditText etCmd;
    private final StringBuilder logBuffer = new StringBuilder();
    private int logLines = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();

        // Bind views
        btnConnect = findViewById(R.id.btnConnect);
        btnF = findViewById(R.id.btnF);
        btnB = findViewById(R.id.btnB);
        btnL = findViewById(R.id.btnL);
        btnR = findViewById(R.id.btnR);
        btnS = findViewById(R.id.btnS);
        tvStatus = findViewById(R.id.tvStatus);
        tvCurrentCmd = findViewById(R.id.tvCurrentCmd);
        tvLog = findViewById(R.id.tvLog);
        statusDot = findViewById(R.id.statusDot);
        tabControl = findViewById(R.id.tabControl);
        tabDebug = findViewById(R.id.tabDebug);
        pageControl = findViewById(R.id.pageControl);
        pageDebug = findViewById(R.id.pageDebug);
        logScroll = findViewById(R.id.logScroll);
        etCmd = findViewById(R.id.etCmd);
        btnSend = findViewById(R.id.btnSend);
        btnClearLog = findViewById(R.id.btnClearLog);
        qF = findViewById(R.id.qF);
        qB = findViewById(R.id.qB);
        qL = findViewById(R.id.qL);
        qR = findViewById(R.id.qR);
        qS = findViewById(R.id.qS);

        // Connect btn
        btnConnect.setOnClickListener(v -> {
            if (isConnected) disconnect();
            else showDeviceList();
        });

        // Tabs
        tabControl.setOnClickListener(v -> switchTab(true));
        tabDebug.setOnClickListener(v -> switchTab(false));
        switchTab(true);

        // Direction buttons (single-click toggle)
        btnF.setOnClickListener(v -> { send("F"); tvCurrentCmd.setText("▲ 前进中"); });
        btnB.setOnClickListener(v -> { send("B"); tvCurrentCmd.setText("▼ 后退中"); });
        btnL.setOnClickListener(v -> { send("L"); tvCurrentCmd.setText("◀ 左转中"); });
        btnR.setOnClickListener(v -> { send("R"); tvCurrentCmd.setText("▶ 右转中"); });
        btnS.setOnClickListener(v -> { send("S"); tvCurrentCmd.setText("待 命"); });

        // Debug page quick commands
        qF.setOnClickListener(v -> send("F"));
        qB.setOnClickListener(v -> send("B"));
        qL.setOnClickListener(v -> send("L"));
        qR.setOnClickListener(v -> send("R"));
        qS.setOnClickListener(v -> send("S"));

        // Send custom command
        btnSend.setOnClickListener(v -> {
            String cmd = etCmd.getText().toString();
            if (!cmd.isEmpty()) {
                send(cmd);
                etCmd.setText("");
            }
        });

        // Clear log
        btnClearLog.setOnClickListener(v -> {
            logBuffer.setLength(0);
            logLines = 0;
            tvLog.setText("(已清空)");
        });

        requestPerms();
    }

    private void switchTab(boolean control) {
        tabControl.setSelected(control);
        tabDebug.setSelected(!control);
        pageControl.setVisibility(control ? View.VISIBLE : View.GONE);
        pageDebug.setVisibility(control ? View.GONE : View.VISIBLE);
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
        appendLog("→ 连接 " + device.getName() + " ...");
        new Thread(() -> {
            try {
                btSocket = device.createRfcommSocketToServiceRecord(SPP_UUID);
                bluetoothAdapter.cancelDiscovery();
                btSocket.connect();
                outputStream = btSocket.getOutputStream();
                inputStream = btSocket.getInputStream();
                isConnected = true;
                startReceiving();
                mainHandler.post(() -> {
                    tvStatus.setText("已连接: " + device.getName());
                    statusDot.setBackgroundResource(R.drawable.status_dot_on);
                    btnConnect.setText("⛓ 断开连接");
                    appendLog("✓ 已连接");
                });
            } catch (IOException e) {
                mainHandler.post(() -> {
                    tvStatus.setText("连接失败");
                    appendLog("✗ 连接失败: " + e.getMessage());
                });
                close();
            }
        }).start();
    }

    private void disconnect() {
        receiving = false;
        close();
        isConnected = false;
        tvStatus.setText("未连接");
        statusDot.setBackgroundResource(R.drawable.status_dot);
        btnConnect.setText("🔗 连接蓝牙");
        tvCurrentCmd.setText("待 命");
        appendLog("已断开");
    }

    private void close() {
        try { if (outputStream != null) outputStream.close(); } catch (IOException ignored) {}
        try { if (inputStream != null) inputStream.close(); } catch (IOException ignored) {}
        try { if (btSocket != null) btSocket.close(); } catch (IOException ignored) {}
        outputStream = null;
        inputStream = null;
        btSocket = null;
    }

    private void send(String cmd) {
        if (!isConnected || outputStream == null) {
            Toast.makeText(this, "未连接蓝牙", Toast.LENGTH_SHORT).show();
            return;
        }
        new Thread(() -> {
            try {
                outputStream.write(cmd.getBytes());
                outputStream.flush();
                mainHandler.post(() -> appendLog("→ 发送: " + cmd));
            } catch (IOException e) {
                mainHandler.post(() -> {
                    appendLog("✗ 发送失败: " + e.getMessage());
                    disconnect();
                });
            }
        }).start();
    }

    private void startReceiving() {
        receiving = true;
        new Thread(() -> {
            byte[] buffer = new byte[1024];
            StringBuilder line = new StringBuilder();
            while (receiving && isConnected) {
                try {
                    if (inputStream == null) break;
                    if (inputStream.available() > 0) {
                        int len = inputStream.read(buffer);
                        if (len > 0) {
                            String data = new String(buffer, 0, len);
                            for (char c : data.toCharArray()) {
                                if (c == '\n') {
                                    String l = line.toString().trim();
                                    if (!l.isEmpty()) {
                                        mainHandler.post(() -> appendLog("← " + l));
                                    }
                                    line.setLength(0);
                                } else if (c != '\r') {
                                    line.append(c);
                                }
                            }
                        }
                    }
                    Thread.sleep(30);
                } catch (Exception e) {
                    if (receiving) {
                        mainHandler.post(() -> appendLog("接收异常: " + e.getMessage()));
                    }
                    break;
                }
            }
        }).start();
    }

    private void appendLog(String msg) {
        String time = sdf.format(new Date());
        if (logBuffer.length() > 0) logBuffer.append('\n');
        logBuffer.append(time).append(' ').append(msg);
        logLines++;
        // Keep last 200 lines to avoid memory blow-up
        if (logLines > 200) {
            int idx = logBuffer.indexOf("\n");
            if (idx > 0) logBuffer.delete(0, idx + 1);
            logLines = 200;
        }
        tvLog.setText(logBuffer.toString());
        logScroll.post(() -> logScroll.fullScroll(View.FOCUS_DOWN));
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        receiving = false;
        close();
    }
}

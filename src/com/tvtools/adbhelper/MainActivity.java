package com.tvtools.adbhelper;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;

/**
 * ADB 助手（TV 版）
 *
 * 功能：
 *  1. 检测本机 adbd(127.0.0.1:5555) 是否在监听，并通过 ADB CNXN 握手
 *     触发系统的"允许 USB 调试"授权弹窗
 *  2. 以多种方式拉起开发者选项设置页
 *  3. 扫描整机所有 Activity，列出名称含 factory/develop/adb/engineering/hidden
 *     的隐藏界面（工厂菜单、调试页等）供一键打开
 *
 * 说明：普通应用无 WRITE_SECURE_SETTINGS 权限，无法直接修改 adb_enabled，
 * 本应用只做"引导 + 触发"，ADB 总开关仍需在工厂菜单/开发者选项中手动打开。
 */
public class MainActivity extends Activity {

    private TextView status;
    private TextView info;
    private final Handler main = new Handler(Looper.getMainLooper());

    private static final int ADB_PORT = 5555;
    private static final int CMD_CNXN = 0x4E584E43;   // 'CNXN' 小端读取值
    private static final int CMD_AUTH = 0x48545541;   // 'AUTH' 小端读取值
    private static final String[] KEYWORDS = {
            "factory", "develop", "adb", "engineering", "hidden"
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(24);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("ADB 助手");
        title.setTextSize(26);
        title.setPadding(0, 0, 0, dp(8));
        root.addView(title);

        info = new TextView(this);
        info.setTextSize(14);
        info.setPadding(0, 0, 0, dp(12));
        root.addView(info);
        refreshInfo();

        status = new TextView(this);
        status.setTextSize(16);
        status.setPadding(0, 0, 0, dp(12));
        root.addView(status);
        setStatus("就绪。请先用遥控器确认本机已开启 ADB（多数定制系统需在工厂菜单打开总开关）。");

        root.addView(makeButton("① 触发 ADB 授权弹窗（本机 5555）", new View.OnClickListener() {
            @Override public void onClick(View v) { probeAdb(); }
        }));

        root.addView(makeButton("② 打开开发者选项", new View.OnClickListener() {
            @Override public void onClick(View v) { openDeveloperSettings(); }
        }));

        root.addView(makeButton("③ 扫描隐藏界面（工厂菜单/调试页）", new View.OnClickListener() {
            @Override public void onClick(View v) { scanHiddenActivities(); }
        }));

        TextView note = new TextView(this);
        note.setTextSize(12);
        note.setPadding(0, dp(16), 0, 0);
        note.setText("原理：本应用向本机 5555 端口发起标准 ADB 握手（CNXN），"
                + "若 adbd 在监听但未授权，系统会弹出\"允许 USB 调试\"确认框；"
                + "若被拒绝连接，请先在工厂菜单开启 ADB 总开关。");
        root.addView(note);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        setContentView(scroll);
        root.getChildAt(2).requestFocus();
    }

    private Button makeButton(String text, View.OnClickListener listener) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(20);
        b.setPadding(0, dp(14), 0, dp(14));
        b.setOnClickListener(listener);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, 0, dp(12));
        b.setLayoutParams(lp);
        return b;
    }

    private void refreshInfo() {
        StringBuilder sb = new StringBuilder();
        sb.append("机型: ").append(Build.MANUFACTURER).append(" ").append(Build.MODEL).append("\n");
        sb.append("系统: Android ").append(Build.VERSION.RELEASE)
          .append(" (API ").append(Build.VERSION.SDK_INT).append(")\n");
        String ip = getLocalIp();
        sb.append("本机 IP: ").append(ip == null ? "未知（未联网？）" : ip);
        info.setText(sb.toString());
    }

    private void setStatus(String s) {
        status.setText("状态: " + s);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }

    /* ---------------- ① ADB 授权触发 ---------------- */

    private void probeAdb() {
        setStatus("正在连接 127.0.0.1:" + ADB_PORT + " …");
        new Thread(new Runnable() {
            @Override public void run() {
                final String result = doProbe();
                main.post(new Runnable() {
                    @Override public void run() { setStatus(result); }
                });
            }
        }).start();
    }

    private String doProbe() {
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress("127.0.0.1", ADB_PORT), 3000);
        } catch (Exception e) {
            return "连接被拒绝 → adbd 未开启。请用 ③ 扫描隐藏界面，"
                    + "找到工厂菜单（Factory）把 ADB 总开关打开。";
        }
        try {
            socket.setSoTimeout(500);
            OutputStream os = socket.getOutputStream();
            os.write(cnxnFrame());
            os.flush();

            InputStream is = socket.getInputStream();
            byte[] buf = new byte[512];
            int total = 0;
            boolean gotAuth = false;
            long deadline = System.currentTimeMillis() + 15000L;
            while (System.currentTimeMillis() < deadline && total < 24) {
                try {
                    int n = is.read(buf);
                    if (n > 0) {
                        if (total == 0 && n >= 4 && readCmd(buf) == CMD_AUTH) {
                            gotAuth = true;
                        }
                        total += n;
                    }
                } catch (java.net.SocketTimeoutException ignored) {
                }
            }
            if (total == 0) {
                return "已连上端口但 15 秒无响应 → adbd 可能未启用协议，"
                        + "或正等待其他客户端授权。请留意屏幕弹窗。";
            }
            return gotAuth
                    ? "adbd 已响应并要求授权 → 请看电视屏幕的\"允许 USB 调试\"弹窗，选择允许/确定！"
                    : "adbd 已有响应（可能已授权过）→ 可以直接用 adb connect " + getLocalIp() + ":5555 连接。";
        } catch (Exception e) {
            return "通信异常: " + e.getClass().getSimpleName();
        } finally {
            try { socket.close(); } catch (Exception ignored) { }
        }
    }

    /** 标准 ADB CNXN 帧：cmd CNXN, version 0x01000000, maxdata 4096, data "host::\0" */
    private byte[] cnxnFrame() {
        byte[] payload = new byte[]{'h','o','s','t',':',':',0};
        int sum = 0;
        for (byte b : payload) sum += (b & 0xFF);
        ByteBuffer bb = ByteBuffer.allocate(24 + payload.length)
                .order(ByteOrder.LITTLE_ENDIAN);
        bb.putInt(CMD_CNXN);
        bb.putInt(0x01000000);
        bb.putInt(4096);
        bb.putInt(payload.length);
        bb.putInt(sum);
        bb.put(payload);
        return bb.array();
    }

    private int readCmd(byte[] buf) {
        return (buf[0] & 0xFF) | ((buf[1] & 0xFF) << 8)
                | ((buf[2] & 0xFF) << 16) | ((buf[3] & 0xFF) << 24);
    }

    /* ---------------- ② 开发者选项 ---------------- */

    private void openDeveloperSettings() {
        // 依次尝试多种入口，直到某一个成功
        if (tryStart(new Intent("android.settings.APPLICATION_DEVELOPMENT_SETTINGS"))) return;

        String[][] components = {
                {"com.android.settings", "com.android.settings.DevelopmentSettings"},
                {"com.android.settings", "com.android.settings.Settings$DevelopmentSettingsActivity"},
                {"com.android.settings", "com.android.settings.Settings"},
        };
        for (String[] c : components) {
            Intent it = new Intent(Intent.ACTION_MAIN);
            it.setClassName(c[0], c[1]);
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if (tryStart(it)) return;
        }
        toast("所有入口都失败了 → 请用 ③ 扫描隐藏界面，或手动进工厂菜单");
    }

    private boolean tryStart(Intent intent) {
        try {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
            setStatus("已拉起: " + (intent.getComponent() != null
                    ? intent.getComponent().toShortString() : intent.getAction()));
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /* ---------------- ③ 隐藏界面扫描 ---------------- */

    private void scanHiddenActivities() {
        setStatus("扫描中…");
        final PackageManager pm = getPackageManager();
        List<PackageInfo> pkgs;
        try {
            pkgs = pm.getInstalledPackages(PackageManager.GET_ACTIVITIES);
        } catch (Exception e) {
            setStatus("扫描失败: " + e.getClass().getSimpleName());
            return;
        }
        final List<String> labels = new ArrayList<String>();
        final List<String[]> comps = new ArrayList<String[]>();
        for (PackageInfo pi : pkgs) {
            if (pi.activities == null) continue;
            for (ActivityInfo ai : pi.activities) {
                String hay = (ai.name + " " + ai.packageName).toLowerCase();
                boolean hit = false;
                for (String k : KEYWORDS) {
                    if (hay.contains(k)) { hit = true; break; }
                }
                if (!hit) continue;
                String shortName = ai.name;
                int dot = shortName.lastIndexOf('.');
                if (dot >= 0 && dot + 1 < shortName.length()) shortName = shortName.substring(dot + 1);
                labels.add(ai.packageName + " → " + shortName);
                comps.add(new String[]{ai.packageName, ai.name});
            }
        }
        if (labels.isEmpty()) {
            setStatus("扫描完成：未发现名称含 factory/develop/adb/engineering/hidden 的界面。"
                    + "定制系统可能使用了改名后的类，可尝试 ② 打开开发者选项。");
            return;
        }
        // labels 与 comps 保持同一下标对应，不做重排序
        setStatus("发现 " + labels.size() + " 个疑似隐藏界面，选择打开:");
        new AlertDialog.Builder(this)
                .setTitle("隐藏界面（共 " + comps.size() + " 个）")
                .setItems(labels.toArray(new String[0]), new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) {
                        String[] c = comps.get(which);
                        Intent it = new Intent(Intent.ACTION_MAIN);
                        it.setClassName(c[0], c[1]);
                        it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        if (!tryStart(it)) {
                            toast("该界面无法启动（可能需要系统权限）");
                        }
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /* ---------------- 工具 ---------------- */

    private String getLocalIp() {
        try {
            Enumeration<NetworkInterface> nis = NetworkInterface.getNetworkInterfaces();
            while (nis.hasMoreElements()) {
                NetworkInterface ni = nis.nextElement();
                Enumeration<java.net.InetAddress> addrs = ni.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    java.net.InetAddress a = addrs.nextElement();
                    if (!a.isLoopbackAddress() && a.getAddress().length == 4) {
                        return a.getHostAddress();
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}

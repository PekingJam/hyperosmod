package com.hyperosmod.singbox;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.hyperosmod.Keys;
import com.hyperosmod.R;
import com.hyperosmod.data.Prefs;
import com.hyperosmod.ui.MainActivity;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 通知栏常驻：显示实时上下行和当前代理节点。
 *
 * <p>用前台服务而不是普通通知，是因为流量是长连接流式读的——服务被系统回收，数字就会停在
 * 最后一帧。前台服务在 Android 14 上必须声明类型，这里用 dataSync。</p>
 */
public final class PanelStatusService extends Service {

    private static final String CHANNEL_ID = "singbox_panel";
    /**
     * 通知 id 每次起服务都换一个：SystemUI 会按 id 缓存通知的小图标位图，
     * 复用同一个 id 时升级后可能仍显示上一次的图标。
     */
    private final int notifyId = 1000 + (int) (System.nanoTime() % 900L);

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean nodeRefreshing = new AtomicBoolean(false);
    private volatile long lastUp;
    private volatile long lastDown;
    private volatile String nodeLabel = "";
    private volatile long lastNotifyAt;
    private ClashApi api;
    private Thread trafficThread;

    public static void start(Context context) {
        Intent intent = new Intent(context, PanelStatusService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }

    public static void stop(Context context) {
        context.stopService(new Intent(context, PanelStatusService.class));
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
        NotificationManager manager =
                (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) {
            // 先清掉本应用所有旧通知，避免图标缓存残留
            manager.cancelAll();
        }
        startForeground(notifyId, buildNotification());
        running.set(true);
        api = new ClashApi(Prefs.getString(Keys.SINGBOX_API_URL, "http://127.0.0.1:9090"),
                Prefs.getString(Keys.SINGBOX_API_SECRET, ""));

        trafficThread = new Thread(this::loopTraffic, "panel-status-traffic");
        trafficThread.start();

        Thread nodeThread = new Thread(this::loopNode, "panel-status-node");
        nodeThread.start();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running.set(false);
        if (api != null) {
            api.stopTraffic();
        }
        super.onDestroy();
    }

    // ==================================================================================

    /** 流量流：每来一帧记下数值，节流后刷新通知。 */
    private void loopTraffic() {
        while (running.get()) {
            api.streamTraffic((up, down) -> {
                lastUp = up;
                lastDown = down;
                maybeNotify();
            });
            // 流断了（控制接口重启、网络抖动）等一会儿再连，别把 CPU 打满。
            if (running.get()) {
                try {
                    Thread.sleep(3000L);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    /** 当前节点：每 5 秒取一次第一个非 GLOBAL 分组的选中项。 */
    private void loopNode() {
        while (running.get()) {
            if (nodeRefreshing.compareAndSet(false, true)) {
                try {
                    List<ClashApi.Group> groups = api.groups(new java.util.HashMap<>());
                    String label = "";
                    for (ClashApi.Group group : groups) {
                        if ("GLOBAL".equalsIgnoreCase(group.name)) {
                            continue;
                        }
                        if (!group.now.isEmpty()) {
                            label = group.name + " · " + group.now;
                            break;
                        }
                    }
                    nodeLabel = label;
                    maybeNotify();
                } catch (Throwable ignored) {
                    // 控制接口暂时不可达，保留上一次的显示
                } finally {
                    nodeRefreshing.set(false);
                }
            }
            try {
                Thread.sleep(5000L);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /** 两秒最多刷一次，避免每帧都发通知。 */
    private void maybeNotify() {
        long now = System.currentTimeMillis();
        if (now - lastNotifyAt < 2000L) {
            return;
        }
        lastNotifyAt = now;
        NotificationManager manager =
                (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) {
            manager.notify(notifyId, buildNotification());
        }
    }

    private Notification buildNotification() {
        Intent open = new Intent(this, MainActivity.class);
        open.putExtra(TileEntryActivity.EXTRA_TAB, TileEntryActivity.TAB_WEB);
        PendingIntent content = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        String title = nodeLabel.isEmpty() ? getString(R.string.app_name) : nodeLabel;
        String text = getString(R.string.panel_notify_text,
                formatRate(lastUp), formatRate(lastDown));
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notify_globe)
                .setContentTitle(title)
                .setContentText(text)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setContentIntent(content)
                .build();
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        NotificationManager manager =
                (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                getString(R.string.panel_notify_channel), NotificationManager.IMPORTANCE_LOW);
        channel.setShowBadge(false);
        manager.createNotificationChannel(channel);
    }

    private static String formatRate(long bytesPerSecond) {
        if (bytesPerSecond < 1024) {
            return bytesPerSecond + " B/s";
        }
        if (bytesPerSecond < 1024 * 1024) {
            return String.format(Locale.ROOT, "%.0f KB/s", bytesPerSecond / 1024f);
        }
        return String.format(Locale.ROOT, "%.1f MB/s", bytesPerSecond / 1048576f);
    }
}

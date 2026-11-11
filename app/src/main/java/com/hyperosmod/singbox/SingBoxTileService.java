package com.hyperosmod.singbox;

import android.graphics.drawable.Icon;
import android.os.Handler;
import android.os.Looper;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

import com.hyperosmod.R;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * sing-box 快捷磁贴：点一下开/关，长按由系统打开本应用（落在网页面板，可在面板里关掉）。
 *
 * <p>root 调用是阻塞的，所以所有动作都丢到单线程池里跑，再把结果抛回主线程更新磁贴，
 * 避免把 SystemUI 的 Binder 线程堵住。</p>
 */
public final class SingBoxTileService extends TileService {

    /** 静态队列：TileService 实例会被系统反复销毁重建，实例级线程池销毁后再提交会直接抛异常。 */
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    @Override
    public void onStartListening() {
        super.onStartListening();
        refresh();
    }

    @Override
    public void onTileAdded() {
        super.onTileAdded();
        refresh();
    }

    @Override
    public void onClick() {
        super.onClick();
        EXECUTOR.execute(() -> {
            SingBoxController.Status status = SingBoxController.status();
            if (!status.rootGranted) {
                return;
            }
            if (status.running()) {
                SingBoxController.stop();
            } else {
                SingBoxController.start();
            }
            refresh();
        });
    }

    /** 读一次真实状态并同步到磁贴上；不要在 root 调用未完成时先乐观翻转。 */
    private void refresh() {
        EXECUTOR.execute(() -> {
            SingBoxController.Status status = SingBoxController.status();
            main.post(() -> apply(status));
        });
    }

    private void apply(SingBoxController.Status status) {
        Tile tile = getQsTile();
        if (tile == null) {
            return;
        }
        if (!status.rootGranted) {
            tile.setState(Tile.STATE_UNAVAILABLE);
            tile.setLabel(getString(R.string.singbox_tile_no_root));
            tile.setSubtitle(getString(R.string.singbox_state_no_root));
        } else if (status.running()) {
            tile.setState(Tile.STATE_ACTIVE);
            tile.setLabel(getString(R.string.singbox_tile_label));
            tile.setSubtitle(getString(R.string.singbox_tile_running, status.version));
        } else {
            tile.setState(Tile.STATE_INACTIVE);
            tile.setLabel(getString(R.string.singbox_tile_label));
            tile.setSubtitle(getString(R.string.singbox_tile_stopped, status.version));
        }
        tile.setIcon(Icon.createWithResource(this, R.drawable.ic_singbox_tile));
        tile.updateTile();
    }

    @Override
    public void onDestroy() {
        main.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}

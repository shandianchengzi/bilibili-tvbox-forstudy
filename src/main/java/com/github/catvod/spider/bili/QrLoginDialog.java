package com.github.catvod.spider.bili;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.Window;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.github.catvod.spider.Init;

import java.util.concurrent.atomic.AtomicLong;

/** Optional native QR view. Authentication and the cover fallback live in the spider. */
public final class QrLoginDialog {
    private final AtomicLong generation = new AtomicLong();
    private AlertDialog dialog;
    private ImageView qrImage;
    private TextView statusView;
    private Runnable refreshAction;
    private Runnable cancelAction;

    public void show(Bitmap image, String status, Runnable refresh, Runnable cancel) {
        long token = generation.incrementAndGet();
        Init.run(() -> {
            if (generation.get() != token) return;
            Activity owner = Init.getActivity();
            if (owner == null) return;
            closeView();
            refreshAction = refresh;
            cancelAction = cancel;
            try {
                DisplayMetrics metrics = owner.getResources().getDisplayMetrics();
                int available = Math.min(metrics.widthPixels - dp(metrics, 64), metrics.heightPixels - dp(metrics, 200));
                int edge = Math.max(dp(metrics, 128), Math.min(dp(metrics, 320), available));
                LinearLayout content = new LinearLayout(owner);
                content.setOrientation(LinearLayout.VERTICAL);
                content.setGravity(Gravity.CENTER_HORIZONTAL);
                content.setPadding(dp(metrics, 20), dp(metrics, 12), dp(metrics, 20), dp(metrics, 12));
                content.setBackgroundColor(Color.WHITE);

                qrImage = new ImageView(owner);
                qrImage.setScaleType(ImageView.ScaleType.FIT_CENTER);
                qrImage.setBackgroundColor(Color.WHITE);
                qrImage.setContentDescription("哔哩哔哩登录二维码");
                qrImage.setImageBitmap(image);
                content.addView(qrImage, new LinearLayout.LayoutParams(edge, edge));

                statusView = new TextView(owner);
                statusView.setTextColor(Color.rgb(32, 32, 32));
                statusView.setTextSize(16);
                statusView.setGravity(Gravity.CENTER);
                statusView.setPadding(0, dp(metrics, 12), 0, 0);
                statusView.setText(status == null ? "请使用哔哩哔哩 APP 扫一扫，并在手机确认登录" : status);
                content.addView(statusView, new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

                AlertDialog created = new AlertDialog.Builder(owner)
                        .setTitle("Bilibili 扫码登录")
                        .setView(content)
                        .setPositiveButton("刷新二维码", null)
                        .setNegativeButton("关闭", null)
                        .create();
                dialog = created;
                created.setCanceledOnTouchOutside(false);
                created.setOnCancelListener(ignored -> cancelByUser(token));
                created.setOnDismissListener(ignored -> {
                    if (dialog == created) clearViewReferences();
                });
                created.show();
                created.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(ignored -> {
                    if (generation.get() == token && refreshAction != null) refreshAction.run();
                });
                created.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener(ignored -> cancelByUser(token));
                Window window = created.getWindow();
                if (window != null) window.setLayout(Math.min(metrics.widthPixels - dp(metrics, 32), dp(metrics, 640)),
                        LinearLayout.LayoutParams.WRAP_CONTENT);
            } catch (RuntimeException ignored) {
                // Activity/window incompatibility must not prevent the cover-based login.
                closeView();
            }
        });
    }

    public void update(Bitmap image, String status) {
        long token = generation.get();
        Init.run(() -> {
            if (generation.get() != token) return;
            if (image != null && qrImage != null) qrImage.setImageBitmap(image);
            if (status != null && statusView != null) statusView.setText(status);
        });
    }

    public void updateStatus(String status) {
        update(null, status);
    }

    /** Programmatic completion/replacement does not cancel the authentication worker. */
    public void dismiss() {
        long token = generation.incrementAndGet();
        Init.run(() -> {
            if (generation.get() == token) closeView();
        });
    }

    private void cancelByUser(long token) {
        if (generation.get() != token) return;
        generation.incrementAndGet();
        Runnable cancel = cancelAction;
        closeView();
        if (cancel != null) cancel.run();
    }

    private void closeView() {
        AlertDialog old = dialog;
        clearViewReferences();
        if (old != null) {
            old.setOnCancelListener(null);
            old.setOnDismissListener(null);
            try { old.dismiss(); } catch (RuntimeException ignored) { }
        }
    }

    private void clearViewReferences() {
        dialog = null;
        qrImage = null;
        statusView = null;
        refreshAction = null;
        cancelAction = null;
    }

    private static int dp(DisplayMetrics metrics, int value) {
        return Math.round(metrics.density * value);
    }
}

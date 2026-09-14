package com.github.catvod.spider.bili;

import java.io.IOException;

/** Bounded QR polling that survives temporary network failures. */
public final class QrPoller {
    private static final long POLL_DELAY_MS = 1500L;
    private static final long LIMITED_DELAY_MS = 5000L;

    public interface Poll { int poll() throws Exception; }
    public interface Listener {
        boolean active();
        void update(String message);
    }
    public enum Result { SUCCESS, EXPIRED, CANCELLED, FAILED }

    interface Clock { long nowMillis(); }
    interface Sleeper { void sleep(long milliseconds) throws InterruptedException; }

    private QrPoller() {}

    /** Must run on a worker thread. No keys or credentials are included in status messages. */
    public static Result await(Poll poll, Listener listener, long timeoutMs) {
        return await(poll, listener, timeoutMs,
                new Clock() {
                    @Override public long nowMillis() { return System.nanoTime() / 1000000L; }
                },
                new Sleeper() {
                    @Override public void sleep(long milliseconds) throws InterruptedException {
                        Thread.sleep(milliseconds);
                    }
                });
    }

    static Result await(Poll poll, Listener listener, long timeoutMs, Clock clock, Sleeper sleeper) {
        long started = clock.nowMillis();
        while (true) {
            if (cancelled(listener)) return Result.CANCELLED;
            if (remaining(timeoutMs, started, clock) <= 0) return expired(listener);

            int code = Integer.MIN_VALUE;
            boolean networkFailure = false;
            boolean fatalFailure = false;
            try {
                code = poll.poll();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return Result.CANCELLED;
            } catch (BiliClient.ApiException api) {
                code = api.code;
            } catch (IOException transientFailure) {
                networkFailure = true;
            } catch (Exception failure) {
                fatalFailure = true;
            }

            // Requests can finish after the dialog was closed or the timeout elapsed.
            // Such responses must never announce a successful login to an obsolete view.
            if (cancelled(listener)) return Result.CANCELLED;
            if (remaining(timeoutMs, started, clock) <= 0) return expired(listener);

            long delay = POLL_DELAY_MS;
            if (fatalFailure) {
                listener.update("扫码登录失败，请重新打开重试");
                return Result.FAILED;
            } else if (networkFailure) {
                listener.update("连接暂时失败，正在重试，请保持二维码页面打开");
            } else if (code == 0) {
                listener.update("登录成功，可返回继续浏览");
                return Result.SUCCESS;
            } else if (code == 86038) {
                return expired(listener);
            } else if (code == 86101) {
                listener.update("等待手机扫码");
            } else if (code == 86090) {
                listener.update("已扫码，请在手机上确认登录");
            } else if (code == -352 || code == -412 || code == -403) {
                listener.update("Bilibili 暂时限制访问，正在重试（" + code + "）");
                delay = LIMITED_DELAY_MS;
            } else {
                listener.update(code == -101 ? "登录凭证验证失败，请重新扫码（-101）"
                        : "扫码登录失败，请重新扫码（错误码 " + code + "）");
                return Result.FAILED;
            }

            if (cancelled(listener)) return Result.CANCELLED;
            long left = remaining(timeoutMs, started, clock);
            if (left <= 0) return expired(listener);
            try {
                sleeper.sleep(Math.min(delay, left));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return Result.CANCELLED;
            }
        }
    }

    private static boolean cancelled(Listener listener) {
        return Thread.currentThread().isInterrupted() || !listener.active();
    }

    private static long remaining(long timeoutMs, long started, Clock clock) {
        if (timeoutMs <= 0) return 0;
        return timeoutMs - Math.max(0L, clock.nowMillis() - started);
    }

    private static Result expired(Listener listener) {
        listener.update("二维码已过期，请重新打开扫码登录");
        return Result.EXPIRED;
    }
}

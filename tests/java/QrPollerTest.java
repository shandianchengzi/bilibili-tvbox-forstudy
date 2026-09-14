package com.github.catvod.spider.bili;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Deterministic polling lifecycle tests; no network, Android calls, or real sleeps. */
public final class QrPollerTest {
    private static int assertions;

    public static void main(String[] args) {
        transientThenSuccess();
        elapsedTimeIncludesRequests();
        pendingStatuses();
        cancellationDiscardsResponse();
        interruptsCancel();
        limitedRetriesAreBounded();
        terminalErrorsDoNotRetry();
        System.out.println("QrPollerTest: " + assertions + " assertions passed");
    }

    private static void transientThenSuccess() {
        Fixture f = new Fixture();
        Script poll = new Script(new IOException("secret-qrcode-key"), new SocketTimeoutException("secret-cookie"), 0);
        equal(QrPoller.Result.SUCCESS, f.run(poll, 10000), "I/O and socket failures retry to success");
        equal(3, poll.calls, "transient failures do not terminate worker");
        equal(Arrays.asList(1500L, 1500L), f.delays, "normal polling delay after transient failure");
        check(f.messages.get(0).contains("重试"), "retry status is visible");
        check(!f.messages.toString().contains("secret"), "exception contents never enter UI status");
    }

    private static void elapsedTimeIncludesRequests() {
        final Fixture f = new Fixture();
        final int[] calls = {0};
        QrPoller.Poll slow = new QrPoller.Poll() {
            @Override public int poll() {
                calls[0]++;
                f.now += 4000L;
                return 86101;
            }
        };
        equal(QrPoller.Result.EXPIRED, f.run(slow, 5000), "request duration counts toward deadline");
        equal(1, calls[0], "no second request at deadline");
        equal(Arrays.asList(1000L), f.delays, "last sleep is capped to remaining lifetime");
        equal(5000L, f.now, "timeout follows elapsed time instead of iteration count");
        final Fixture late = new Fixture();
        equal(QrPoller.Result.EXPIRED, late.run(new QrPoller.Poll() {
            @Override public int poll() { late.now += 5001; return 0; }
        }, 5000), "success arriving after expiry is discarded");
        check(!late.messages.toString().contains("登录成功"), "late response never announces success");
        Script noRequest = new Script(0);
        equal(QrPoller.Result.EXPIRED, new Fixture().run(noRequest, 0), "zero lifetime expires immediately");
        equal(0, noRequest.calls, "expired flow makes no API request");
    }

    private static void pendingStatuses() {
        Fixture f = new Fixture();
        equal(QrPoller.Result.SUCCESS, f.run(new Script(86101, 86090, 0), 10000), "scan and confirmation states eventually succeed");
        check(f.messages.get(0).contains("等待手机扫码"), "unscanned state shown");
        check(f.messages.get(1).contains("手机上确认"), "scanned state shown");
        equal(Arrays.asList(1500L, 1500L), f.delays, "pending statuses use normal delay");
        Fixture expired = new Fixture();
        Script poll = new Script(86038, 0);
        equal(QrPoller.Result.EXPIRED, expired.run(poll, 10000), "server expiry terminates immediately");
        equal(1, poll.calls, "expired QR is not polled again");
        check(expired.delays.isEmpty(), "server expiry has no needless sleep");
    }

    private static void cancellationDiscardsResponse() {
        final Fixture f = new Fixture();
        equal(QrPoller.Result.CANCELLED, f.run(new QrPoller.Poll() {
            @Override public int poll() { f.active = false; return 0; }
        }, 10000), "dialog close during request discards success");
        check(f.messages.isEmpty(), "cancelled request never updates closed dialog");
        Fixture inactive = new Fixture();
        inactive.active = false;
        Script poll = new Script(0);
        equal(QrPoller.Result.CANCELLED, inactive.run(poll, 10000), "inactive flow cancels before request");
        equal(0, poll.calls, "inactive flow never polls");
    }

    private static void interruptsCancel() {
        try {
            Fixture before = new Fixture();
            Script poll = new Script(0);
            Thread.currentThread().interrupt();
            equal(QrPoller.Result.CANCELLED, before.run(poll, 10000), "preexisting interrupt cancels");
            check(Thread.currentThread().isInterrupted(), "preexisting interrupt is preserved");
            equal(0, poll.calls, "interrupted worker does not request");
            Thread.interrupted();

            Fixture request = new Fixture();
            equal(QrPoller.Result.CANCELLED, request.run(new Script(new InterruptedException()), 10000), "request interruption cancels");
            check(Thread.currentThread().isInterrupted(), "request interruption flag is restored");
            Thread.interrupted();

            Fixture sleep = new Fixture();
            sleep.interruptSleep = true;
            equal(QrPoller.Result.CANCELLED, sleep.run(new Script(86101, 0), 10000), "sleep interruption cancels");
            check(Thread.currentThread().isInterrupted(), "sleep interruption flag is restored");
        } finally { Thread.interrupted(); }
    }

    private static void limitedRetriesAreBounded() {
        Fixture f = new Fixture();
        Script poll = new Script(new BiliClient.ApiException(-352), new BiliClient.ApiException(-412),
                new BiliClient.ApiException(-403), 0);
        equal(QrPoller.Result.SUCCESS, f.run(poll, 20000), "temporary API limitations retry");
        equal(Arrays.asList(5000L, 5000L, 5000L), f.delays, "API limitations back off for five seconds");
        check(f.messages.get(0).contains("-352"), "status includes safe numeric API code");
        Fixture bounded = new Fixture();
        Script limited = new Script(new BiliClient.ApiException(-412), 0);
        equal(QrPoller.Result.EXPIRED, bounded.run(limited, 3000), "backoff cannot extend deadline");
        equal(Arrays.asList(3000L), bounded.delays, "limited delay is capped to QR lifetime");
        equal(1, limited.calls, "no limited API retry after deadline");
    }

    private static void terminalErrorsDoNotRetry() {
        for (Object failure : new Object[] {new BiliClient.ApiException(-101), new BiliClient.ApiException(-400),
                new IllegalStateException("secret-credential"), 12345}) {
            Fixture f = new Fixture();
            Script poll = new Script(failure, 0);
            equal(QrPoller.Result.FAILED, f.run(poll, 10000), "auth and unknown fatal errors stop");
            equal(1, poll.calls, "terminal failure is not retried");
            check(f.delays.isEmpty(), "terminal error has no sleep");
            check(f.messages.get(0).contains("失败"), "terminal failure status is explicit");
            check(!f.messages.toString().contains("secret"), "fatal exception contents remain private");
        }
    }

    private static final class Fixture implements QrPoller.Clock, QrPoller.Sleeper, QrPoller.Listener {
        long now;
        boolean active = true;
        boolean interruptSleep;
        final List<Long> delays = new ArrayList<>();
        final List<String> messages = new ArrayList<>();
        @Override public long nowMillis() { return now; }
        @Override public boolean active() { return active; }
        @Override public void update(String message) { messages.add(message); }
        @Override public void sleep(long milliseconds) throws InterruptedException {
            if (interruptSleep) throw new InterruptedException();
            delays.add(milliseconds);
            now += milliseconds;
        }
        QrPoller.Result run(QrPoller.Poll poll, long timeoutMs) {
            return QrPoller.await(poll, this, timeoutMs, this, this);
        }
    }

    private static final class Script implements QrPoller.Poll {
        final Object[] responses;
        int calls;
        Script(Object... responses) { this.responses = responses; }
        @Override public int poll() throws Exception {
            if (calls >= responses.length) throw new AssertionError("unexpected extra poll");
            Object response = responses[calls++];
            if (response instanceof Exception) throw (Exception) response;
            return (Integer) response;
        }
    }

    private static void equal(Object expected, Object actual, String message) {
        check(expected.equals(actual), message + " expected=" + expected + " actual=" + actual);
    }
    private static void check(boolean value, String message) {
        assertions++;
        if (!value) throw new AssertionError(message);
    }
}

package com.github.catvod.spider.bili;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Loopback-only, bounded store for generated QR PNGs and static DASH manifests. */
public final class LocalServer {
    private static final int MAX_ENTRIES = 64;
    private static final int MAX_BYTES = 16 * 1024 * 1024;
    private static final int MAX_ENTRY_BYTES = 4 * 1024 * 1024;
    private static final long DEFAULT_TTL_MS = 30 * 60 * 1000L;
    private static final long MAX_TTL_MS = 60 * 60 * 1000L;
    private static final LocalServer INSTANCE = new LocalServer();

    private final SecureRandom random = new SecureRandom();
    private final LinkedHashMap<String, Entry> entries = new LinkedHashMap<>();
    private final ThreadPoolExecutor workers = new ThreadPoolExecutor(0, 4, 30,
            TimeUnit.SECONDS, new ArrayBlockingQueue<Runnable>(16), new ThreadFactory() {
        @Override public Thread newThread(Runnable task) {
            Thread thread = new Thread(task, "bili-local-response");
            thread.setDaemon(true);
            return thread;
        }
    });
    private ServerSocket server;
    private int totalBytes;

    private LocalServer() { }

    public static LocalServer get() { return INSTANCE; }

    public String put(byte[] bytes, String mime) throws IOException {
        return put(bytes, mime, DEFAULT_TTL_MS);
    }

    /** The caller keeps no ownership of the stored bytes; the input is copied. */
    public synchronized String put(byte[] bytes, String mime, long ttlMillis) throws IOException {
        if (bytes == null || bytes.length == 0 || bytes.length > MAX_ENTRY_BYTES)
            throw new IllegalArgumentException("Generated asset must be 1 byte to 4 MiB");
        String extension;
        if ("image/png".equals(mime)) extension = ".png";
        else if ("application/dash+xml".equals(mime)) extension = ".mpd";
        else throw new IllegalArgumentException("Only generated PNG and DASH assets are accepted");
        if (ttlMillis <= 0 || ttlMillis > MAX_TTL_MS)
            throw new IllegalArgumentException("Asset lifetime must be between 1 ms and 1 hour");
        start();
        removeExpired();
        while (entries.size() >= MAX_ENTRIES || totalBytes + bytes.length > MAX_BYTES) {
            Iterator<Map.Entry<String, Entry>> iterator = entries.entrySet().iterator();
            Map.Entry<String, Entry> oldest = iterator.next();
            totalBytes -= oldest.getValue().bytes.length;
            iterator.remove();
        }
        byte[] nonce = new byte[24];
        random.nextBytes(nonce);
        StringBuilder token = new StringBuilder();
        for (byte value : nonce) {
            token.append(Character.forDigit((value >>> 4) & 15, 16));
            token.append(Character.forDigit(value & 15, 16));
        }
        String path = "/" + token + extension;
        Entry entry = new Entry(bytes.clone(), mime,
                System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(ttlMillis));
        entries.put(path, entry);
        totalBytes += entry.bytes.length;
        return "http://127.0.0.1:" + server.getLocalPort() + path;
    }

    public synchronized void remove(String url) {
        if (url == null) return;
        String prefix = server == null ? "" : "http://127.0.0.1:" + server.getLocalPort();
        if (prefix.isEmpty() || !url.startsWith(prefix + "/")) return;
        Entry removed = entries.remove(url.substring(prefix.length()));
        if (removed != null) totalBytes -= removed.bytes.length;
    }

    /** Invalidates generated assets; no account cookies or network media are stored here. */
    public synchronized void clear() {
        entries.clear();
        totalBytes = 0;
    }

    private synchronized Entry lookup(String path) {
        removeExpired();
        return entries.get(path);
    }

    private void removeExpired() {
        long now = System.nanoTime();
        Iterator<Map.Entry<String, Entry>> iterator = entries.entrySet().iterator();
        while (iterator.hasNext()) {
            Entry entry = iterator.next().getValue();
            if (now - entry.expiresAtNanos >= 0) {
                totalBytes -= entry.bytes.length;
                iterator.remove();
            }
        }
    }

    private void start() throws IOException {
        if (server != null && !server.isClosed()) return;
        final ServerSocket listener = new ServerSocket();
        try {
            listener.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 16);
        } catch (IOException error) {
            listener.close();
            throw error;
        }
        server = listener;
        Thread acceptor = new Thread(new Runnable() {
            @Override public void run() {
                while (!listener.isClosed()) {
                    Socket incoming = null;
                    try {
                        incoming = listener.accept();
                        incoming.setSoTimeout(5000);
                        final Socket client = incoming;
                        workers.execute(new Runnable() {
                            @Override public void run() { serve(client); }
                        });
                    } catch (IOException error) {
                        close(incoming);
                    } catch (RuntimeException error) {
                        // A full queue rejects a connection rather than growing without bounds.
                        close(incoming);
                    }
                }
            }
        }, "bili-local-listener");
        acceptor.setDaemon(true);
        acceptor.start();
    }

    private void serve(Socket client) {
        try {
            InputStream input = client.getInputStream();
            OutputStream output = client.getOutputStream();
            String first = readLine(input);
            if (first == null) return;
            String[] request = first.split(" ");
            if (request.length != 3 || !request[2].matches("HTTP/1\\.[01]")) {
                reply(output, 400, "Bad Request", null, false);
                return;
            }
            boolean head = "HEAD".equals(request[0]);
            if (!head && !"GET".equals(request[0])) {
                reply(output, 405, "Method Not Allowed", null, false);
                return;
            }
            int headerBytes = first.length();
            String line;
            while ((line = readLine(input)) != null && !line.isEmpty()) {
                headerBytes += line.length();
                if (headerBytes > 16384) {
                    reply(output, 431, "Request Header Fields Too Large", null, head);
                    return;
                }
            }
            if (line == null) return;
            // Exact opaque paths only: there is no filesystem, forwarding or directory route.
            Entry entry = request[1].matches("/[0-9a-f]{48}\\.(png|mpd)")
                    ? lookup(request[1]) : null;
            reply(output, entry == null ? 404 : 200, entry == null ? "Not Found" : "OK", entry, head);
        } catch (IOException ignored) {
            // A disconnected image loader/player must not affect other requests.
        } finally {
            close(client);
        }
    }

    private static String readLine(InputStream input) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        int value;
        while ((value = input.read()) != -1) {
            if (value == '\n') return new String(bytes.toByteArray(), StandardCharsets.US_ASCII).replace("\r", "");
            if (bytes.size() >= 4096) throw new IOException("HTTP line too long");
            bytes.write(value);
        }
        return null;
    }

    private static void reply(OutputStream output, int status, String reason, Entry entry, boolean head)
            throws IOException {
        byte[] bytes = entry == null ? reason.getBytes(StandardCharsets.UTF_8) : entry.bytes;
        String mime = entry == null ? "text/plain; charset=utf-8" : entry.mime;
        String headers = "HTTP/1.1 " + status + " " + reason + "\r\n"
                + "Content-Type: " + mime + "\r\n"
                + "Content-Length: " + bytes.length + "\r\n"
                + "Cache-Control: no-store\r\n"
                + "X-Content-Type-Options: nosniff\r\n"
                + "Connection: close\r\n\r\n";
        output.write(headers.getBytes(StandardCharsets.US_ASCII));
        if (!head) output.write(bytes);
        output.flush();
    }

    private static void close(Socket socket) {
        if (socket != null) try { socket.close(); } catch (IOException ignored) { }
    }

    private static final class Entry {
        final byte[] bytes;
        final String mime;
        final long expiresAtNanos;

        Entry(byte[] bytes, String mime, long expiresAtNanos) {
            this.bytes = bytes;
            this.mime = mime;
            this.expiresAtNanos = expiresAtNanos;
        }
    }
}

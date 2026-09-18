package cn.ellan.timedorders;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

final class RedisClient implements AutoCloseable {
    private final String host;
    private final int port;
    private final String password;
    private final int timeoutMillis;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile Socket subscriberSocket;
    private volatile Thread subscriberThread;

    RedisClient(String host, int port, String password, int timeoutMillis) {
        this.host = host;
        this.port = port;
        this.password = password == null ? "" : password;
        this.timeoutMillis = timeoutMillis;
    }

    String get(String key) throws IOException {
        Object result = command("GET", key);
        return result instanceof String string ? string : null;
    }

    void set(String key, String value, long ttlMillis) throws IOException {
        command("SET", key, value, "PX", Long.toString(ttlMillis));
    }

    void setPersistent(String key, String value) throws IOException {
        command("SET", key, value);
    }

    boolean setIfAbsent(String key, String value, long ttlMillis) throws IOException {
        Object result = command("SET", key, value, "NX", "PX", Long.toString(ttlMillis));
        return "OK".equals(result);
    }

    void expire(String key, long ttlMillis) throws IOException {
        command("PEXPIRE", key, Long.toString(ttlMillis));
    }

    void delete(String... keys) throws IOException {
        String[] args = new String[keys.length + 1];
        args[0] = "DEL";
        System.arraycopy(keys, 0, args, 1, keys.length);
        command(args);
    }

    void publish(String channel, String payload) throws IOException {
        command("PUBLISH", channel, payload);
    }

    Object command(String... args) throws IOException {
        try (Socket socket = openSocket();
             BufferedInputStream input = new BufferedInputStream(socket.getInputStream());
             BufferedOutputStream output = new BufferedOutputStream(socket.getOutputStream())) {
            authenticate(input, output);
            writeCommand(output, args);
            return readReply(input);
        }
    }

    void startSubscriber(String channel, Consumer<String> consumer, Consumer<Exception> errorConsumer) {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        subscriberThread = Thread.ofPlatform().name("EllanTimedOrders-Redis").daemon(true).start(() -> {
            while (running.get()) {
                try (Socket socket = openSocket();
                     BufferedInputStream input = new BufferedInputStream(socket.getInputStream());
                     BufferedOutputStream output = new BufferedOutputStream(socket.getOutputStream())) {
                    subscriberSocket = socket;
                    socket.setSoTimeout(0);
                    authenticate(input, output);
                    writeCommand(output, "SUBSCRIBE", channel);
                    readReply(input);
                    while (running.get()) {
                        Object reply = readReply(input);
                        if (!(reply instanceof List<?> values) || values.size() < 3) {
                            continue;
                        }
                        if (Objects.equals("message", values.get(0)) && values.get(2) instanceof String payload) {
                            consumer.accept(payload);
                        }
                    }
                } catch (Exception exception) {
                    if (running.get()) {
                        errorConsumer.accept(exception);
                        try {
                            Thread.sleep(2000L);
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                    }
                } finally {
                    subscriberSocket = null;
                }
            }
        });
    }

    private Socket openSocket() throws IOException {
        Socket socket = new Socket();
        socket.connect(new InetSocketAddress(host, port), timeoutMillis);
        socket.setSoTimeout(timeoutMillis);
        return socket;
    }

    private void authenticate(BufferedInputStream input, BufferedOutputStream output) throws IOException {
        if (password.isBlank()) {
            return;
        }
        writeCommand(output, "AUTH", password);
        Object reply = readReply(input);
        if (!"OK".equals(reply)) {
            throw new IOException("Redis authentication failed");
        }
    }

    private static void writeCommand(BufferedOutputStream output, String... args) throws IOException {
        output.write(("*" + args.length + "\r\n").getBytes(StandardCharsets.US_ASCII));
        for (String arg : args) {
            byte[] bytes = arg.getBytes(StandardCharsets.UTF_8);
            output.write(("$" + bytes.length + "\r\n").getBytes(StandardCharsets.US_ASCII));
            output.write(bytes);
            output.write('\r');
            output.write('\n');
        }
        output.flush();
    }

    private static Object readReply(BufferedInputStream input) throws IOException {
        int marker = input.read();
        if (marker < 0) {
            throw new EOFException("Redis connection closed");
        }
        return switch (marker) {
            case '+' -> readLine(input);
            case '-' -> throw new IOException("Redis error: " + readLine(input));
            case ':' -> Long.parseLong(readLine(input));
            case '$' -> readBulk(input);
            case '*' -> readArray(input);
            default -> throw new IOException("Unknown Redis reply marker: " + (char) marker);
        };
    }

    private static String readBulk(BufferedInputStream input) throws IOException {
        int length = Integer.parseInt(readLine(input));
        if (length < 0) {
            return null;
        }
        byte[] bytes = input.readNBytes(length);
        if (bytes.length != length || input.read() != '\r' || input.read() != '\n') {
            throw new EOFException("Truncated Redis bulk reply");
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static List<Object> readArray(BufferedInputStream input) throws IOException {
        int length = Integer.parseInt(readLine(input));
        if (length < 0) {
            return List.of();
        }
        List<Object> values = new ArrayList<>(length);
        for (int index = 0; index < length; index++) {
            values.add(readReply(input));
        }
        return values;
    }

    private static String readLine(BufferedInputStream input) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        int previous = -1;
        while (true) {
            int current = input.read();
            if (current < 0) {
                throw new EOFException("Redis connection closed");
            }
            if (previous == '\r' && current == '\n') {
                byte[] bytes = buffer.toByteArray();
                return new String(bytes, 0, Math.max(0, bytes.length - 1), StandardCharsets.UTF_8);
            }
            buffer.write(current);
            previous = current;
        }
    }

    @Override
    public void close() {
        running.set(false);
        Socket socket = subscriberSocket;
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
        Thread thread = subscriberThread;
        if (thread != null) {
            thread.interrupt();
        }
    }
}

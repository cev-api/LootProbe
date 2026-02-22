package dev.lootprobe;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

public final class RconClient implements AutoCloseable {
    private static final int TYPE_AUTH = 3;
    private static final int TYPE_COMMAND = 2;
    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int READ_TIMEOUT_MS = 300_000;

    private final String host;
    private final int port;
    private final String password;

    private final AtomicInteger requestId = new AtomicInteger(1);
    private Socket socket;
    private DataInputStream in;
    private DataOutputStream out;

    public RconClient(String host, int port, String password) {
        this.host = host;
        this.port = port;
        this.password = password;
    }

    public void connectAndLogin() throws IOException {
        socket = new Socket();
        socket.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
        socket.setSoTimeout(READ_TIMEOUT_MS);
        in = new DataInputStream(socket.getInputStream());
        out = new DataOutputStream(socket.getOutputStream());

        int id = requestId.getAndIncrement();
        writePacket(id, TYPE_AUTH, password);
        boolean ok = false;
        for (int i = 0; i < 3; i++) {
            try {
                Packet response = readPacket();
                if (response.requestId == -1) {
                    throw new IOException("RCON authentication failed.");
                }
                if (response.requestId == id) {
                    ok = true;
                    break;
                }
            } catch (SocketTimeoutException timeout) {
                if (ok) {
                    break;
                }
                throw timeout;
            }
        }
        if (!ok) {
            throw new IOException("RCON authentication did not return expected request id.");
        }
    }

    public String command(String command) throws IOException {
        return executeWithRetry(command, 1);
    }

    public String commandOnce(String command, int readTimeoutMs) throws IOException {
        return executeOnce(command, Math.max(1000, readTimeoutMs));
    }

    private String executeWithRetry(String command, int retries) throws IOException {
        try {
            return executeOnce(command, READ_TIMEOUT_MS);
        } catch (IOException e) {
            if (retries <= 0 || !isRecoverableConnectionIssue(e)) {
                throw e;
            }
            reconnect();
            return executeWithRetry(command, retries - 1);
        }
    }

    private String executeOnce(String command, int readTimeoutMs) throws IOException {
        int previousTimeout = socket != null ? socket.getSoTimeout() : READ_TIMEOUT_MS;
        if (socket != null) {
            socket.setSoTimeout(readTimeoutMs);
        }
        int id = requestId.getAndIncrement();
        try {
            writePacket(id, TYPE_COMMAND, command);
            StringBuilder sb = new StringBuilder();
            while (true) {
                Packet packet = readPacket();
                if (packet.requestId != id) {
                    continue;
                }
                sb.append(packet.payload);
                if (packet.payload.length() < 4096) {
                    break;
                }
            }
            return sb.toString().trim();
        } finally {
            if (socket != null) {
                socket.setSoTimeout(previousTimeout);
            }
        }
    }

    private void reconnect() throws IOException {
        close();
        try {
            Thread.sleep(250);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
        connectAndLogin();
    }

    private boolean isRecoverableConnectionIssue(IOException e) {
        if (e instanceof SocketTimeoutException || e instanceof EOFException || e instanceof SocketException) {
            return true;
        }
        String msg = e.getMessage();
        if (msg == null) {
            return false;
        }
        String s = msg.toLowerCase();
        return s.contains("connection reset")
                || s.contains("connection closed")
                || s.contains("broken pipe")
                || s.contains("timed out");
    }

    private void writePacket(int id, int type, String payload) throws IOException {
        byte[] body = payload.getBytes(StandardCharsets.UTF_8);
        int length = 4 + 4 + body.length + 2;
        ByteBuffer buffer = ByteBuffer.allocate(4 + length).order(ByteOrder.LITTLE_ENDIAN);
        buffer.putInt(length);
        buffer.putInt(id);
        buffer.putInt(type);
        buffer.put(body);
        buffer.put((byte) 0);
        buffer.put((byte) 0);
        out.write(buffer.array());
        out.flush();
    }

    private Packet readPacket() throws IOException {
        int length;
        try {
            length = Integer.reverseBytes(in.readInt());
        } catch (EOFException e) {
            throw new IOException("RCON connection closed.", e);
        }

        byte[] data = in.readNBytes(length);
        if (data.length != length) {
            throw new IOException("Incomplete RCON packet.");
        }
        ByteBuffer buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        int id = buffer.getInt();
        int type = buffer.getInt();
        byte[] payloadBytes = new byte[Math.max(0, length - 10)];
        buffer.get(payloadBytes);
        buffer.get();
        buffer.get();
        String payload = new String(payloadBytes, StandardCharsets.UTF_8);
        return new Packet(id, type, payload);
    }

    @Override
    public void close() {
        try {
            if (socket != null) {
                socket.close();
            }
        } catch (IOException ignored) {
        }
    }

    private record Packet(int requestId, int type, String payload) {
    }
}

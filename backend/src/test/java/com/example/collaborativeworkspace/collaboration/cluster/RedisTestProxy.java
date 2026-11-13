package com.example.collaborativeworkspace.collaboration.cluster;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Executors;

/** Test-only network fault boundary. Never intercepts or prints Redis AUTH bytes. */
final class RedisTestProxy implements AutoCloseable {
    private final ServerSocket listener = new ServerSocket(0, 16, InetAddress.getLoopbackAddress());
    private final java.util.concurrent.ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
    private final Set<Socket> sockets = new HashSet<>();
    private boolean blocked = true;
    RedisTestProxy() throws IOException { workers.submit(this::accept); }
    int port() { return listener.getLocalPort(); }
    synchronized void block() { blocked = true; sockets.forEach(RedisTestProxy::closeSocket); sockets.clear(); }
    synchronized void restore() { blocked = false; }
    private void accept() {
        while (!listener.isClosed()) {
            try {
                Socket client = listener.accept();
                synchronized (this) {
                    if (blocked || sockets.size() >= 32) { client.close(); continue; }
                    Socket server = new Socket();
                    try { server.connect(new java.net.InetSocketAddress("127.0.0.1", 6379), 500); }
                    catch (IOException failure) { client.close(); server.close(); continue; }
                    sockets.add(client); sockets.add(server);
                    workers.submit(() -> copy(client, server)); workers.submit(() -> copy(server, client));
                }
            } catch (IOException ignored) { if (listener.isClosed()) return; }
        }
    }
    private void copy(Socket from, Socket to) {
        try { from.getInputStream().transferTo(to.getOutputStream()); }
        catch (IOException ignored) { }
        finally {
            closeSocket(from); closeSocket(to);
            synchronized (this) { sockets.remove(from); sockets.remove(to); }
        }
    }
    private static void closeSocket(Socket socket) { try { socket.close(); } catch (IOException ignored) { } }
    @Override public void close() throws IOException { listener.close(); block(); workers.shutdownNow(); }
}

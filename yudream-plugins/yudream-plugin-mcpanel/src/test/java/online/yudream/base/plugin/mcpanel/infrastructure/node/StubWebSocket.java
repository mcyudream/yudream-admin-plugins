package online.yudream.base.plugin.mcpanel.infrastructure.node;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * 受控 WebSocket 替身（仅测试）：真实实现 java.net.http.WebSocket 的发送面语义——
 * sendText 返回写完成 future；autoCompleteWrites=true 时立即完成（内核缓冲未满），
 * false 时 future 挂起进入 pendingWrites，由测试控制完成/失败，用于重现
 * 「sendText 异步失败被吞」的旧缺陷与验证有界保序写队列的失败传播。
 */
final class StubWebSocket implements java.net.http.WebSocket {

    final List<String> sentFrames = Collections.synchronizedList(new ArrayList<>());
    final ConcurrentLinkedDeque<CompletableFuture<java.net.http.WebSocket>> pendingWrites =
            new ConcurrentLinkedDeque<>();
    volatile boolean autoCompleteWrites = true;
    volatile boolean aborted;
    volatile int closeCode = -1;

    int sendTextCount() {
        synchronized (sentFrames) {
            return sentFrames.size();
        }
    }

    String lastFrame() {
        synchronized (sentFrames) {
            return sentFrames.isEmpty() ? null : sentFrames.get(sentFrames.size() - 1);
        }
    }

    /** 把尚未完成的写 future 全部按异常收尾（模拟底层断链后的异步写失败）。 */
    void failPendingWrites(Throwable cause) {
        CompletableFuture<java.net.http.WebSocket> future;
        while ((future = pendingWrites.pollFirst()) != null) {
            future.completeExceptionally(cause);
        }
    }

    /** 完成最早的挂起写（模拟内核缓冲排空、字节真正写出）。 */
    void completeOldestPendingWrite() {
        CompletableFuture<java.net.http.WebSocket> future = pendingWrites.pollFirst();
        if (future != null) {
            future.complete(this);
        }
    }

    @Override
    public CompletableFuture<java.net.http.WebSocket> sendText(CharSequence data, boolean last) {
        sentFrames.add(String.valueOf(data));
        CompletableFuture<java.net.http.WebSocket> future = new CompletableFuture<>();
        if (autoCompleteWrites) {
            future.complete(this);
        } else {
            pendingWrites.add(future);
        }
        return future;
    }

    @Override
    public CompletableFuture<java.net.http.WebSocket> sendBinary(ByteBuffer data, boolean last) {
        throw new UnsupportedOperationException();
    }

    @Override
    public CompletableFuture<java.net.http.WebSocket> sendPing(ByteBuffer message) {
        return CompletableFuture.completedFuture(this);
    }

    @Override
    public CompletableFuture<java.net.http.WebSocket> sendPong(ByteBuffer message) {
        return CompletableFuture.completedFuture(this);
    }

    @Override
    public CompletableFuture<java.net.http.WebSocket> sendClose(int statusCode, String reason) {
        this.closeCode = statusCode;
        return CompletableFuture.completedFuture(this);
    }

    @Override
    public void request(long n) {
        // 需求计数与替身无关
    }

    @Override
    public String getSubprotocol() {
        return "";
    }

    @Override
    public boolean isOutputClosed() {
        return aborted || closeCode >= 0;
    }

    @Override
    public boolean isInputClosed() {
        return aborted;
    }

    @Override
    public void abort() {
        aborted = true;
    }
}

package online.yudream.base.plugin.mcpanel.infrastructure.node;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * 每连接有界保序出站写帧队列（协议 v1 §4；transport 内部机制，非线上协议）。
 *
 * 语义：
 * - 严格保序：头帧写完成（sendText future 完成）前绝不发起后续写操作，杜绝
 *   JDK WebSocket 异步写「乱序插队」；
 * - 只等「字节写出」完成，不等业务响应；res/err 关联仍由 Conn 的 pending 承担；
 * - 失败立即传播：头帧写失败或写超时 → 该帧与全部未决帧立即异常完成并进入
 *   closed（此后 write 一律失败）；连接级收尾由 Conn 关闭流程触发；
 * - 有界：容量满时立即拒绝新帧（保住已登记帧，不静默丢弃）；
 * - 请求放弃：discard 支持把「调用方已超时/放弃」且尚未交给 wire 的帧摘除，
 *   避免界面已报超时之后变更才真正发出；已交给 wire 的帧无法撤回（返回 false）；
 * - 断线/换代/卸载：failAll 立即异常完成全部未决帧并拒绝后续写入。
 */
final class ConnWriteQueue {

    /** 发送通道：生产实现即 WebSocket::sendText（返回写完成 future）。 */
    interface Wire {

        CompletableFuture<java.net.http.WebSocket> sendText(String frame, boolean last);
    }

    /** 单帧排队项。 */
    private static final class Frame {

        final String encoded;
        QueuedWrite write;
        ScheduledFuture<?> timeoutTask;

        Frame(String encoded) {
            this.encoded = encoded;
        }
    }

    /** 一次入队写的句柄：完成即已写出；失败/discard 即不会或未能写出。 */
    static final class QueuedWrite extends CompletableFuture<Void> {

        private Frame bound;
        private volatile boolean discarded;

        QueuedWrite(Frame bound) {
            this.bound = bound;
        }

        boolean isDiscarded() {
            return discarded;
        }

        private Frame bound() {
            return bound;
        }

        private void unbind() {
            bound = null;
        }
    }

    private static final NodeCallException DISCARDED =
            new NodeCallException("node.abandoned", "请求已放弃，帧未发送");

    /** 未建立连接（未 onOpen）期间的占位队列：任何写入立即失败。 */
    static ConnWriteQueue dead() {
        return new ConnWriteQueue((frame, last) ->
                CompletableFuture.failedFuture(new NodeCallException("node.offline", "连接未建立")), 1, 0L, null);
    }

    private final Wire wire;
    private final int capacity;
    private final long writeTimeoutMs;
    private final Supplier<ScheduledExecutorService> timeouts;
    private final Object lock = new Object();
    private final Deque<Frame> queue = new ArrayDeque<>();
    private boolean inFlight;
    private boolean closed;
    private NodeCallException closedCause;

    ConnWriteQueue(Wire wire, int capacity, long writeTimeoutMs, Supplier<ScheduledExecutorService> timeouts) {
        this.wire = wire;
        this.capacity = Math.max(1, capacity);
        this.writeTimeoutMs = writeTimeoutMs;
        this.timeouts = timeouts;
    }

    /** 入队一帧；返回的 future 完成即「已交给 wire 且 wire 确认写出」。满/关闭立即失败。 */
    QueuedWrite write(String frame) {
        synchronized (lock) {
            if (closed) {
                QueuedWrite rejected = new QueuedWrite(null);
                rejected.completeExceptionally(closedCause());
                return rejected;
            }
            if (queue.size() >= capacity) {
                QueuedWrite rejected = new QueuedWrite(null);
                rejected.completeExceptionally(
                        new NodeCallException("node.congested", "出站写队列已满"));
                return rejected;
            }
            Frame frameEntry = new Frame(frame);
            frameEntry.write = new QueuedWrite(frameEntry);
            queue.addLast(frameEntry);
            pumpLocked();
            return frameEntry.write;
        }
    }

    /**
     * 请求已放弃：把尚未交给 wire 的帧摘除并异常完成其 future；已交给 wire（不可
     * 撤回）或队列已收尾时返回 false。丢弃不计为连接失败（调用方据此跳过收尾）。
     */
    boolean discard(QueuedWrite write) {
        synchronized (lock) {
            Frame frame = write.bound();
            if (closed || frame == null) {
                return false;
            }
            if (inFlight && queue.peekFirst() == frame) {
                return false;
            }
            if (queue.remove(frame)) {
                write.unbind();
                write.discarded = true;
                write.completeExceptionally(DISCARDED);
                return true;
            }
            return false;
        }
    }

    /** 断线/换代/卸载收尾：立即异常完成全部未决帧（含在途头帧），此后 write 一律失败。 */
    void failAll(NodeCallException cause) {
        synchronized (lock) {
            failAllLocked(cause);
        }
    }

    boolean closed() {
        synchronized (lock) {
            return closed;
        }
    }

    // ---------- 内部：仅在 lock 内驱动 ----------

    private void pumpLocked() {
        if (inFlight || closed) {
            return;
        }
        Frame head = queue.peekFirst();
        if (head == null) {
            return;
        }
        inFlight = true;
        CompletableFuture<java.net.http.WebSocket> future;
        try {
            future = wire.sendText(head.encoded, true);
        } catch (RuntimeException error) {
            inFlight = false;
            failAllLocked(NodeCallException.wrapSendFailure(error));
            return;
        }
        scheduleWriteTimeout(head);
        future.whenComplete((websocket, error) -> onWritten(head, error));
    }

    private void onWritten(Frame frame, Throwable error) {
        synchronized (lock) {
            cancelTimeoutLocked(frame);
            if (queue.peekFirst() == frame) {
                queue.pollFirst();
            }
            inFlight = false;
            if (error != null) {
                // 写失败即连接不可写：先失败头帧自身（已不在队尾循环覆盖范围内），
                // 再整队立即失败，由 Conn 走关闭/重连。
                NodeCallException cause = NodeCallException.wrapSendFailure(error);
                frame.write.completeExceptionally(cause);
                failAllLocked(cause);
                return;
            }
            frame.write.complete(null);
            pumpLocked();
        }
    }

    private void scheduleWriteTimeout(Frame frame) {
        if (writeTimeoutMs <= 0L || timeouts == null) {
            return;
        }
        ScheduledExecutorService executor = timeouts.get();
        if (executor == null || executor.isShutdown()) {
            return;
        }
        frame.timeoutTask = executor.schedule(() -> onWriteTimeout(frame), writeTimeoutMs, TimeUnit.MILLISECONDS);
    }

    private void onWriteTimeout(Frame frame) {
        synchronized (lock) {
            if (closed || queue.peekFirst() != frame) {
                return;
            }
            frame.timeoutTask = null;
            queue.pollFirst();
            inFlight = false;
            NodeCallException cause = new NodeCallException("node.sendTimeout", "发送超时");
            frame.write.completeExceptionally(cause);
            failAllLocked(cause);
        }
    }

    private void cancelTimeoutLocked(Frame frame) {
        ScheduledFuture<?> task = frame.timeoutTask;
        frame.timeoutTask = null;
        if (task != null) {
            task.cancel(false);
        }
    }

    private void failAllLocked(NodeCallException cause) {
        closed = true;
        closedCause = cause;
        Frame frame;
        while ((frame = queue.pollFirst()) != null) {
            cancelTimeoutLocked(frame);
            frame.write.completeExceptionally(cause);
        }
    }

    private NodeCallException closedCause() {
        return closedCause != null ? closedCause : new NodeCallException("node.offline", "连接已断开");
    }
}

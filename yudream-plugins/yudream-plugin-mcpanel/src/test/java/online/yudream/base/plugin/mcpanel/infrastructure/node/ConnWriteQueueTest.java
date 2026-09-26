package online.yudream.base.plugin.mcpanel.infrastructure.node;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.http.WebSocket;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ConnWriteQueue：有界保序出站写帧队列的纯单元回归。
 * - 严格保序：头帧写完成前不发后续写（写超时/挂起期间队列不透传）；
 * - 失败立即传播：头帧失败/超时 → 全队立即异常完成并 closed；
 * - 有界：满则立即拒绝新帧，不丢已登记帧；
 * - discard：只摘除尚未交给 wire 的帧（调用方已超时/放弃的活性保障）。
 */
class ConnWriteQueueTest {

    /** 记录 sendText 调用顺序、写 future 由测试手动收尾的 wire。 */
    private static final class RecordingWire implements ConnWriteQueue.Wire {

        final List<String> sent = Collections.synchronizedList(new java.util.ArrayList<>());
        final ConcurrentLinkedDeque<CompletableFuture<WebSocket>> pending = new ConcurrentLinkedDeque<>();
        volatile RuntimeException syncFailure;

        @Override
        public CompletableFuture<WebSocket> sendText(String frame, boolean last) {
            if (syncFailure != null) {
                throw syncFailure;
            }
            sent.add(frame);
            CompletableFuture<WebSocket> future = new CompletableFuture<>();
            pending.add(future);
            return future;
        }

        void completeHead() {
            CompletableFuture<WebSocket> head = pending.pollFirst();
            if (head != null) {
                head.complete(null);
            }
        }

        void failHead(Throwable cause) {
            CompletableFuture<WebSocket> head = pending.pollFirst();
            if (head != null) {
                head.completeExceptionally(cause);
            }
        }
    }

    private ScheduledExecutorService timeoutScheduler;

    @AfterEach
    void tearDown() {
        if (timeoutScheduler != null) {
            timeoutScheduler.shutdownNow();
        }
    }

    private static void await(String what, BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5_000;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("timeout waiting for: " + what);
    }

    @Test
    void preservesOrderAndOnlySendsAfterPreviousWriteCompletes() throws Exception {
        RecordingWire wire = new RecordingWire();
        ConnWriteQueue queue = new ConnWriteQueue(wire, 8, 0L, null);

        ConnWriteQueue.QueuedWrite first = queue.write("frame-a");
        ConnWriteQueue.QueuedWrite second = queue.write("frame-b");
        ConnWriteQueue.QueuedWrite third = queue.write("frame-c");

        // 头帧未写完成前，绝不发起后续写操作。
        await("only head handed to wire", () -> wire.sent.size() == 1);
        assertEquals("frame-a", wire.sent.get(0));
        assertFalse(first.isDone(), "写完成 future 不得早于 wire 确认");

        wire.completeHead();
        await("second handed after head completes", () -> wire.sent.size() == 2);
        assertTrue(first.isDone() && !first.isCompletedExceptionally(), "头帧写完成");
        wire.completeHead();
        await("third handed", () -> wire.sent.size() == 3);
        wire.completeHead();
        assertTrue(second.isDone() && !second.isCompletedExceptionally());
        assertTrue(third.isDone() && !third.isCompletedExceptionally());
        assertEquals(List.of("frame-a", "frame-b", "frame-c"), wire.sent);
    }

    @Test
    void fullQueueRejectsNewFrameWithoutDroppingQueuedOnes() throws Exception {
        RecordingWire wire = new RecordingWire();
        ConnWriteQueue queue = new ConnWriteQueue(wire, 2, 0L, null);

        ConnWriteQueue.QueuedWrite first = queue.write("a");
        ConnWriteQueue.QueuedWrite second = queue.write("b");
        ConnWriteQueue.QueuedWrite rejected = queue.write("c");

        assertTrue(rejected.isCompletedExceptionally(), "满队必须立即拒绝新帧");
        try {
            rejected.join();
            throw new AssertionError("应当拒绝");
        } catch (java.util.concurrent.CompletionException error) {
            assertTrue(error.getCause() instanceof NodeCallException);
            assertEquals("node.congested", ((NodeCallException) error.getCause()).code());
        }
        // 已登记帧不受影响：头帧完成后 b 立即发出。
        wire.completeHead();
        await("queued frame still delivered", () -> wire.sent.size() == 2);
        assertEquals("b", wire.sent.get(1));
        // b 的写完成 future 由 wire 确认后才完成。
        wire.completeHead();
        await("second write confirmed", second::isDone);
        assertTrue(first.isDone() && second.isDone() && !second.isCompletedExceptionally());
    }

    @Test
    void headWriteFailureFailsAllQueuedWritesAndClosesQueue() throws Exception {
        RecordingWire wire = new RecordingWire();
        ConnWriteQueue queue = new ConnWriteQueue(wire, 8, 0L, null);

        ConnWriteQueue.QueuedWrite first = queue.write("a");
        ConnWriteQueue.QueuedWrite second = queue.write("b");

        wire.failHead(new java.io.IOException("broken pipe"));
        await("head failed", first::isDone);
        await("queued failed immediately", second::isDone);
        assertTrue(first.isCompletedExceptionally());
        assertTrue(second.isCompletedExceptionally(), "写失败必须立即传播到全部未决帧");
        assertTrue(queue.closed(), "写失败后队列必须进入 closed");

        ConnWriteQueue.QueuedWrite afterClose = queue.write("c");
        assertTrue(afterClose.isCompletedExceptionally(), "closed 后写入立即失败");
        assertEquals(1, wire.sent.size(), "closed 后不得再写 wire");
    }

    @Test
    void writeTimeoutFailsQueue() throws Exception {
        RecordingWire wire = new RecordingWire();
        timeoutScheduler = Executors.newSingleThreadScheduledExecutor();
        ConnWriteQueue queue = new ConnWriteQueue(wire, 8, 80L, () -> timeoutScheduler);

        ConnWriteQueue.QueuedWrite first = queue.write("a");
        await("write timeout fails frame", first::isDone);
        assertTrue(first.isCompletedExceptionally());
        try {
            first.join();
            throw new AssertionError("应当超时");
        } catch (java.util.concurrent.CompletionException error) {
            assertTrue(error.getCause() instanceof NodeCallException);
            assertEquals("node.sendTimeout", ((NodeCallException) error.getCause()).code());
        }
        assertTrue(queue.closed(), "写超时视为连接坏死");
    }

    @Test
    void wireSendThrowingFailsQueueImmediately() throws Exception {
        RecordingWire wire = new RecordingWire();
        wire.syncFailure = new IllegalStateException("socket closed");
        ConnWriteQueue queue = new ConnWriteQueue(wire, 8, 0L, null);

        ConnWriteQueue.QueuedWrite first = queue.write("a");
        await("sync failure propagates", first::isDone);
        assertTrue(first.isCompletedExceptionally());
        assertTrue(queue.closed());
        assertTrue(wire.sent.isEmpty());
    }

    @Test
    void discardRemovesOnlyFramesNotYetHandedToWire() throws Exception {
        RecordingWire wire = new RecordingWire();
        ConnWriteQueue queue = new ConnWriteQueue(wire, 8, 0L, null);

        ConnWriteQueue.QueuedWrite head = queue.write("a");
        ConnWriteQueue.QueuedWrite queued = queue.write("b");
        ConnWriteQueue.QueuedWrite tail = queue.write("c");

        // 在途头帧不可撤回；排队帧可摘除。
        assertFalse(queue.discard(head), "已交给 wire 的帧不可撤回");
        assertTrue(queue.discard(queued), "未发出的帧应当被摘除");
        assertTrue(queued.isDiscarded() && queued.isCompletedExceptionally());

        wire.completeHead();
        await("tail handed after head completes", () -> wire.sent.size() == 2);
        assertEquals(List.of("a", "c"), wire.sent, "被丢弃的帧不得再发出");
        wire.completeHead();
        await("tail write confirmed", tail::isDone);
        assertTrue(!tail.isCompletedExceptionally());
        assertFalse(queue.discard(tail), "已写出的帧不可 discard");
    }
}

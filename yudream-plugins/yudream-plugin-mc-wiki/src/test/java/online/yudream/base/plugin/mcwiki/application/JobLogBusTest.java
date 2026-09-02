package online.yudream.base.plugin.mcwiki.application;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import online.yudream.base.plugin.spi.http.PluginSseStream;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class JobLogBusTest {
    @Test void replaysBufferedEventsToLateSubscriber() { JobLogBus bus=new JobLogBus("stream"); bus.emit("progress",Map.of("phase","DOWNLOAD")); AtomicInteger sent=new AtomicInteger(); bus.subscribe(new PluginSseStream.Subscriber(){public void send(String event,Object value){assertEquals("progress",event);sent.incrementAndGet();}public void complete(){}public void error(Throwable error){fail(error);}}); assertEquals(1,sent.get()); assertEquals(1,bus.after(0).size()); assertEquals(0,bus.after(1).size()); }
    @Test void keepsOnlyBoundedHistory() { JobLogBus bus=new JobLogBus("stream"); for(int i=0;i<600;i++)bus.emit("log",Map.of("i",i)); assertEquals(500,bus.after(0).size()); assertEquals(1,bus.after(599).size()); }
}

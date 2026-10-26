package online.yudream.ysm.model;
import online.yudream.ysm.protocol.*;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
class ViewerSessionTest {
    Protocol.Snapshot snapshot(String revision){return new Protocol.Snapshot(revision,"sha","url",Map.of("model",new Protocol.Asset("model",revision,"url")));}
    @Test void snapshotDoesNotChangeUntilMatchingSuccessAndFailureRetainsOldAssets(){
        var session=new ViewerSession();assertTrue(session.begin(snapshot("old"),0,1000,true));assertFalse(session.begin(snapshot("other"),1,1000,true));
        assertFalse(session.complete("stale",true));assertNull(session.active());assertTrue(session.complete("old",true));
        assertFalse(session.begin(snapshot("new"),500,1000,false));assertTrue(session.begin(snapshot("new"),1500,1000,false));
        assertEquals("old",session.model("model").orElseThrow().hash());assertFalse(session.complete("new",false));assertEquals("old",session.active().revision());
        assertTrue(session.begin(snapshot("new"),3000,1000,false));assertTrue(session.complete("new",true));assertEquals("new",session.model("model").orElseThrow().hash());
        assertFalse(session.begin(snapshot("new"),5000,1000,false));
    }
}

package online.yudream.ysm.protocol;

import java.util.*;

/** Viewer-local state machine. Failed and superseded requests cannot advance a snapshot. */
public final class ViewerSession {
    private Protocol.Snapshot active,pending;
    private long requestedAt=Long.MIN_VALUE;
    private boolean initialRequested;
    public synchronized boolean begin(Protocol.Snapshot next,long now,long cooldown,boolean initial) {
        if(pending!=null || initial&&initialRequested)return false;
        if(initial)initialRequested=true;
        if(active!=null && active.revision().equals(next.revision()))return false;
        if(!initial && requestedAt!=Long.MIN_VALUE && now-requestedAt<cooldown)return false;
        requestedAt=now;pending=next;return true;
    }
    public synchronized boolean complete(String revision,boolean success) {
        if(pending==null || !pending.revision().equals(revision))return false;
        if(success)active=pending;pending=null;return success;
    }
    public synchronized Protocol.Snapshot active(){return active;}
    public synchronized Protocol.Snapshot pending(){return pending;}
    public synchronized Optional<Protocol.Asset> model(String id){return active==null?Optional.empty():Optional.ofNullable(active.models().get(id));}
}

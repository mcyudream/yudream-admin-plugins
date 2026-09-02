package online.yudream.base.plugin.mcwiki.application;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import online.yudream.base.plugin.spi.http.PluginSseStream;
import online.yudream.base.plugin.spi.system.storage.PluginDocumentStore;

public final class JobService {
    public record Job(String jobId, String version, String phase, long done, long total, String status, String error, long createdAt, long updatedAt, String streamId) {}
    @FunctionalInterface public interface Work { void run(String version, Control control); }
    public final class Control {
        private final Job original; private Job current;
        private Control(Job original){this.original=original;this.current=original;}
        public void checkpoint(){AtomicBoolean paused=pauses.get(original.jobId());while(paused!=null&&paused.get()){current=update(current,current.phase(),current.done(),current.total(),"PAUSED",null);emit(current,"state","任务已暂停，等待继续");try{Thread.sleep(150);}catch(InterruptedException ex){Thread.currentThread().interrupt();throw new Cancelled();}if(cancels.get(original.jobId()).get())throw new Cancelled();}if(cancels.get(original.jobId()).get())throw new Cancelled();}
        public void progress(String phase,long done,long total,String message){checkpoint();current=update(current,phase,done,total,"RUNNING",null);emit(current,"progress",message);}
        public void log(String level,String message){emit(current,"log",message,level);}
    }
    private static final String JOBS="import_jobs"; private static final String EVENTS="import_job_events"; private static final int PAGE=200;
    private final PluginDocumentStore documents; private final Executor executor; private final Work work;
    private final Map<String, JobLogBus> buses=new java.util.concurrent.ConcurrentHashMap<>(); private final Map<String, AtomicBoolean> cancels=new java.util.concurrent.ConcurrentHashMap<>(); private final Map<String, AtomicBoolean> pauses=new java.util.concurrent.ConcurrentHashMap<>();
    public JobService(PluginDocumentStore documents, Executor executor, Work work){this.documents=documents;this.executor=executor;this.work=work;}
    public Job create(String version){long now=System.currentTimeMillis();String id=UUID.randomUUID().toString(),stream=UUID.randomUUID().toString();Job job=new Job(id,version,"QUEUE",0,0,"PENDING",null,now,now,stream);save(job);buses.put(stream,new JobLogBus(stream));cancels.put(id,new AtomicBoolean());pauses.put(id,new AtomicBoolean());try{executor.execute(()->run(job));}catch(RuntimeException ex){Job failed=update(job,"QUEUE",0,0,"FAILED","任务队列已满");emit(failed,"state",failed.error());JobLogBus bus=buses.remove(stream);if(bus!=null)bus.complete();}return job;}
    public List<Map<String,Object>> list(int page,int size){return documents.findAll(JOBS,Math.max(page,1),Math.min(Math.max(size,1),PAGE));}
    public long count(){return documents.count(JOBS);}
    public Job get(String id){return read(documents.findById(JOBS,id).orElseThrow(()->new IllegalArgumentException("任务不存在")));}
    public List<JobLogBus.Event> eventsAfter(String streamId,long seq){JobLogBus bus=buses.get(streamId);if(bus!=null)return bus.after(seq);return persistedEvents(streamId).stream().filter(event->event.seq()>seq).toList();}
    /** 运行中的任务走内存总线；插件重载/重启后内存总线丢失，历史任务回退为按持久化事件重放，仅完全不存在的任务返回 null。 */
    public PluginSseStream stream(String id){JobLogBus bus=buses.get(id);if(bus!=null)return bus;return findByStreamId(id)==null?null:new ReplayStream(persistedEvents(id));}
    public Job pause(String id){Job job=get(id);AtomicBoolean flag=pauses.get(id);if(flag==null||terminal(job))return job;flag.set(true);Job next=update(job,job.phase(),job.done(),job.total(),"PAUSING",null);emit(next,"state","正在等待安全点暂停");return next;}
    public Job resume(String id){Job job=get(id);AtomicBoolean flag=pauses.get(id);if(flag==null||terminal(job))return job;flag.set(false);Job next=update(job,job.phase(),job.done(),job.total(),"RESUMING",null);emit(next,"state","正在继续任务");return next;}
    public Job cancel(String id){Job job=get(id);AtomicBoolean flag=cancels.get(id);if(flag!=null)flag.set(true);Job next=update(job,job.phase(),job.done(),job.total(),"CANCELLING",null);emit(next,"state","正在等待安全点取消");return next;}
    public void delete(String id){AtomicBoolean flag=cancels.get(id);if(flag!=null)flag.set(true);Map<String,Object> doc=documents.findById(JOBS,id).orElse(null);if(doc!=null){String streamId=String.valueOf(doc.get("streamId"));JobLogBus bus=buses.remove(streamId);if(bus!=null)bus.complete();deleteEvents(streamId);}cancels.remove(id);pauses.remove(id);documents.delete(JOBS,id);}
    private boolean terminal(Job job){return List.of("DONE","FAILED","CANCELLED").contains(job.status());}
    private void run(Job original){Control control=new Control(original);try{control.progress("START",0,1,"导入任务已启动");work.run(original.version(),control);Job done=update(control.current,"DONE",control.current.total(),control.current.total(),"DONE",null);emit(done,"state","导入完成");complete(done);}catch(Cancelled ex){Job cancelled=update(control.current,control.current.phase(),control.current.done(),control.current.total(),"CANCELLED",null);emit(cancelled,"state","导入已取消");complete(cancelled);}catch(Exception ex){Job failed=update(control.current,control.current.phase(),control.current.done(),control.current.total(),"FAILED",safeMessage(ex));emit(failed,"state",failed.error());complete(failed);}}
    private Job update(Job old,String phase,long done,long total,String status,String error){Job next=new Job(old.jobId(),old.version(),phase,done,total,status,error,old.createdAt(),System.currentTimeMillis(),old.streamId());save(next);return next;}
    private void complete(Job job){JobLogBus bus=buses.remove(job.streamId());if(bus!=null)bus.complete();cancels.remove(job.jobId());pauses.remove(job.jobId());}
    private void emit(Job job,String type,String message){emit(job,type,message,"INFO");}
    private void emit(Job job,String type,String message,String level){JobLogBus bus=buses.get(job.streamId());if(bus==null)return;JobLogBus.Event event=bus.emit(type,Map.of("jobId",job.jobId(),"version",job.version(),"phase",job.phase(),"done",job.done(),"total",job.total(),"status",job.status(),"level",level,"message",message,"updatedAt",job.updatedAt()));if(event!=null)persistEvent(job.streamId(),event);}
    private void persistEvent(String streamId,JobLogBus.Event event){Map<String,Object> doc=new LinkedHashMap<>();doc.put("streamId",streamId);doc.put("seq",event.seq());doc.put("type",event.type());doc.put("payload",event.payload());documents.save(EVENTS,eventKey(streamId,event.seq()),doc);}
    private static String eventKey(String streamId,long seq){return streamId+":"+String.format("%08d",seq);}
    @SuppressWarnings("unchecked")
    private List<JobLogBus.Event> persistedEvents(String streamId){List<JobLogBus.Event> events=new ArrayList<>();for(int page=1;;page++){List<Map<String,Object>> docs=documents.findByField(EVENTS,"streamId",streamId,page,PAGE);for(Map<String,Object> doc:docs){Object payload=doc.get("payload");events.add(new JobLogBus.Event(((Number)doc.getOrDefault("seq",0)).longValue(),String.valueOf(doc.get("type")),payload instanceof Map<?,?>?(Map<String,Object>)payload:Map.of()));}if(docs.size()<PAGE)break;}events.sort(Comparator.comparingLong(JobLogBus.Event::seq));return events;}
    private void deleteEvents(String streamId){for(;;){List<Map<String,Object>> docs=documents.findByField(EVENTS,"streamId",streamId,1,PAGE);if(docs.isEmpty())break;for(Map<String,Object> doc:docs)documents.delete(EVENTS,eventKey(streamId,((Number)doc.getOrDefault("seq",0)).longValue()));}}
    private Job findByStreamId(String streamId){for(int page=1;;page++){List<Map<String,Object>> docs=documents.findAll(JOBS,page,PAGE);for(Map<String,Object> doc:docs)if(streamId.equals(String.valueOf(doc.get("streamId"))))return read(doc);if(docs.size()<PAGE)return null;}}
    private void save(Job job){documents.save(JOBS,job.jobId(),map(job));}
    private Map<String,Object> map(Job job){Map<String,Object>m=new LinkedHashMap<>();m.put("jobId",job.jobId());m.put("version",job.version());m.put("phase",job.phase());m.put("done",job.done());m.put("total",job.total());m.put("status",job.status());m.put("error",job.error());m.put("createdAt",job.createdAt());m.put("updatedAt",job.updatedAt());m.put("streamId",job.streamId());return m;}
    private Job read(Map<String,Object> m){return new Job(String.valueOf(m.get("jobId")),String.valueOf(m.get("version")),String.valueOf(m.get("phase")),((Number)m.getOrDefault("done",0)).longValue(),((Number)m.getOrDefault("total",0)).longValue(),String.valueOf(m.get("status")),(String)m.get("error"),((Number)m.getOrDefault("createdAt",0)).longValue(),((Number)m.getOrDefault("updatedAt",0)).longValue(),String.valueOf(m.get("streamId")));}
    private String safeMessage(Exception ex){String message=ex.getMessage();return message==null||message.isBlank()?ex.getClass().getSimpleName():message;}
    /** 历史任务的事件流重放：把持久化事件按序推给订阅者后立即结束。 */
    private static final class ReplayStream implements PluginSseStream {
        private final List<JobLogBus.Event> events;
        private ReplayStream(List<JobLogBus.Event> events){this.events=events;}
        @Override public void subscribe(Subscriber subscriber){for(JobLogBus.Event event:events)subscriber.send(event.type(),event.payload());subscriber.complete();}
        @Override public void unsubscribe(Subscriber subscriber){}
    }
    private static final class Cancelled extends RuntimeException{}
}

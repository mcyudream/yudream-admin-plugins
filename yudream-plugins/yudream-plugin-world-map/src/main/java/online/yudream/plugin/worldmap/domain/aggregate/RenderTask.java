package online.yudream.plugin.worldmap.domain.aggregate;

import online.yudream.plugin.worldmap.domain.enumerate.TaskState;

/**
 * 渲染任务聚合根。
 */
public class RenderTask {

    private final String id;
    private final String mapId;
    private TaskState state;
    private int totalTiles;
    private int doneTiles;
    private String message;
    private long createdAt;
    private long startedAt;
    private long finishedAt;
    private String error;

    public RenderTask(String id, String mapId) {
        this.id = id;
        this.mapId = mapId;
        this.state = TaskState.PENDING;
        this.createdAt = System.currentTimeMillis();
    }

    public void start(int totalTiles) {
        if (isTerminal()) {
            return;
        }
        this.state = TaskState.RUNNING;
        this.totalTiles = totalTiles;
        this.doneTiles = 0;
        this.startedAt = System.currentTimeMillis();
    }

    public void advance(int done, int total, String message) {
        this.doneTiles = done;
        if (total > 0) {
            this.totalTiles = total;
        }
        this.message = message;
    }

    public void succeed() {
        if (isTerminal()) {
            return;
        }
        this.state = TaskState.SUCCESS;
        this.finishedAt = System.currentTimeMillis();
    }

    public void fail(String error) {
        if (isTerminal()) {
            return;
        }
        this.state = TaskState.FAILED;
        this.error = error;
        this.finishedAt = System.currentTimeMillis();
    }

    public void cancel(String message) {
        if (isTerminal()) {
            return;
        }
        this.state = TaskState.CANCELLED;
        this.message = message;
        this.error = message;
        this.finishedAt = System.currentTimeMillis();
    }

    public boolean isTerminal() {
        return state == TaskState.SUCCESS || state == TaskState.CANCELLED || state == TaskState.FAILED;
    }

    public String getId() { return id; }
    public String getMapId() { return mapId; }
    public TaskState getState() { return state; }
    public void setState(TaskState state) { this.state = state; }
    public int getTotalTiles() { return totalTiles; }
    public void setTotalTiles(int totalTiles) { this.totalTiles = totalTiles; }
    public int getDoneTiles() { return doneTiles; }
    public void setDoneTiles(int doneTiles) { this.doneTiles = doneTiles; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
    public long getCreatedAt() { return createdAt; }
    public void setCreatedAt(long createdAt) { this.createdAt = createdAt; }
    public long getStartedAt() { return startedAt; }
    public void setStartedAt(long startedAt) { this.startedAt = startedAt; }
    public long getFinishedAt() { return finishedAt; }
    public void setFinishedAt(long finishedAt) { this.finishedAt = finishedAt; }
    public String getError() { return error; }
    public void setError(String error) { this.error = error; }
}

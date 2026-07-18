package online.yudream.plugin.worldmap.domain.enumerate;

/** Durable render phases with a weighted contribution to overall progress. */
public enum RenderPhase {
    IMPORT(0, 5),
    EXTRACT(5, 15),
    ASSETS(15, 25),
    HIRES(25, 80),
    LOWRES(80, 95),
    PUBLISH(95, 100);

    private final int startPercent;
    private final int endPercent;

    RenderPhase(int startPercent, int endPercent) {
        this.startPercent = startPercent;
        this.endPercent = endPercent;
    }

    public int progressAt(int phasePercent) {
        int bounded = Math.max(0, Math.min(100, phasePercent));
        return startPercent + Math.round((endPercent - startPercent) * bounded / 100f);
    }
}

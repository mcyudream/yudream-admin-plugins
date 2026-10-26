package online.yudream.ysm.model;

/** Retain locomotion over short gaps between client movement packets. */
public final class MotionState {
    static final int HOLD_TICKS = 8;
    private long movingUntil = Long.MIN_VALUE;
    private String locomotion = "walk";
    private int airborneTicks;

    public String update(long tick, double horizontalDistance, boolean grounded, boolean flying, boolean sprinting, boolean reset) {
        if (reset) {
            movingUntil = Long.MIN_VALUE;
            airborneTicks = 0;
            return "idle";
        }
        airborneTicks = !grounded && !flying ? airborneTicks + 1 : 0;
        if (horizontalDistance > 0.012) {
            movingUntil = tick + HOLD_TICKS;
            locomotion = sprinting ? "run" : "walk";
        }
        if (airborneTicks >= 2) return "jump";
        return tick <= movingUntil ? locomotion : "idle";
    }
}

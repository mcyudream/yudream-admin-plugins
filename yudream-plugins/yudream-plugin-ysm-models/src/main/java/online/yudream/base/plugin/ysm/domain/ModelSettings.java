package online.yudream.base.plugin.ysm.domain;

/** Upload policy applies to personal uploads, including replacement versions. */
public record ModelSettings(boolean uploadEnabled, boolean reviewRequired) {
    public static ModelSettings defaults() { return new ModelSettings(true, false); }
}

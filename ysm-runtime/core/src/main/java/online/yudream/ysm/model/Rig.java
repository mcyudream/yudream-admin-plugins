package online.yudream.ysm.model;

import com.google.gson.*;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.*;

/** Immutable, precompiled numeric animation data. No Bukkit or per-tick JSON parsing. */
public final class Rig {
    static final float UNIT = 1f / 16f;
    public final Bone[] bones;
    public final int[] partBones;
    public final int head;
    public final Map<String, Clip> clips = new LinkedHashMap<>();

    public Rig(JsonObject source) {
        Map<String, JsonObject> definitions = new LinkedHashMap<>();
        for (JsonElement value : source.getAsJsonArray("bones"))
            definitions.put(value.getAsJsonObject().get("name").getAsString(), value.getAsJsonObject());
        Map<String, Integer> indices = new LinkedHashMap<>();
        List<Bone> ordered = new ArrayList<>();
        for (String name : definitions.keySet()) compileBone(name, definitions, indices, ordered, new HashSet<>());
        bones = ordered.toArray(Bone[]::new);
        head = indices.getOrDefault("Head", -1);
        JsonArray parts = source.getAsJsonArray("parts");
        partBones = new int[parts.size()];
        for (int i = 0; i < parts.size(); i++) partBones[i] = indices.get(parts.get(i).getAsJsonObject().get("bone").getAsString());
        for (var entry : source.getAsJsonObject("animations").entrySet()) {
            JsonObject animation = entry.getValue().getAsJsonObject();
            JsonObject channels = animation.getAsJsonObject("bones");
            Channels[] compiled = new Channels[bones.length];
            for (int i = 0; i < bones.length; i++) {
                JsonObject channel = channels != null && channels.has(bones[i].name) ? channels.getAsJsonObject(bones[i].name) : new JsonObject();
                compiled[i] = new Channels(new Track(channel.get("position"), 0),
                        new Track(channel.get("rotation"), 0), new Track(channel.get("scale"), 1));
            }
            clips.put(entry.getKey(), new Clip(animation.get("animation_length").getAsDouble(),
                    animation.has("loop") && animation.get("loop").getAsBoolean(), compiled));
        }
    }

    private static int compileBone(String name, Map<String, JsonObject> definitions, Map<String, Integer> indices,
                                   List<Bone> ordered, Set<String> visiting) {
        if (indices.containsKey(name)) return indices.get(name);
        if (!visiting.add(name)) throw new IllegalArgumentException("Cyclic bone hierarchy: " + name);
        JsonObject bone = Objects.requireNonNull(definitions.get(name), "Missing bone " + name);
        String parentName = bone.has("parent") ? bone.get("parent").getAsString() : null;
        int parent = parentName == null ? -1 : compileBone(parentName, definitions, indices, ordered, visiting);
        Vector3f pivot = bedrock(vector(bone.get("pivot"), 0));
        Vector3f parentPivot = parent < 0 ? new Vector3f() : ordered.get(parent).pivot;
        int index = ordered.size();
        ordered.add(new Bone(name, parent, pivot, new Vector3f(pivot).sub(parentPivot).mul(UNIT), vector(bone.get("rotation"), 0)));
        indices.put(name, index);
        visiting.remove(name);
        return index;
    }

    public Evaluation evaluate(String name, double at, Matrix4f basis, LocalPose[] from, float blend) {
        Clip clip = clips.get(name);
        LocalPose[] local = new LocalPose[bones.length];
        Matrix4f[] world = new Matrix4f[bones.length];
        for (int i = 0; i < bones.length; i++) {
            Bone bone = bones[i];
            Channels channel = clip == null ? BIND : clip.channels[i];
            Vector3f position = bedrock(channel.position.sample(at)).mul(UNIT).add(bone.offset);
            Quaternionf rotation = rotation(channel.rotation.sample(at).add(bone.rotation));
            Vector3f scale = channel.scale.sample(at);
            if (from != null && blend < 1) {
                position = new Vector3f(from[i].position).lerp(position, blend);
                rotation = new Quaternionf(from[i].rotation).slerp(rotation, blend);
                scale = new Vector3f(from[i].scale).lerp(scale, blend);
            }
            local[i] = new LocalPose(position, rotation, scale);
            world[i] = new Matrix4f(bone.parent < 0 ? basis : world[bone.parent])
                    .translate(position).rotate(rotation).scale(scale);
        }
        return new Evaluation(local, world);
    }

    private static final Channels BIND = new Channels(new Track(null, 0), new Track(null, 0), new Track(null, 1));

    public String select(String preferred) {
        if (clips.containsKey(preferred)) return preferred;
        if (clips.containsKey("idle")) return "idle";
        return clips.keySet().stream().findFirst().orElse("");
    }

    public static Vector3f bedrock(Vector3f value) { value.x = -value.x; return value; }
    public static Quaternionf rotation(Vector3f angles) {
        return new Quaternionf().rotationZYX((float)Math.toRadians(angles.z),
                (float)Math.toRadians(-angles.y), (float)Math.toRadians(-angles.x));
    }
    public static Vector3f vector(JsonElement value, float fallback) {
        if (value == null || value.isJsonNull()) return new Vector3f(fallback);
        if (value.isJsonPrimitive()) return new Vector3f(value.getAsFloat());
        JsonArray a = value.getAsJsonArray();
        return new Vector3f(a.get(0).getAsFloat(), a.get(1).getAsFloat(), a.get(2).getAsFloat());
    }
    private static Vector3f frame(JsonElement value, String side, float fallback) {
        if (value.isJsonObject()) {
            JsonObject object = value.getAsJsonObject();
            if (object.has(side)) return vector(object.get(side), fallback);
            if (object.has("post")) return vector(object.get("post"), fallback);
            if (object.has("vector")) return vector(object.get("vector"), fallback);
            throw new IllegalArgumentException("Unsupported keyframe " + object.keySet());
        }
        return vector(value, fallback);
    }

    public static final class Track {
        final double[] times;
        final Vector3f[] pre, post;
        final String[] interpolation;
        Track(JsonElement value, float fallback) {
            if (value == null || !value.isJsonObject()) {
                times = new double[]{0};
                pre = new Vector3f[]{vector(value, fallback)};
                post = pre;
                interpolation = new String[]{"linear"};
                return;
            }
            TreeMap<Double, JsonElement> sorted = new TreeMap<>();
            value.getAsJsonObject().entrySet().forEach(e -> sorted.put(Double.parseDouble(e.getKey()), e.getValue()));
            if (sorted.isEmpty()) throw new IllegalArgumentException("Empty keyframe track");
            times = new double[sorted.size()]; pre = new Vector3f[times.length]; post = new Vector3f[times.length]; interpolation = new String[times.length];
            int i = 0;
            for (var entry : sorted.entrySet()) {
                times[i] = entry.getKey();
                pre[i] = frame(entry.getValue(), "pre", fallback);
                post[i] = frame(entry.getValue(), "post", fallback);
                interpolation[i] = entry.getValue().isJsonObject() && entry.getValue().getAsJsonObject().has("lerp_mode") ? entry.getValue().getAsJsonObject().get("lerp_mode").getAsString() : "linear";
                i++;
            }
        }
        Vector3f sample(double time) {
            int index = Arrays.binarySearch(times, time);
            if (index >= 0) return new Vector3f(post[index]);
            int high = -index - 1;
            if (high == 0) return new Vector3f(pre[0]);
            if (high == times.length) return new Vector3f(post[times.length - 1]);
            int low = high - 1;
            float t=(float)((time-times[low])/(times[high]-times[low]));
            if (interpolation[low].equals("step")) return new Vector3f(post[low]);
            if (interpolation[low].equals("catmullrom")) {
                Vector3f p0=post[Math.max(0,low-1)],p1=post[low],p2=pre[high],p3=post[Math.min(post.length-1,high+1)];
                return new Vector3f(p1).mul(2*t*t*t-3*t*t+1).add(new Vector3f(p2).mul(-2*t*t*t+3*t*t))
                    .add(new Vector3f(p2).sub(p0).mul(.5f*(t*t*t-2*t*t+t)))
                    .add(new Vector3f(p3).sub(p1).mul(.5f*(t*t*t-t*t)));
            }
            return new Vector3f(post[low]).lerp(pre[high],t);
        }
    }
    public record Bone(String name, int parent, Vector3f pivot, Vector3f offset, Vector3f rotation) {}
    public record Channels(Track position, Track rotation, Track scale) {}
    public record Clip(double length, boolean loop, Channels[] channels) {
        public double at(double time, boolean frozen) { return !frozen && loop ? time % length : Math.min(time, length); }
    }
    public record LocalPose(Vector3f position, Quaternionf rotation, Vector3f scale) {}
    public record Evaluation(LocalPose[] local, Matrix4f[] world) {}
}

package online.yudream.ysm.model;

import com.google.gson.*;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.*;

/** Immutable tracks and bounded expression programs. No per-tick JSON parsing. */
public final class Rig {
    static final float UNIT = 1f / 16f;
    public final Bone[] bones;
    public final int[] partBones;
    public final int head;
    public final Vector3f modelScale;
    private final List<String> layers=new ArrayList<>();
    public final Map<String, Clip> clips = new LinkedHashMap<>();
    private static final Track ZERO=new Track(null,0), ONE=new Track(null,1);
    private static final Channels BIND=new Channels(ZERO,ZERO,ONE);

    public Rig(JsonObject source) {
        modelScale=vector(source.get("modelScale"),1);
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
        if(source.has("layers"))source.getAsJsonArray("layers").forEach(e->layers.add(e.getAsString()));
        for (var entry : source.getAsJsonObject("animations").entrySet()) {
            JsonObject animation = entry.getValue().getAsJsonObject();
            JsonObject channels = animation.getAsJsonObject("bones");
            Channels[] compiled = new Channels[bones.length];
            for (int i = 0; i < bones.length; i++) {
                JsonObject channel = channels != null && channels.has(bones[i].name) ? channels.getAsJsonObject(bones[i].name) : new JsonObject();
                compiled[i] = channel.isEmpty()?BIND:new Channels(channel.has("position")?new Track(channel.get("position"),0):ZERO,
                        channel.has("rotation")?new Track(channel.get("rotation"),0):ZERO,channel.has("scale")?new Track(channel.get("scale"),1):ONE);
            }
            clips.put(entry.getKey(), new Clip(animation.get("animation_length").getAsDouble(),
                    animation.has("loop") && animation.get("loop").getAsJsonPrimitive().isBoolean() && animation.get("loop").getAsBoolean(), compiled,
                    animation.has("initializers")?animation.getAsJsonArray("initializers").asList().stream().map(Molang::compile).toList():List.of()));
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
        List<Clip> active=new ArrayList<>();
        for(String layer:layers)if(layer.startsWith("pre_")&&!layer.equals(name)&&clips.containsKey(layer))active.add(clips.get(layer));
        if(clips.containsKey(name))active.add(clips.get(name));
        for(String layer:layers)if(!layer.startsWith("pre_")&&!layer.equals(name)&&clips.containsKey(layer))active.add(clips.get(layer));
        Molang.Context context=new Molang.Context(at);
        for(Clip clip:active)for(Molang.Expr initial:clip.initializers)initial.eval(context);
        Matrix4f rootBasis=new Matrix4f(basis).scale(modelScale);
        LocalPose[] local = new LocalPose[bones.length];
        Matrix4f[] world = new Matrix4f[bones.length];
        for (int i = 0; i < bones.length; i++) {
            Bone bone = bones[i];
            Vector3f position=new Vector3f(),angles=new Vector3f(),scale=new Vector3f(1);
            for(Clip clip:active){
                context.time=clip.at(at,false);Channels channel=clip.channels[i];
                if(channel.position!=ZERO)position.add(channel.position.sample(context));
                if(channel.rotation!=ZERO)angles.add(channel.rotation.sample(context));
                if(channel.scale!=ONE)scale.mul(channel.scale.sample(context));
            }
            bedrock(position).mul(UNIT).add(bone.offset);
            Quaternionf rotation = rotation(angles.add(bone.rotation));
            if (from != null && blend < 1) {
                position = new Vector3f(from[i].position).lerp(position, blend);
                rotation = new Quaternionf(from[i].rotation).slerp(rotation, blend);
                scale = new Vector3f(from[i].scale).lerp(scale, blend);
            }
            local[i] = new LocalPose(position, rotation, scale);
            world[i] = new Matrix4f(bone.parent < 0 ? rootBasis : world[bone.parent])
                    .translate(position).rotate(rotation).scale(scale);
        }
        return new Evaluation(local, world);
    }

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
    private record Value(Molang.Expr x,Molang.Expr y,Molang.Expr z){
        static Value of(JsonElement value,float fallback){
            if(value==null||value.isJsonNull())value=new JsonPrimitive(fallback);
            if(value.isJsonArray()){var a=value.getAsJsonArray();return new Value(Molang.compile(a.get(0)),Molang.compile(a.get(1)),Molang.compile(a.get(2)));}
            var scalar=Molang.compile(value);return new Value(scalar,scalar,scalar);
        }
        Vector3f eval(Molang.Context context){return new Vector3f((float)x.eval(context),(float)y.eval(context),(float)z.eval(context));}
    }
    private static Value expressionFrame(JsonElement value,String side,float fallback){
        if(value.isJsonObject()&&!value.getAsJsonObject().has("expr")){var o=value.getAsJsonObject();return Value.of(o.has(side)?o.get(side):o.has("post")?o.get("post"):o.get("vector"),fallback);}
        return Value.of(value,fallback);
    }
    public static final class Track {
        final double[] times;
        final Value[] pre, post;
        final String[] interpolation;
        Track(JsonElement value, float fallback) {
            if (value == null || !value.isJsonObject() || value.getAsJsonObject().has("expr")) {
                times = new double[]{0};
                pre = new Value[]{Value.of(value, fallback)};
                post = pre;
                interpolation = new String[]{"linear"};
                return;
            }
            TreeMap<Double, JsonElement> sorted = new TreeMap<>();
            value.getAsJsonObject().entrySet().forEach(e -> sorted.put(Double.parseDouble(e.getKey()), e.getValue()));
            if (sorted.isEmpty()) throw new IllegalArgumentException("Empty keyframe track");
            times = new double[sorted.size()]; pre = new Value[times.length]; post = new Value[times.length]; interpolation = new String[times.length];
            int i = 0;
            for (var entry : sorted.entrySet()) {
                times[i] = entry.getKey();
                pre[i] = expressionFrame(entry.getValue(), "pre", fallback);
                post[i] = expressionFrame(entry.getValue(), "post", fallback);
                interpolation[i] = entry.getValue().isJsonObject() && entry.getValue().getAsJsonObject().has("lerp_mode") ? entry.getValue().getAsJsonObject().get("lerp_mode").getAsString() : "linear";
                i++;
            }
        }
        Vector3f sample(Molang.Context context) {
            double time=context.time;
            int index = Arrays.binarySearch(times, time);
            if (index >= 0) return post[index].eval(context);
            int high = -index - 1;
            if (high == 0) return pre[0].eval(context);
            if (high == times.length) return post[times.length - 1].eval(context);
            int low = high - 1;
            float t=(float)((time-times[low])/(times[high]-times[low]));
            if (interpolation[low].equals("step")) return post[low].eval(context);
            if (interpolation[low].equals("catmullrom")) {
                Vector3f p0=post[Math.max(0,low-1)].eval(context),p1=post[low].eval(context),p2=pre[high].eval(context),p3=post[Math.min(post.length-1,high+1)].eval(context);
                return new Vector3f(p1).mul(2*t*t*t-3*t*t+1).add(new Vector3f(p2).mul(-2*t*t*t+3*t*t))
                    .add(new Vector3f(p2).sub(p0).mul(.5f*(t*t*t-2*t*t+t)))
                    .add(new Vector3f(p3).sub(p1).mul(.5f*(t*t*t-t*t)));
            }
            return post[low].eval(context).lerp(pre[high].eval(context),t);
        }
    }
    public record Bone(String name, int parent, Vector3f pivot, Vector3f offset, Vector3f rotation) {}
    public record Channels(Track position, Track rotation, Track scale) {}
    public record Clip(double length, boolean loop, Channels[] channels, List<Molang.Expr> initializers) {
        public double at(double time, boolean frozen) { return !frozen && loop ? time % length : Math.min(time, length); }
    }
    public record LocalPose(Vector3f position, Quaternionf rotation, Vector3f scale) {}
    public record Evaluation(LocalPose[] local, Matrix4f[] world) {}
}

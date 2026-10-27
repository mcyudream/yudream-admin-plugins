package online.yudream.base.plugin.ysm.infrastructure;
import online.yudream.ysm.model.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import java.nio.file.*;
import java.time.Duration;
import static org.junit.jupiter.api.Assertions.*;
class YsmDecoderTest {
    @Test void rejectsMalformedInputAndReleasesWorker(){
        try(var decoder=new YsmDecoder()){
            assertThrows(IllegalArgumentException.class,()->decoder.decode(new byte[0]));
            assertTimeoutPreemptively(Duration.ofSeconds(20),()->{
                for(int n=0;n<2;n++)assertThrows(IllegalArgumentException.class,()->decoder.decode(new byte[]{1,2,3,4,5}));
            });
        }
    }
    /** Optional private fixture, never checked into Git or copied to the distributed JAR. */
    @Test @EnabledIfSystemProperty(named="ysm.sample",matches=".+")
    void originalYsmDecodesCompilesAndSamples() throws Exception {
        try(var decoder=new YsmDecoder()){
            var files=decoder.decode(Files.readAllBytes(Path.of(System.getProperty("ysm.sample"))));
            assertTrue(files.containsKey("ysm.json"));assertTrue(files.containsKey("models/main.json"));
            var asset=new ModelCompiler().compile(files,"本地兼容验证");ModelCompiler.validateBundle(asset.data());
            var rig=new Rig(asset.data());assertTrue(rig.bones.length>128);assertTrue(Geometry.mesh(asset).size()>1000);
            for(var name:rig.clips.keySet())for(var m:rig.evaluate(name,.5,new org.joml.Matrix4f(),null,1).world())assertTrue(m.isFinite(),name);
        }
    }
}

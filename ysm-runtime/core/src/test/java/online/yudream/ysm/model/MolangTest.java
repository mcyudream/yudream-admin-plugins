package online.yudream.ysm.model;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class MolangTest {
    private double value(String text,double time){return Molang.compile(Molang.parse(text)).eval(new Molang.Context(time));}
    @Test void arithmeticDegreesDefaultsAndShortCircuit(){
        assertEquals(3,value("1+2*3-4/1",0));assertEquals(2,value("2*math.sin(q.anim_time*90)",1),1e-6);
        assertEquals(1,value("v.roaming.cloth==0&&v.coat==0?1:0",0));assertEquals(5,value("v.test=3;v.test+2",0));
        assertEquals(0,value("q.ground_speed>0?2",0));assertEquals(3,value("ysm.second_order('key',3,1,.8,0)",0));
        assertEquals(0,value("1/0",0));assertEquals(0,value("math.exp(1000000)",0));
    }
    @Test void boundedAndClosedGrammar(){
        for(String text:new String[]{"loop(999999999,{v.x=1;});", "java.lang.Runtime.exec('cmd')", "query.anim_time=4", "-*ysm.head_yaw", "(".repeat(50)+"1"+")".repeat(50)})assertThrows(IllegalArgumentException.class,()->Molang.parse(text));
    }
    @Test void layersApplyDefaultVariablesAndDoNotDuplicateSelectedLayer() throws Exception {
        var json=com.google.gson.JsonParser.parseString("""
         {"bones":[{"name":"Root"}],"parts":[],"layers":["parallel0"],"animations":{
          "idle":{"animation_length":2,"bones":{"Root":{"position":[0,16,0]}}},
          "parallel0":{"animation_length":2,"initializers":[],"bones":{"Root":{"scale":[0,0,0]}}}}}
         """).getAsJsonObject();
        json.add("modelScale",com.google.gson.JsonParser.parseString("[0.5,0.75,0.5]"));
        var rig=new Rig(json);assertEquals(0,rig.evaluate("idle",1,new org.joml.Matrix4f(),null,1).local()[0].scale().x);
        assertEquals(.75f,rig.evaluate("idle",1,new org.joml.Matrix4f(),null,1).world()[0].m31());
        assertEquals(0,rig.evaluate("parallel0",1,new org.joml.Matrix4f(),null,1).local()[0].scale().x);
    }
}

package online.yudream.ysm.model;

import java.nio.file.*;

/** Offline conversion utility. Inputs and outputs use the same schema as the website API. */
public final class ModelTool {
    public static void main(String[] args) throws Exception {
        if (args.length < 2 || args.length > 3) throw new IllegalArgumentException("用法：ModelTool source.zip bundle.json [模型名称]");
        Path input=Path.of(args[0]), output=Path.of(args[1]);
        if (Files.size(input)>ModelCompiler.MAX_UPLOAD) throw new IllegalArgumentException("模型 ZIP 超过 16 MiB");
        var asset=new ModelCompiler().compile(Files.readAllBytes(input),args.length==3?args[2]:input.getFileName().toString());
        Files.write(output,asset.bytes());
        System.out.println("schema=1 sha256="+asset.hash()+" parts="+new Rig(asset.data()).partBones.length);
    }
}

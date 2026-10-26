package online.yudream.ysm.fabric26_3.mixin;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;
@Mixin(AvatarRenderer.class)
public interface AvatarNames {
    @Invoker("submitNameDisplay") void ysm$name(AvatarRenderState state,PoseStack stack,SubmitNodeCollector collector,CameraRenderState camera);
}

package online.yudream.ysm.folia;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.joml.Matrix4f;
import java.util.*;

/** Only this port and its adapter know how a game version encodes virtual displays and player flags. */
public interface PacketPort {
    interface Connection extends AutoCloseable {
        void send(Collection<Object> packets);
        void mask(int entityId,boolean hidden,byte originalFlags,Object equipment);
        void close();
    }
    interface Part {
        int id();
        void transform(Location at,Matrix4f matrix);
        List<Object> spawn();
        List<Object> update();
    }
    Connection bind(Player player);
    Part part(Player target,ItemStack item,Location at,Matrix4f matrix);
    Object remove(int[] ids);
    Object payload(byte[] bytes);
    Object equipment(Player player,Map<org.bukkit.inventory.EquipmentSlot,ItemStack> equipment);
}

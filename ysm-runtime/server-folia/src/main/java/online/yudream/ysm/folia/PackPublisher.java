package online.yudream.ysm.folia;

import org.bukkit.entity.Player;
import java.nio.file.Path;

/** Download transport only; the caller owns each immutable snapshot file. */
interface PackPublisher extends AutoCloseable {
    default boolean ready() { return true; }
    Download publish(Path file, String sha1) throws Exception;
    @Override void close();
    interface Download { String url(Player player) throws Exception; }
}

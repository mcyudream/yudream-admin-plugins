package online.yudream.base.plugin.mcpanel.infrastructure.dns;

import java.util.List;

/**
 * 云解析驱动：只做协议适配（记录增删查），命名规则、TTL 收敛、分配/释放策略
 * 由应用层 {@code DomainService} 负责——换服务商不影响业务语义。
 *
 * <p>约定：{@code name} 一律是**完整记录名**（如 {@code mc.example.com}、
 * {@code _minecraft._tcp.mc.example.com}），相对名（阿里云 RR / 腾讯云 SubDomain）
 * 由各实现按自己的区域根域名换算（见 {@link DnsNames#relative}）。
 *
 * <p>实现不得吞掉云商错误：一律抛 {@link DnsCallException}（含可读中文信息），
 * 由应用层转成业务错误码返回前端。
 */
public interface DnsProvider {

    /** 驱动标识：cloudflare / aliyun / dnspod。 */
    String type();

    /** TTL 下限（秒）：低于该值的配置会被收敛，避免云商直接拒绝。 */
    int minTtlSeconds();

    /** 幂等写入：同名同类型记录存在则更新其值，不存在则新建。 */
    void upsert(String name, String type, String value, int ttlSeconds);

    /** 删除同名同类型全部记录（不存在则静默返回）。 */
    void delete(String name, String type);

    /** 查询同名同类型记录值（校验解析是否生效）。 */
    List<String> values(String name, String type);
}

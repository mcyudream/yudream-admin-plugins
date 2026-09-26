package online.yudream.base.plugin.ymcl.api;

/**
 * P2P 业务失败（YAP §6.12）：带机器码，适配器原样透给启动器，启动器据此给玩家可读提示。
 *
 * <p>code 约定见 {@link YmclP2pProvider#open}；提供方不得用通用异常代替业务码，
 * 否则启动器无法区分「面板未开 P2P」与「这个实例不允许你连」。
 */
public class YmclP2pException extends RuntimeException {

    private final String code;

    public YmclP2pException(String code, String message) {
        super(message);
        this.code = code == null || code.isBlank() ? "p2p.failed" : code;
    }

    public String code() {
        return code;
    }
}

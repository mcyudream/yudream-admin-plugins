package online.yudream.base.plugin.mcpanel.infrastructure.node;

/** 节点调用失败（err 帧或本地前置校验），携带协议错误码。 */
public class NodeCallException extends RuntimeException {

 private final String code;

 public NodeCallException(String code, String message) {
  super(message == null || message.isBlank() ? code : message);
  this.code = code;
 }

 public NodeCallException(String code, String message, Throwable cause) {
  super(message == null || message.isBlank() ? code : message, cause);
  this.code = code;
 }

 public String code() {
  return code;
 }

 /** 出站写失败统一归类为 node.offline：只保留错误类别，不透出底层异常细节。 */
 static NodeCallException wrapSendFailure(Throwable cause) {
  if (cause instanceof NodeCallException nodeCall) {
   return nodeCall;
  }
  return new NodeCallException("node.offline", "发送失败", cause);
 }
}

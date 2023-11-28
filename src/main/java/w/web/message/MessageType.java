package w.web.message;

/**
 * @author Frank
 * @date 2023/11/25 21:58
 */
public enum MessageType {
    /**
     * 修改方法的body的消息
     */
    CHANGE_BODY,

    WATCH,

    EXEC,

    /**
     * 通信的心跳類型
     */
    PING,

    PONG,

    LOG,

}

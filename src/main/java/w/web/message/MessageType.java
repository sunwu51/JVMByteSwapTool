package w.web.message;

/**
 * Message Type Enum
 *
 * @author Frank
 * @date 2023/11/25 21:58
 */
public enum MessageType {
    /**
     * 修改方法的body的消息
     */
    CHANGE_BODY,
    CHANGE_RESULT,

    WATCH,

    OUTER_WATCH,

    EXEC,

    REPLACE_CLASS,

    PING,

    PONG,

    LOG,

    DELETE,

    TRACE,

    DECOMPILE,

    RESET,

    EVAL,

}

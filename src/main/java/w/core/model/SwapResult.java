package w.core.model;

import lombok.Data;
import w.web.message.Message;
import w.web.message.MessageType;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Data
public class SwapResult {
    private boolean success;
    private String message;
    private String requestId;
    private MessageType type;
    private UUID transformerId;
    private List<TransformApplyResult> applyResults = new ArrayList<>();

    public static SwapResult failure(Message request, String message) {
        SwapResult result = new SwapResult();
        result.setSuccess(false);
        result.setMessage(message);
        result.fillRequest(request);
        return result;
    }

    public static SwapResult failure(Message request, String message, Throwable e) {
        SwapResult result = failure(request, message + ": " + e.getMessage());
        return result;
    }

    public static SwapResult of(Message request, BaseClassTransformer transformer, List<TransformApplyResult> applyResults) {
        SwapResult result = new SwapResult();
        result.fillRequest(request);
        result.setTransformerId(transformer.getUuid());
        result.setApplyResults(applyResults);
        result.setSuccess(!applyResults.isEmpty() && applyResults.stream().allMatch(TransformApplyResult::isSuccess));
        result.setMessage(result.isSuccess() ? "retransform success" : "retransform failed");
        return result;
    }

    private void fillRequest(Message request) {
        if (request != null) {
            this.requestId = request.getId();
            this.type = request.getType();
        }
    }
}

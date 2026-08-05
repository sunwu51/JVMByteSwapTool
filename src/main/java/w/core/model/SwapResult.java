package w.core.model;

import lombok.Data;
import w.web.message.Message;
import w.web.message.MessageType;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Data
public class SwapResult {
    private boolean success;
    private String message;
    private String requestId;
    private MessageType type;
    private UUID transformerId;
    private List<TransformApplyResult> applyResults = new ArrayList<>();
    private List<Map<String, Object>> candidates = new ArrayList<>();
    private Object data;

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

    public static SwapResult failure(Message request, String message, List<Map<String, Object>> candidates) {
        SwapResult result = failure(request, message);
        result.setCandidates(candidates);
        return result;
    }

    public static SwapResult of(Message request, BaseClassTransformer transformer, List<TransformApplyResult> applyResults) {
        SwapResult result = new SwapResult();
        result.fillRequest(request);
        result.setTransformerId(transformer.getUuid());
        result.setApplyResults(applyResults);
        result.setSuccess(!applyResults.isEmpty() && applyResults.stream().allMatch(TransformApplyResult::isSuccess));
        if (result.isSuccess()) {
            result.setMessage("retransform success");
        } else {
            String reason = "retransform failed";
            for (TransformApplyResult applyResult : applyResults) {
                if (!applyResult.isSuccess() && applyResult.getMessage() != null) {
                    reason = applyResult.getMessage();
                    break;
                }
            }
            result.setMessage(reason);
        }
        return result;
    }

    private void fillRequest(Message request) {
        if (request != null) {
            this.requestId = request.getId();
            this.type = request.getType();
        }
    }
}

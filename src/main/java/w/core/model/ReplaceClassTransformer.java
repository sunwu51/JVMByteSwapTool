package w.core.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;
import w.Global;
import w.web.message.ReplaceClassMessage;

import java.io.IOException;
import java.util.Base64;

/**
 * @author Frank
 * @date 2023/12/21 13:46
 */
@Data
public class ReplaceClassTransformer extends BaseClassTransformer {

    @JsonIgnore
    transient ReplaceClassMessage message;

    byte[] content;

    public ReplaceClassTransformer(ReplaceClassMessage message) throws IOException {
        this.className = message.getClassName();
        this.message = message;
        this.content = Base64.getDecoder().decode(message.getContent());;
        this.traceId = message.getId();
    }

    @Override
    public byte[] transform(Class<?> className, byte[] origin) throws Exception {
        status = 1;
        return content;
    }

    public boolean equals(Object other) {
        if (other instanceof ReplaceClassTransformer) {
            return this.uuid.equals(((ReplaceClassTransformer) other).getUuid());
        }
        return false;
    }
    @Override
    public String desc() {
        return "ReplaceClass_" + getClassName();
    }
}

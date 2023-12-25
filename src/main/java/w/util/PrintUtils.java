package w.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import lombok.Getter;
import m3.prettyobject.PrettyFormat;
import m3.prettyobject.PrettyFormatRegistry;

/**
 * @author Frank
 * @date 2023/12/21 11:19
 */
public class PrintUtils {
    @Getter
    final static ObjectMapper objectMapper = new ObjectMapper();
    @Getter
    final static PrettyFormat prettyFormat = new PrettyFormat(PrettyFormatRegistry.createDefaultInstance());
}

package fan.summer.fengyu.sdk;

import org.junit.jupiter.api.Test;
import java.lang.reflect.RecordComponent;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;

/** Locks the {@link FileRef} record shape to the cross-language browser SDK contract. */
class FileRefContractTest {
    @Test void recordFieldsMatchBrowserSdkContract() {
        assertArrayEquals(new String[]{"id", "name", "kind", "access", "size"},
            java.util.Arrays.stream(FileRef.class.getRecordComponents())
                .map(RecordComponent::getName).toArray(String[]::new));
    }
}

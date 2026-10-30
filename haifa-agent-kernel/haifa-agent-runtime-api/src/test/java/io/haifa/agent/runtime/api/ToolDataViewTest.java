package io.haifa.agent.runtime.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ToolDataViewTest {
    @Test
    void completenessMetadataIsImmutableAndCannotCertifyMissingFields() {
        var fields = new HashSet<>(Set.of("path"));
        var view = new ToolDataView(Map.of("path", "/workspace/file"), true, fields);
        fields.clear();
        assertThat(view.completeFields()).containsExactly("path");
        assertThatThrownBy(() -> view.completeFields().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> new ToolDataView(Map.of(), true, Set.of("path")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void legacyOrMissingMetadataDoesNotCertifyTruncatedFields() {
        var values = Map.<String, Object>of("path", "preview");
        assertThat(new ToolDataView(values, true).completeFields()).isEmpty();
        assertThat(new ToolDataView(values, true, null).completeFields()).isEmpty();
        assertThat(new ToolDataView(values, false).completeFields()).containsExactly("path");
    }
}

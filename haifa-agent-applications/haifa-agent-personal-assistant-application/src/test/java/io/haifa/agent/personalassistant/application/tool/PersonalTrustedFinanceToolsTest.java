package io.haifa.agent.personalassistant.application.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class PersonalTrustedFinanceToolsTest {
    @Test
    void acceptsOnlyLogicalWorkspaceXlsxPaths() {
        assertThat(PersonalTrustedFinanceTools.safeWorkbook("models/value.xlsx"))
                .isEqualTo("models/value.xlsx");
        assertThat(PersonalTrustedFinanceTools.safeWorkbook("models\\value.XLSX"))
                .isEqualTo("models/value.XLSX");

        for (String value : new String[] {
            "../value.xlsx",
            "models/../value.xlsx",
            "C:\\value.xlsx",
            "\\\\server\\share\\value.xlsx",
            "\\\\?\\C:\\value.xlsx",
            "/tmp/value.xlsx",
            "models/value.xls"
        }) {
            assertThatThrownBy(() -> PersonalTrustedFinanceTools.safeWorkbook(value))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Workspace-relative");
        }
    }
}

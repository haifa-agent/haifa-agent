package io.haifa.agent.store.sqlite.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class SqlScriptParserTest {
    @Test
    void preservesQuotedCommentAndTriggerSemicolons() {
        String script =
                """
                -- semicolon ; in a comment
                CREATE TABLE sample(id INTEGER PRIMARY KEY, value TEXT);
                INSERT INTO sample(value) VALUES ('a;''b');
                /* block ; comment */
                CREATE TRIGGER sample_insert AFTER INSERT ON sample
                BEGIN
                    SELECT CASE WHEN NEW.value = 'x;y' THEN 1 ELSE 0 END;
                    UPDATE sample SET value = "quoted;name" WHERE id = NEW.id;
                END;
                """;

        assertThat(SqlScriptParser.parse(script))
                .hasSize(3)
                .element(2)
                .asString()
                .contains("CREATE TRIGGER", "CASE", "UPDATE sample", "END");
    }

    @Test
    void rejectsUnterminatedInput() {
        assertThatThrownBy(() -> SqlScriptParser.parse("INSERT INTO sample VALUES ('unterminated);"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("unterminated");
    }
}

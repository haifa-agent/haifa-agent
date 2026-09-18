package io.haifa.agent.sdk.contribution;

import io.haifa.agent.common.time.TimeProvider;
import io.haifa.agent.runtime.core.skill.DefaultSkillActivationService;
import io.haifa.agent.runtime.core.skill.SkillToolCatalogContribution;
import io.haifa.agent.runtime.core.skill.SkillToolProvider;
import io.haifa.agent.sdk.spi.SdkPersistenceContribution;
import io.haifa.agent.skill.api.SkillContentLoader;
import java.util.List;
import java.util.Objects;

/** Public SDK assembly helper that routes Skill activation and resource reads through the Tool pipeline. */
public final class SkillToolContributions {
    private SkillToolContributions() {}

    public static List<SkillToolCatalogContribution> create(
            SdkPersistenceContribution persistence, SkillContentLoader contentLoader, TimeProvider time) {
        Objects.requireNonNull(persistence, "persistence must not be null");
        Objects.requireNonNull(contentLoader, "contentLoader must not be null");
        Objects.requireNonNull(time, "time must not be null");
        var ports = persistence.runtimePersistence();
        var service = new DefaultSkillActivationService(ports.runs(), ports.state(), contentLoader, time);
        return new SkillToolProvider(service).contributions();
    }
}

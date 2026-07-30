package io.haifa.agent.personalassistant.application;

import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.reference.TenantRef;
import io.haifa.agent.personalassistant.application.execution.PersonalExecutionPlatform;
import io.haifa.agent.personalassistant.application.mcp.PersonalMcpConfiguration;
import io.haifa.agent.personalassistant.application.mcp.PersonalMcpPlatform;
import io.haifa.agent.personalassistant.application.product.PersonalAssistantProfile;
import io.haifa.agent.personalassistant.application.skill.PersonalSkillPlatform;
import io.haifa.agent.personalassistant.application.tool.PersonalToolPlatform;
import io.haifa.agent.personalassistant.application.trust.PersonalTrustedScriptManifest;
import io.haifa.agent.personalassistant.application.web.PersonalWebPlatform;
import io.haifa.agent.sdk.api.HaifaAgents;
import io.haifa.agent.sdk.api.SdkCallerProvider;
import io.haifa.agent.sdk.contribution.MemoryPlatformContribution;
import io.haifa.agent.sdk.contribution.ModelContribution;
import io.haifa.agent.sdk.contribution.PolicyPlatformContribution;
import io.haifa.agent.sdk.spi.SdkConversationContribution;
import io.haifa.agent.sdk.spi.SdkPersistenceContribution;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Explicit Composition helper; no classpath scanning or Bean ordering participates in product assembly. */
public final class PersonalAssistantAssembler {
    private PersonalAssistantAssembler() {}

    public static PersonalAssistantApplication assemble(Dependencies dependencies) {
        Objects.requireNonNull(dependencies);
        PersonalTrustedScriptManifest trustManifest =
                PersonalTrustedScriptManifest.load(dependencies.trustedScriptManifest());
        var skills = PersonalSkillPlatform.create(
                dependencies.tenant(),
                dependencies.principal(),
                dependencies.localSkillRoot(),
                dependencies.protectedPaths(),
                trustManifest,
                dependencies.clock());
        PersonalMcpPlatform mcp = PersonalMcpPlatform.connect(
                dependencies.mcp(), dependencies.tenant(), dependencies.principal(), dependencies.clock());
        try {
            var tools = PersonalToolPlatform.create(
                    dependencies.persistence(),
                    skills,
                    mcp,
                    dependencies.web(),
                    dependencies.execution(),
                    dependencies.clock()::instant);
            var coordinates = new PersonalAssistantProfile.ContributionCoordinates(
                    dependencies.model().coordinate(),
                    dependencies.persistence().coordinate(),
                    dependencies.conversation().coordinate(),
                    dependencies.memory().coordinate(),
                    dependencies.policy().coordinate(),
                    tools.tool().coordinate(),
                    tools.skill().coordinate(),
                    tools.mcp().coordinate(),
                    dependencies.web().credential().coordinate(),
                    dependencies.execution().execution().coordinate(),
                    dependencies.execution().shell().coordinate(),
                    dependencies.execution().approval().coordinate());
            var profile = PersonalAssistantProfile.create(
                    coordinates,
                    skills.aliases(),
                    mcp.aliases(),
                    dependencies.web().aliases(),
                    tools.trustedScriptToolAliases());
            var agent = HaifaAgents.builder(profile)
                    .callerProvider(dependencies.callers())
                    .timeProvider(dependencies.clock()::instant)
                    .toolApprovalPrompts(dependencies.execution()::approvalPrompt)
                    .contribute(dependencies.model())
                    .contribute(dependencies.persistence())
                    .contribute(dependencies.conversation())
                    .contribute(dependencies.memory())
                    .contribute(dependencies.policy())
                    .contribute(tools.tool())
                    .contribute(tools.skill())
                    .contribute(tools.mcp())
                    .contribute(dependencies.web().credential())
                    .contribute(dependencies.execution().execution())
                    .contribute(dependencies.execution().shell())
                    .contribute(dependencies.execution().approval())
                    .build();
            return new PersonalAssistantApplication(
                    agent, mcp, dependencies.clock(), PersonalCapabilityRegistry.create(tools, mcp));
        } catch (RuntimeException | Error exception) {
            mcp.close();
            throw exception;
        }
    }

    public record Dependencies(
            TenantRef tenant,
            PrincipalRef principal,
            SdkCallerProvider callers,
            ModelContribution model,
            SdkPersistenceContribution persistence,
            SdkConversationContribution conversation,
            MemoryPlatformContribution memory,
            PolicyPlatformContribution policy,
            PersonalExecutionPlatform execution,
            PersonalWebPlatform web,
            PersonalMcpConfiguration mcp,
            Optional<Path> localSkillRoot,
            Optional<Path> trustedScriptManifest,
            List<Path> protectedPaths,
            Clock clock) {
        public Dependencies(
                TenantRef tenant,
                PrincipalRef principal,
                SdkCallerProvider callers,
                ModelContribution model,
                SdkPersistenceContribution persistence,
                SdkConversationContribution conversation,
                MemoryPlatformContribution memory,
                PolicyPlatformContribution policy,
                PersonalExecutionPlatform execution,
                PersonalWebPlatform web,
                PersonalMcpConfiguration mcp,
                Optional<Path> localSkillRoot,
                List<Path> protectedPaths,
                Clock clock) {
            this(
                    tenant,
                    principal,
                    callers,
                    model,
                    persistence,
                    conversation,
                    memory,
                    policy,
                    execution,
                    web,
                    mcp,
                    localSkillRoot,
                    Optional.empty(),
                    protectedPaths,
                    clock);
        }

        public Dependencies {
            Objects.requireNonNull(tenant);
            Objects.requireNonNull(principal);
            Objects.requireNonNull(callers);
            Objects.requireNonNull(model);
            Objects.requireNonNull(persistence);
            Objects.requireNonNull(conversation);
            Objects.requireNonNull(memory);
            Objects.requireNonNull(policy);
            Objects.requireNonNull(execution);
            Objects.requireNonNull(web);
            Objects.requireNonNull(mcp);
            localSkillRoot = Objects.requireNonNull(localSkillRoot);
            trustedScriptManifest = Objects.requireNonNull(trustedScriptManifest);
            protectedPaths = List.copyOf(protectedPaths);
            Objects.requireNonNull(clock);
        }
    }
}

package io.haifa.coding.host;

import io.haifa.agent.application.project.persistence.ProjectPersistenceConfiguration;
import io.haifa.coding.host.internal.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Starts a new product owner explicitly. Connecting to an existing Host belongs to the Client SDK. */
public final class CodingHosts {
    private CodingHosts() {}

    public static CodingHost start(HostOptions options) {
        return start(options, System.getenv());
    }

    public static CodingHost start(HostOptions options, Map<String, String> environment) {
        environment = Map.copyOf(environment);
        Objects.requireNonNull(options);
        CodingHostServer.loopback(options.bind());
        try {
            Path workspace = options.workspace().toRealPath();
            Files.createDirectories(options.dataDirectory());
            Path data = options.dataDirectory().toRealPath();
            if (options.browser().isPresent()) {
                Path assets = options.browser().orElseThrow().assetRoot().toRealPath();
                if (workspace.startsWith(assets)
                        || data.startsWith(assets)
                        || assets.startsWith(workspace)
                        || assets.startsWith(data)) throw new IllegalArgumentException("ASSET_ROOT_EXPOSES_OWNER_DATA");
            }
            var resolved = new CliConfigurationLoader(environment::get)
                    .loadHostConfiguration(ConfigurationInput.of(options.configuration()), workspace);
            var c = resolved.configuration();
            var previous = c.persistence();
            if (previous.mode() == io.haifa.agent.application.project.persistence.ProjectPersistenceMode.MEMORY)
                throw new IllegalStateException("HOST_DURABLE_PERSISTENCE_REQUIRED");
            var persistence = new ProjectPersistenceConfiguration(
                    previous.mode(),
                    previous.protection(),
                    Optional.of(data.resolve("coding.sqlite")),
                    previous.transcriptRoot(),
                    previous.protectorReference(),
                    previous.busyTimeoutMillis(),
                    previous.maximumPayloadBytes());
            var configuration = new CliConfiguration(
                    c.model(),
                    c.availableModels(),
                    c.enabledTools(),
                    c.mcpServers(),
                    c.web(),
                    c.skills(),
                    c.execution(),
                    c.approval(),
                    c.approvalThreshold(),
                    c.timeout(),
                    c.maxIterations(),
                    c.maxModelCalls(),
                    c.maxToolCalls(),
                    persistence,
                    c.modelMaxResponseBytes(),
                    c.abandonedRunStaleness(),
                    c.context());
            var agent = StandaloneCodingAgents.openConfigured(workspace, configuration, environment);
            try {
                return new CodingHost(CodingHostServer.start(agent, options, resolved.summary()));
            } catch (RuntimeException failure) {
                agent.close();
                throw failure;
            }
        } catch (io.haifa.agent.application.project.persistence.CodingDatabaseOwnershipException owned) {
            throw new io.haifa.coding.api.CodingClientException("DATABASE_OWNED_BY_ANOTHER_PROCESS", false);
        } catch (IOException unavailable) {
            throw new IllegalArgumentException("HOST_OWNER_PATH_UNAVAILABLE");
        }
    }
}

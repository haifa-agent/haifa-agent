package io.haifa.agent.runtime.core.checkpoint;

import io.haifa.agent.core.checkpoint.Checkpoint;
import io.haifa.agent.core.checkpoint.CheckpointType;
import io.haifa.agent.core.run.AgentRun;
import io.haifa.agent.runtime.core.storage.CheckpointRepository;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public final class CheckpointManager {
    private final CheckpointRepository repository;
    private final CheckpointPolicy policy;
    private final CheckpointSnapshotBuilder snapshotBuilder;
    private final ResumeCheckpointSelector selections;

    public CheckpointManager(
            CheckpointRepository repository,
            CheckpointPolicy policy,
            CheckpointSnapshotBuilder snapshotBuilder,
            ResumeCheckpointSelector selections) {
        this.repository = Objects.requireNonNull(repository);
        this.policy = Objects.requireNonNull(policy);
        this.snapshotBuilder = Objects.requireNonNull(snapshotBuilder);
        this.selections = Objects.requireNonNull(selections);
    }

    public Optional<Checkpoint> capture(
            AgentRun run, int completedIteration, List<String> fingerprints, CheckpointType type) {
        if (!policy.shouldCapture(run, completedIteration, type)) return Optional.empty();
        long sequence = repository.latest(run.id()).map(Checkpoint::sequence).orElse(0L) + 1L;
        var snapshot = snapshotBuilder.build(run, completedIteration, fingerprints, type, sequence);
        repository.append(snapshot.checkpoint(), snapshot.state());
        return Optional.of(snapshot.checkpoint());
    }

    public Optional<RuntimeCheckpointState> restoreLatest(AgentRun run) {
        var selected = selections.consume(run.id());
        if (selected.isPresent()) return repository.state(selected.orElseThrow().value());
        return repository
                .latest(run.id())
                .flatMap(checkpoint -> repository.state(checkpoint.id().value()));
    }
}

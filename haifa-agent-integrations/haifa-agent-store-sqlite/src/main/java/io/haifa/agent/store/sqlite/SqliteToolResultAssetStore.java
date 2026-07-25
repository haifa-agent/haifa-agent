package io.haifa.agent.store.sqlite;

import io.haifa.agent.core.reference.AssetRef;
import io.haifa.agent.core.tool.ToolCallId;
import io.haifa.agent.core.tool.ToolResult;
import io.haifa.agent.runtime.core.tool.ToolResultAssetStore;
import io.haifa.agent.store.sqlite.codec.EncodedPayload;
import io.haifa.agent.store.sqlite.codec.VersionedPayloadCodecRegistry;
import io.haifa.agent.store.sqlite.mybatis.RuntimeStoreMapper;
import io.haifa.agent.store.sqlite.mybatis.ToolResultAssetRow;
import io.haifa.agent.store.sqlite.payload.SqliteRuntimePayloadTypes;
import io.haifa.agent.store.sqlite.payload.ToolResultPayload;
import java.time.Clock;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

public final class SqliteToolResultAssetStore implements ToolResultAssetStore {
    private static final String MIME_TYPE = "application/vnd.haifa.tool-result";

    private final SqliteRuntimeUnitOfWork unitOfWork;
    private final VersionedPayloadCodecRegistry codecs;
    private final Clock clock;

    public SqliteToolResultAssetStore(
            SqliteRuntimeUnitOfWork unitOfWork, VersionedPayloadCodecRegistry codecs, Clock clock) {
        this.unitOfWork = Objects.requireNonNull(unitOfWork);
        this.codecs = Objects.requireNonNull(codecs);
        this.clock = Objects.requireNonNull(clock);
    }

    @Override
    public AssetRef put(ToolCallId toolCallId, ToolResult result) {
        return execute(() -> {
            RuntimeStoreMapper mapper = unitOfWork.mapper(RuntimeStoreMapper.class);
            EncodedPayload payload =
                    codecs.encode(SqliteRuntimePayloadTypes.TOOL_RESULT, ToolResultPayload.from(result));
            String id = "tool-result:" + payload.hash();
            mapper.insertToolResultAsset(new ToolResultAssetRow(
                    id,
                    toolCallId.value(),
                    payload.schemaVersion(),
                    payload.bytes(),
                    payload.hash(),
                    payload.bytes().length,
                    clock.instant()));
            ToolResultAssetRow stored = mapper.findToolResultAsset(id);
            ToolResult restored = decode(stored);
            if (!stored.toolCallId().equals(toolCallId.value()) || !restored.equals(result)) {
                throw new IllegalStateException("tool result asset hash collision");
            }
            return new AssetRef(id, MIME_TYPE, toolCallId.value() + ".result");
        });
    }

    @Override
    public Optional<ToolResult> load(AssetRef reference) {
        if (!MIME_TYPE.equals(reference.mimeType())) {
            throw new IllegalArgumentException("asset reference has incompatible media type");
        }
        return execute(() -> Optional.ofNullable(
                        unitOfWork.mapper(RuntimeStoreMapper.class).findToolResultAsset(reference.assetId()))
                .map(this::decode));
    }

    private ToolResult decode(ToolResultAssetRow row) {
        if (row == null || row.byteLength() != row.resultPayload().length) {
            throw new IllegalStateException("tool result asset length validation failed");
        }
        return codecs.decode(
                        SqliteRuntimePayloadTypes.TOOL_RESULT,
                        new EncodedPayload(
                                SqliteRuntimePayloadTypes.TOOL_RESULT.name(),
                                row.resultSchemaVersion(),
                                row.resultPayload(),
                                row.resultHash()))
                .toDomain();
    }

    private <T> T execute(Supplier<T> work) {
        try {
            return unitOfWork.execute(work);
        } catch (SqliteStoreException exception) {
            if (exception.getCause() instanceof RuntimeException runtimeException) throw runtimeException;
            throw exception;
        }
    }
}

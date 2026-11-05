package io.haifa.coding.api;

import java.util.List;
import java.util.Objects;

/** Generated from contract/coding-presentation.schema.json. No engine or Runtime dependencies. */
public final class CodingProtocol {
    private CodingProtocol() {}

    public record Target(String sessionId, String runId, String interactionId, Long targetRevision) {
        public Target {
            Objects.requireNonNull(sessionId, "sessionId");
            if (sessionId.isEmpty()) throw new IllegalArgumentException("Empty sessionId");
            if (sessionId.codePointCount(0, sessionId.length()) > 256)
                throw new IllegalArgumentException("sessionId exceeds budget");
            if (runId != null) {
                Objects.requireNonNull(runId, "runId");
                if (runId.isEmpty()) throw new IllegalArgumentException("Empty runId");
                if (runId.codePointCount(0, runId.length()) > 256)
                    throw new IllegalArgumentException("runId exceeds budget");
            }
            if (interactionId != null) {
                Objects.requireNonNull(interactionId, "interactionId");
                if (interactionId.isEmpty()) throw new IllegalArgumentException("Empty interactionId");
                if (interactionId.codePointCount(0, interactionId.length()) > 256)
                    throw new IllegalArgumentException("interactionId exceeds budget");
            }
            if (targetRevision != null) {
                if (targetRevision < 0L || targetRevision > 9007199254740991L)
                    throw new IllegalArgumentException("Invalid targetRevision");
            }
        }
    }

    public record Action(String kind, Target target, String choice, boolean available, String reason) {
        public Action {
            Objects.requireNonNull(kind, "kind");
            if (kind.isEmpty()) throw new IllegalArgumentException("Empty kind");
            if (kind.codePointCount(0, kind.length()) > 40) throw new IllegalArgumentException("kind exceeds budget");
            if (!java.util.Set.of("submit", "cancel", "respond", "allowForSession", "resetConsent")
                    .contains(kind)) throw new IllegalArgumentException("Unknown kind");
            Objects.requireNonNull(target, "target");
            if (choice != null) {
                Objects.requireNonNull(choice, "choice");
                if (choice.codePointCount(0, choice.length()) > 64)
                    throw new IllegalArgumentException("choice exceeds budget");
            }
            if (reason != null) {
                Objects.requireNonNull(reason, "reason");
                if (reason.codePointCount(0, reason.length()) > 128)
                    throw new IllegalArgumentException("reason exceeds budget");
            }
        }
    }

    public record InputOption(String id, String label, String description) {
        public InputOption {
            Objects.requireNonNull(id, "id");
            if (id.isEmpty()) throw new IllegalArgumentException("Empty id");
            if (id.codePointCount(0, id.length()) > 256) throw new IllegalArgumentException("id exceeds budget");
            Objects.requireNonNull(label, "label");
            if (label.codePointCount(0, label.length()) > 512)
                throw new IllegalArgumentException("label exceeds budget");
            if (description != null) {
                Objects.requireNonNull(description, "description");
                if (description.codePointCount(0, description.length()) > 512)
                    throw new IllegalArgumentException("description exceeds budget");
            }
        }
    }

    public record Input(
            String type,
            long maximumCharacters,
            long minimumSelections,
            long maximumSelections,
            List<InputOption> options,
            List<Question> questions) {
        public Input {
            Objects.requireNonNull(type, "type");
            if (type.codePointCount(0, type.length()) > 64) throw new IllegalArgumentException("type exceeds budget");
            if (maximumCharacters < 0L || maximumCharacters > 65536L)
                throw new IllegalArgumentException("Invalid maximumCharacters");
            if (minimumSelections < 0L || minimumSelections > 100L)
                throw new IllegalArgumentException("Invalid minimumSelections");
            if (maximumSelections < 0L || maximumSelections > 100L)
                throw new IllegalArgumentException("Invalid maximumSelections");
            options = List.copyOf(options);
            if (options.size() > 100) throw new IllegalArgumentException("options exceeds budget");
            questions = List.copyOf(questions);
            if (questions.size() > 3) throw new IllegalArgumentException("questions exceeds budget");
        }
    }

    public record ReviewFact(String label, String value) {
        public ReviewFact {
            Objects.requireNonNull(label, "label");
            if (label.codePointCount(0, label.length()) > 64)
                throw new IllegalArgumentException("label exceeds budget");
            Objects.requireNonNull(value, "value");
            if (value.codePointCount(0, value.length()) > 512)
                throw new IllegalArgumentException("value exceeds budget");
        }
    }

    public record Review(
            String purpose,
            String contentType,
            String content,
            List<ReviewFact> environment,
            List<ReviewFact> technical,
            String risk,
            boolean targetComplete,
            boolean contentTruncated) {
        public Review {
            Objects.requireNonNull(purpose, "purpose");
            if (purpose.codePointCount(0, purpose.length()) > 512)
                throw new IllegalArgumentException("purpose exceeds budget");
            Objects.requireNonNull(contentType, "contentType");
            if (contentType.codePointCount(0, contentType.length()) > 64)
                throw new IllegalArgumentException("contentType exceeds budget");
            Objects.requireNonNull(content, "content");
            if (content.codePointCount(0, content.length()) > 16384)
                throw new IllegalArgumentException("content exceeds budget");
            environment = List.copyOf(environment);
            if (environment.size() > 8) throw new IllegalArgumentException("environment exceeds budget");
            technical = List.copyOf(technical);
            if (technical.size() > 16) throw new IllegalArgumentException("technical exceeds budget");
            if (risk != null) {
                Objects.requireNonNull(risk, "risk");
                if (risk.codePointCount(0, risk.length()) > 32)
                    throw new IllegalArgumentException("risk exceeds budget");
            }
        }
    }

    public record Interaction(String id, long revision, String kind, Input input, Review review, List<Action> actions) {
        public Interaction {
            Objects.requireNonNull(id, "id");
            if (id.isEmpty()) throw new IllegalArgumentException("Empty id");
            if (id.codePointCount(0, id.length()) > 256) throw new IllegalArgumentException("id exceeds budget");
            if (revision < 0L || revision > 9007199254740991L) throw new IllegalArgumentException("Invalid revision");
            Objects.requireNonNull(kind, "kind");
            if (kind.isEmpty()) throw new IllegalArgumentException("Empty kind");
            if (kind.codePointCount(0, kind.length()) > 64) throw new IllegalArgumentException("kind exceeds budget");
            Objects.requireNonNull(input, "input");
            if (review != null) {
                Objects.requireNonNull(review, "review");
            }
            actions = List.copyOf(actions);
            if (actions.size() > 16) throw new IllegalArgumentException("actions exceeds budget");
        }
    }

    public record Entry(
            String id,
            String kind,
            String runId,
            String role,
            String title,
            String text,
            String status,
            Long sequence,
            boolean truncated,
            Interaction interaction,
            Long occurredAtEpochMillis,
            String toolCallId,
            String interactionId,
            String noticeCode,
            String diagnosticId) {
        public Entry {
            Objects.requireNonNull(id, "id");
            if (id.isEmpty()) throw new IllegalArgumentException("Empty id");
            if (id.codePointCount(0, id.length()) > 256) throw new IllegalArgumentException("id exceeds budget");
            Objects.requireNonNull(kind, "kind");
            if (kind.isEmpty()) throw new IllegalArgumentException("Empty kind");
            if (kind.codePointCount(0, kind.length()) > 16) throw new IllegalArgumentException("kind exceeds budget");
            if (!java.util.Set.of("message", "tool", "interaction", "notice").contains(kind))
                throw new IllegalArgumentException("Unknown kind");
            if (runId != null) {
                Objects.requireNonNull(runId, "runId");
                if (runId.isEmpty()) throw new IllegalArgumentException("Empty runId");
                if (runId.codePointCount(0, runId.length()) > 256)
                    throw new IllegalArgumentException("runId exceeds budget");
            }
            Objects.requireNonNull(role, "role");
            if (role.codePointCount(0, role.length()) > 32) throw new IllegalArgumentException("role exceeds budget");
            Objects.requireNonNull(title, "title");
            if (title.codePointCount(0, title.length()) > 256)
                throw new IllegalArgumentException("title exceeds budget");
            Objects.requireNonNull(text, "text");
            if (text.codePointCount(0, text.length()) > 16384)
                throw new IllegalArgumentException("text exceeds budget");
            Objects.requireNonNull(status, "status");
            if (status.codePointCount(0, status.length()) > 64)
                throw new IllegalArgumentException("status exceeds budget");
            if (sequence != null) {
                if (sequence < 0L || sequence > 9007199254740991L)
                    throw new IllegalArgumentException("Invalid sequence");
            }
            if (interaction != null) {
                Objects.requireNonNull(interaction, "interaction");
            }
            if (occurredAtEpochMillis != null) {
                if (occurredAtEpochMillis < 0L || occurredAtEpochMillis > 9007199254740991L)
                    throw new IllegalArgumentException("Invalid occurredAtEpochMillis");
            }
            if (toolCallId != null) {
                Objects.requireNonNull(toolCallId, "toolCallId");
                if (toolCallId.isEmpty()) throw new IllegalArgumentException("Empty toolCallId");
                if (toolCallId.codePointCount(0, toolCallId.length()) > 256)
                    throw new IllegalArgumentException("toolCallId exceeds budget");
            }
            if (interactionId != null) {
                Objects.requireNonNull(interactionId, "interactionId");
                if (interactionId.isEmpty()) throw new IllegalArgumentException("Empty interactionId");
                if (interactionId.codePointCount(0, interactionId.length()) > 256)
                    throw new IllegalArgumentException("interactionId exceeds budget");
            }
            if (noticeCode != null) {
                Objects.requireNonNull(noticeCode, "noticeCode");
                if (noticeCode.isEmpty()) throw new IllegalArgumentException("Empty noticeCode");
                if (noticeCode.codePointCount(0, noticeCode.length()) > 128)
                    throw new IllegalArgumentException("noticeCode exceeds budget");
            }
            if (diagnosticId != null) {
                Objects.requireNonNull(diagnosticId, "diagnosticId");
                if (diagnosticId.isEmpty()) throw new IllegalArgumentException("Empty diagnosticId");
                if (diagnosticId.codePointCount(0, diagnosticId.length()) > 256)
                    throw new IllegalArgumentException("diagnosticId exceeds budget");
            }
        }
    }

    public record Run(String id, String status, long revision) {
        public Run {
            Objects.requireNonNull(id, "id");
            if (id.isEmpty()) throw new IllegalArgumentException("Empty id");
            if (id.codePointCount(0, id.length()) > 256) throw new IllegalArgumentException("id exceeds budget");
            Objects.requireNonNull(status, "status");
            if (status.codePointCount(0, status.length()) > 64)
                throw new IllegalArgumentException("status exceeds budget");
            if (revision < 0L || revision > 9007199254740991L) throw new IllegalArgumentException("Invalid revision");
        }
    }

    public record Session(
            String id,
            String title,
            long revision,
            String status,
            Run run,
            String modelId,
            String modelName,
            String context,
            long modelRevision,
            boolean modelAvailable) {
        public Session {
            Objects.requireNonNull(id, "id");
            if (id.isEmpty()) throw new IllegalArgumentException("Empty id");
            if (id.codePointCount(0, id.length()) > 256) throw new IllegalArgumentException("id exceeds budget");
            Objects.requireNonNull(title, "title");
            if (title.codePointCount(0, title.length()) > 256)
                throw new IllegalArgumentException("title exceeds budget");
            if (revision < 0L || revision > 9007199254740991L) throw new IllegalArgumentException("Invalid revision");
            Objects.requireNonNull(status, "status");
            if (status.codePointCount(0, status.length()) > 64)
                throw new IllegalArgumentException("status exceeds budget");
            if (run != null) {
                Objects.requireNonNull(run, "run");
            }
            Objects.requireNonNull(modelId, "modelId");
            if (modelId.isEmpty()) throw new IllegalArgumentException("Empty modelId");
            if (modelId.codePointCount(0, modelId.length()) > 256)
                throw new IllegalArgumentException("modelId exceeds budget");
            Objects.requireNonNull(modelName, "modelName");
            if (modelName.codePointCount(0, modelName.length()) > 512)
                throw new IllegalArgumentException("modelName exceeds budget");
            Objects.requireNonNull(context, "context");
            if (context.codePointCount(0, context.length()) > 4096)
                throw new IllegalArgumentException("context exceeds budget");
            if (modelRevision < 0L || modelRevision > 9007199254740991L)
                throw new IllegalArgumentException("Invalid modelRevision");
        }
    }

    public record Scope(
            String viewId,
            String hostIncarnation,
            String clientId,
            String sessionId,
            long schemaVersion,
            String source) {
        public Scope {
            Objects.requireNonNull(viewId, "viewId");
            if (viewId.isEmpty()) throw new IllegalArgumentException("Empty viewId");
            if (viewId.codePointCount(0, viewId.length()) > 256)
                throw new IllegalArgumentException("viewId exceeds budget");
            Objects.requireNonNull(hostIncarnation, "hostIncarnation");
            if (hostIncarnation.isEmpty()) throw new IllegalArgumentException("Empty hostIncarnation");
            if (hostIncarnation.codePointCount(0, hostIncarnation.length()) > 256)
                throw new IllegalArgumentException("hostIncarnation exceeds budget");
            Objects.requireNonNull(clientId, "clientId");
            if (clientId.isEmpty()) throw new IllegalArgumentException("Empty clientId");
            if (clientId.codePointCount(0, clientId.length()) > 256)
                throw new IllegalArgumentException("clientId exceeds budget");
            Objects.requireNonNull(sessionId, "sessionId");
            if (sessionId.isEmpty()) throw new IllegalArgumentException("Empty sessionId");
            if (sessionId.codePointCount(0, sessionId.length()) > 256)
                throw new IllegalArgumentException("sessionId exceeds budget");
            if (schemaVersion < 0L || schemaVersion > 9007199254740991L)
                throw new IllegalArgumentException("Invalid schemaVersion");
            if (!(schemaVersion == 1)) throw new IllegalArgumentException("Invalid schemaVersion");
            Objects.requireNonNull(source, "source");
            if (!java.util.Set.of("native", "browser").contains(source))
                throw new IllegalArgumentException("Unknown source");
        }
    }

    public record History(boolean hasEarlier, Long beforeSequence, boolean complete) {
        public History {
            if (beforeSequence != null) {
                if (beforeSequence < 1L || beforeSequence > 9007199254740991L)
                    throw new IllegalArgumentException("Invalid beforeSequence");
            }
        }
    }

    public record Live(
            String runId,
            String generationId,
            long sequence,
            String text,
            boolean truncated,
            String modelCallId,
            long physicalAttempt) {
        public Live {
            Objects.requireNonNull(runId, "runId");
            if (runId.isEmpty()) throw new IllegalArgumentException("Empty runId");
            if (runId.codePointCount(0, runId.length()) > 256)
                throw new IllegalArgumentException("runId exceeds budget");
            Objects.requireNonNull(generationId, "generationId");
            if (generationId.isEmpty()) throw new IllegalArgumentException("Empty generationId");
            if (generationId.codePointCount(0, generationId.length()) > 256)
                throw new IllegalArgumentException("generationId exceeds budget");
            if (sequence < 0L || sequence > 9007199254740991L) throw new IllegalArgumentException("Invalid sequence");
            Objects.requireNonNull(text, "text");
            if (text.codePointCount(0, text.length()) > 8000) throw new IllegalArgumentException("text exceeds budget");
            Objects.requireNonNull(modelCallId, "modelCallId");
            if (modelCallId.isEmpty()) throw new IllegalArgumentException("Empty modelCallId");
            if (modelCallId.codePointCount(0, modelCallId.length()) > 256)
                throw new IllegalArgumentException("modelCallId exceeds budget");
            if (physicalAttempt < 1L || physicalAttempt > 9007199254740991L)
                throw new IllegalArgumentException("Invalid physicalAttempt");
        }
    }

    public record Consent(String state, Long expiresAtEpochMillis, boolean available) {
        public Consent {
            Objects.requireNonNull(state, "state");
            if (state.codePointCount(0, state.length()) > 32)
                throw new IllegalArgumentException("state exceeds budget");
            if (!java.util.Set.of("ASK", "PENDING", "ACTIVE").contains(state))
                throw new IllegalArgumentException("Unknown state");
            if (expiresAtEpochMillis != null) {
                if (expiresAtEpochMillis < 0L || expiresAtEpochMillis > 9007199254740991L)
                    throw new IllegalArgumentException("Invalid expiresAtEpochMillis");
            }
        }
    }

    public record Snapshot(
            String kind,
            Scope scope,
            long revision,
            String hydration,
            Session session,
            List<Entry> entries,
            History history,
            Live live,
            Consent consent,
            List<Action> actions,
            List<String> notices)
            implements Envelope {
        public Snapshot {
            Objects.requireNonNull(kind, "kind");
            if (kind.isEmpty()) throw new IllegalArgumentException("Empty kind");
            if (!(kind.equals("snapshot"))) throw new IllegalArgumentException("Invalid kind");
            Objects.requireNonNull(scope, "scope");
            if (revision < 0L || revision > 9007199254740991L) throw new IllegalArgumentException("Invalid revision");
            Objects.requireNonNull(hydration, "hydration");
            if (hydration.codePointCount(0, hydration.length()) > 32)
                throw new IllegalArgumentException("hydration exceeds budget");
            if (!java.util.Set.of("ready", "refreshing").contains(hydration))
                throw new IllegalArgumentException("Unknown hydration");
            Objects.requireNonNull(session, "session");
            entries = List.copyOf(entries);
            if (entries.size() > 32) throw new IllegalArgumentException("entries exceeds budget");
            Objects.requireNonNull(history, "history");
            if (live != null) {
                Objects.requireNonNull(live, "live");
            }
            Objects.requireNonNull(consent, "consent");
            actions = List.copyOf(actions);
            if (actions.size() > 16) throw new IllegalArgumentException("actions exceeds budget");
            notices = List.copyOf(notices);
            if (notices.size() > 16) throw new IllegalArgumentException("notices exceeds budget");
        }
    }

    public record Update(String kind, String viewId, long baseRevision, long revision, Snapshot replacement)
            implements Envelope {
        public Update {
            Objects.requireNonNull(kind, "kind");
            if (kind.isEmpty()) throw new IllegalArgumentException("Empty kind");
            if (!(kind.equals("update"))) throw new IllegalArgumentException("Invalid kind");
            Objects.requireNonNull(viewId, "viewId");
            if (viewId.isEmpty()) throw new IllegalArgumentException("Empty viewId");
            if (viewId.codePointCount(0, viewId.length()) > 256)
                throw new IllegalArgumentException("viewId exceeds budget");
            if (baseRevision < 0L || baseRevision > 9007199254740991L)
                throw new IllegalArgumentException("Invalid baseRevision");
            if (revision < 1L || revision > 9007199254740991L) throw new IllegalArgumentException("Invalid revision");
            Objects.requireNonNull(replacement, "replacement");
        }
    }

    public record Resync(String kind, String viewId, String reason) implements Envelope {
        public Resync {
            Objects.requireNonNull(kind, "kind");
            if (kind.isEmpty()) throw new IllegalArgumentException("Empty kind");
            if (!(kind.equals("resync"))) throw new IllegalArgumentException("Invalid kind");
            Objects.requireNonNull(viewId, "viewId");
            if (viewId.isEmpty()) throw new IllegalArgumentException("Empty viewId");
            if (viewId.codePointCount(0, viewId.length()) > 256)
                throw new IllegalArgumentException("viewId exceeds budget");
            Objects.requireNonNull(reason, "reason");
            if (reason.codePointCount(0, reason.length()) > 128)
                throw new IllegalArgumentException("reason exceeds budget");
        }
    }

    public record Question(
            String header, String question, boolean multiple, boolean allowCustom, List<InputOption> options) {
        public Question {
            Objects.requireNonNull(header, "header");
            if (header.codePointCount(0, header.length()) > 32)
                throw new IllegalArgumentException("header exceeds budget");
            Objects.requireNonNull(question, "question");
            if (question.codePointCount(0, question.length()) > 2000)
                throw new IllegalArgumentException("question exceeds budget");
            options = List.copyOf(options);
            if (options.size() > 6) throw new IllegalArgumentException("options exceeds budget");
        }
    }

    public record HistoryPage(String sessionId, List<Entry> entries, History history) {
        public HistoryPage {
            Objects.requireNonNull(sessionId, "sessionId");
            if (sessionId.codePointCount(0, sessionId.length()) > 256)
                throw new IllegalArgumentException("sessionId exceeds budget");
            entries = List.copyOf(entries);
            if (entries.size() > 100) throw new IllegalArgumentException("entries exceeds budget");
            Objects.requireNonNull(history, "history");
        }
    }

    public record CommandTarget(
            String projectId, String sessionId, String runId, String interactionId, Long targetRevision) {
        public CommandTarget {
            if (projectId != null) {
                Objects.requireNonNull(projectId, "projectId");
                if (projectId.codePointCount(0, projectId.length()) > 256)
                    throw new IllegalArgumentException("projectId exceeds budget");
            }
            if (sessionId != null) {
                Objects.requireNonNull(sessionId, "sessionId");
                if (sessionId.codePointCount(0, sessionId.length()) > 256)
                    throw new IllegalArgumentException("sessionId exceeds budget");
            }
            if (runId != null) {
                Objects.requireNonNull(runId, "runId");
                if (runId.codePointCount(0, runId.length()) > 256)
                    throw new IllegalArgumentException("runId exceeds budget");
            }
            if (interactionId != null) {
                Objects.requireNonNull(interactionId, "interactionId");
                if (interactionId.codePointCount(0, interactionId.length()) > 256)
                    throw new IllegalArgumentException("interactionId exceeds budget");
            }
            if (targetRevision != null) {
                if (targetRevision < 0L || targetRevision > 9007199254740991L)
                    throw new IllegalArgumentException("Invalid targetRevision");
            }
        }
    }

    public record Command(
            String kind,
            String operation,
            String clientRequestId,
            String idempotencyKey,
            CommandTarget target,
            String text,
            String optionId,
            String executionId,
            List<String> selections) {
        public Command {
            Objects.requireNonNull(kind, "kind");
            if (!(kind.equals("command"))) throw new IllegalArgumentException("Invalid kind");
            Objects.requireNonNull(operation, "operation");
            if (!java.util.Set.of(
                            "create",
                            "submit",
                            "steer",
                            "followUp",
                            "restore",
                            "respond",
                            "cancel",
                            "rename",
                            "archive",
                            "delete",
                            "compact",
                            "export",
                            "selectModel",
                            "shellPlan",
                            "shellStart",
                            "shellGet",
                            "shellCancel",
                            "shellDiscard",
                            "workspaceRevoke",
                            "resourcesReload",
                            "resetConsent",
                            "allowForSession",
                            "prepareApproval")
                    .contains(operation)) throw new IllegalArgumentException("Unknown operation");
            Objects.requireNonNull(clientRequestId, "clientRequestId");
            if (clientRequestId.codePointCount(0, clientRequestId.length()) > 256)
                throw new IllegalArgumentException("clientRequestId exceeds budget");
            if (idempotencyKey != null) {
                Objects.requireNonNull(idempotencyKey, "idempotencyKey");
                if (idempotencyKey.codePointCount(0, idempotencyKey.length()) > 256)
                    throw new IllegalArgumentException("idempotencyKey exceeds budget");
            }
            Objects.requireNonNull(target, "target");
            if (text != null) {
                Objects.requireNonNull(text, "text");
                if (text.codePointCount(0, text.length()) > 65536)
                    throw new IllegalArgumentException("text exceeds budget");
            }
            if (optionId != null) {
                Objects.requireNonNull(optionId, "optionId");
                if (optionId.codePointCount(0, optionId.length()) > 256)
                    throw new IllegalArgumentException("optionId exceeds budget");
            }
            if (executionId != null) {
                Objects.requireNonNull(executionId, "executionId");
                if (executionId.codePointCount(0, executionId.length()) > 256)
                    throw new IllegalArgumentException("executionId exceeds budget");
            }
            selections = List.copyOf(selections);
            if (selections.size() > 100) throw new IllegalArgumentException("selections exceeds budget");
        }
    }

    public record Receipt(
            String clientRequestId, String disposition, String operationId, String runId, String reasonCode) {
        public Receipt {
            Objects.requireNonNull(clientRequestId, "clientRequestId");
            if (clientRequestId.codePointCount(0, clientRequestId.length()) > 256)
                throw new IllegalArgumentException("clientRequestId exceeds budget");
            Objects.requireNonNull(disposition, "disposition");
            if (!java.util.Set.of("accepted", "rejected", "unknown").contains(disposition))
                throw new IllegalArgumentException("Unknown disposition");
            if (operationId != null) {
                Objects.requireNonNull(operationId, "operationId");
                if (operationId.codePointCount(0, operationId.length()) > 256)
                    throw new IllegalArgumentException("operationId exceeds budget");
            }
            if (runId != null) {
                Objects.requireNonNull(runId, "runId");
                if (runId.codePointCount(0, runId.length()) > 256)
                    throw new IllegalArgumentException("runId exceeds budget");
            }
            if (reasonCode != null) {
                Objects.requireNonNull(reasonCode, "reasonCode");
                if (reasonCode.codePointCount(0, reasonCode.length()) > 256)
                    throw new IllegalArgumentException("reasonCode exceeds budget");
            }
        }
    }

    public record Preview(
            String sessionId,
            String runId,
            String toolCallId,
            String channel,
            String text,
            boolean outputTruncated,
            boolean previewDropped) {
        public Preview {
            Objects.requireNonNull(sessionId, "sessionId");
            if (sessionId.isEmpty()) throw new IllegalArgumentException("Empty sessionId");
            if (sessionId.codePointCount(0, sessionId.length()) > 256)
                throw new IllegalArgumentException("sessionId exceeds budget");
            Objects.requireNonNull(runId, "runId");
            if (runId.isEmpty()) throw new IllegalArgumentException("Empty runId");
            if (runId.codePointCount(0, runId.length()) > 256)
                throw new IllegalArgumentException("runId exceeds budget");
            Objects.requireNonNull(toolCallId, "toolCallId");
            if (toolCallId.isEmpty()) throw new IllegalArgumentException("Empty toolCallId");
            if (toolCallId.codePointCount(0, toolCallId.length()) > 256)
                throw new IllegalArgumentException("toolCallId exceeds budget");
            Objects.requireNonNull(channel, "channel");
            if (!java.util.Set.of("STDOUT", "STDERR").contains(channel))
                throw new IllegalArgumentException("Unknown channel");
            Objects.requireNonNull(text, "text");
            if (text.codePointCount(0, text.length()) > 4096) throw new IllegalArgumentException("text exceeds budget");
        }
    }

    public record Subscribed(String subscriptionId, Snapshot snapshot) {
        public Subscribed {
            Objects.requireNonNull(subscriptionId, "subscriptionId");
            if (subscriptionId.isEmpty()) throw new IllegalArgumentException("Empty subscriptionId");
            if (subscriptionId.codePointCount(0, subscriptionId.length()) > 256)
                throw new IllegalArgumentException("subscriptionId exceeds budget");
            Objects.requireNonNull(snapshot, "snapshot");
        }
    }

    public sealed interface Envelope permits Snapshot, Update, Resync {}
}

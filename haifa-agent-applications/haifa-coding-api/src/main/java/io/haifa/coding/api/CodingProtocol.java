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

    public record ToolOutput(String text, boolean truncated, long byteCount, long lineCount) {
        public ToolOutput {
            Objects.requireNonNull(text, "text");
            if (text.codePointCount(0, text.length()) > 4000) throw new IllegalArgumentException("text exceeds budget");
            if (byteCount < 0L || byteCount > 9007199254740991L)
                throw new IllegalArgumentException("Invalid byteCount");
            if (lineCount < 0L || lineCount > 9007199254740991L)
                throw new IllegalArgumentException("Invalid lineCount");
        }
    }

    public record ToolDetails(
            String name,
            String intent,
            String reasonCode,
            String resultRef,
            String processState,
            Long exitCode,
            Long startedAtEpochMillis,
            Long durationMillis,
            ToolOutput output) {
        public ToolDetails {
            Objects.requireNonNull(name, "name");
            if (name.codePointCount(0, name.length()) > 128) throw new IllegalArgumentException("name exceeds budget");
            Objects.requireNonNull(intent, "intent");
            if (intent.codePointCount(0, intent.length()) > 256)
                throw new IllegalArgumentException("intent exceeds budget");
            Objects.requireNonNull(reasonCode, "reasonCode");
            if (reasonCode.codePointCount(0, reasonCode.length()) > 128)
                throw new IllegalArgumentException("reasonCode exceeds budget");
            Objects.requireNonNull(resultRef, "resultRef");
            if (resultRef.codePointCount(0, resultRef.length()) > 512)
                throw new IllegalArgumentException("resultRef exceeds budget");
            if (processState != null) {
                Objects.requireNonNull(processState, "processState");
                if (processState.codePointCount(0, processState.length()) > 64)
                    throw new IllegalArgumentException("processState exceeds budget");
            }
            if (exitCode != null) {
                if (exitCode < -2147483648L || exitCode > 2147483647L)
                    throw new IllegalArgumentException("Invalid exitCode");
            }
            if (startedAtEpochMillis != null) {
                if (startedAtEpochMillis < 0L || startedAtEpochMillis > 9007199254740991L)
                    throw new IllegalArgumentException("Invalid startedAtEpochMillis");
            }
            if (durationMillis != null) {
                if (durationMillis < 0L || durationMillis > 9007199254740991L)
                    throw new IllegalArgumentException("Invalid durationMillis");
            }
            if (output != null) {
                Objects.requireNonNull(output, "output");
            }
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
            String diagnosticId,
            ToolDetails toolDetails) {
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
            if (toolDetails != null) {
                Objects.requireNonNull(toolDetails, "toolDetails");
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
            if (notices.stream().anyMatch(item -> item.codePointCount(0, item.length()) > 512))
                throw new IllegalArgumentException("notices item exceeds budget");
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
                            "prepareApproval",
                            "logout",
                            "shellExecute",
                            "reconcile")
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
            if (selections.stream().anyMatch(item -> item.codePointCount(0, item.length()) > 512))
                throw new IllegalArgumentException("selections item exceeds budget");
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

    public record ConnectionInfo(
            String hostIncarnation,
            String clientId,
            String projectId,
            String protocolVersion,
            String displayName,
            List<String> capabilities,
            String source) {
        public ConnectionInfo {
            Objects.requireNonNull(hostIncarnation, "hostIncarnation");
            if (hostIncarnation.isEmpty()) throw new IllegalArgumentException("Empty hostIncarnation");
            if (hostIncarnation.codePointCount(0, hostIncarnation.length()) > 256)
                throw new IllegalArgumentException("hostIncarnation exceeds budget");
            Objects.requireNonNull(clientId, "clientId");
            if (clientId.isEmpty()) throw new IllegalArgumentException("Empty clientId");
            if (clientId.codePointCount(0, clientId.length()) > 256)
                throw new IllegalArgumentException("clientId exceeds budget");
            Objects.requireNonNull(projectId, "projectId");
            if (projectId.isEmpty()) throw new IllegalArgumentException("Empty projectId");
            if (projectId.codePointCount(0, projectId.length()) > 256)
                throw new IllegalArgumentException("projectId exceeds budget");
            Objects.requireNonNull(protocolVersion, "protocolVersion");
            if (protocolVersion.isEmpty()) throw new IllegalArgumentException("Empty protocolVersion");
            if (protocolVersion.codePointCount(0, protocolVersion.length()) > 16)
                throw new IllegalArgumentException("protocolVersion exceeds budget");
            Objects.requireNonNull(displayName, "displayName");
            if (displayName.codePointCount(0, displayName.length()) > 256)
                throw new IllegalArgumentException("displayName exceeds budget");
            capabilities = List.copyOf(capabilities);
            if (capabilities.size() > 64) throw new IllegalArgumentException("capabilities exceeds budget");
            if (capabilities.stream().anyMatch(item -> item.codePointCount(0, item.length()) > 64))
                throw new IllegalArgumentException("capabilities item exceeds budget");
            Objects.requireNonNull(source, "source");
            if (source.isEmpty()) throw new IllegalArgumentException("Empty source");
            if (source.codePointCount(0, source.length()) > 16)
                throw new IllegalArgumentException("source exceeds budget");
            if (!java.util.Set.of("native", "browser", "embedded").contains(source))
                throw new IllegalArgumentException("Unknown source");
        }
    }

    public record SessionSummary(
            String sessionId,
            String projectId,
            String title,
            String status,
            String runId,
            String runStatus,
            long queuedCount,
            long lastActivityAtEpochMillis,
            long revision) {
        public SessionSummary {
            Objects.requireNonNull(sessionId, "sessionId");
            if (sessionId.isEmpty()) throw new IllegalArgumentException("Empty sessionId");
            if (sessionId.codePointCount(0, sessionId.length()) > 256)
                throw new IllegalArgumentException("sessionId exceeds budget");
            Objects.requireNonNull(projectId, "projectId");
            if (projectId.isEmpty()) throw new IllegalArgumentException("Empty projectId");
            if (projectId.codePointCount(0, projectId.length()) > 256)
                throw new IllegalArgumentException("projectId exceeds budget");
            Objects.requireNonNull(title, "title");
            if (title.codePointCount(0, title.length()) > 256)
                throw new IllegalArgumentException("title exceeds budget");
            Objects.requireNonNull(status, "status");
            if (status.isEmpty()) throw new IllegalArgumentException("Empty status");
            if (status.codePointCount(0, status.length()) > 64)
                throw new IllegalArgumentException("status exceeds budget");
            if (runId != null) {
                Objects.requireNonNull(runId, "runId");
                if (runId.isEmpty()) throw new IllegalArgumentException("Empty runId");
                if (runId.codePointCount(0, runId.length()) > 256)
                    throw new IllegalArgumentException("runId exceeds budget");
            }
            if (runStatus != null) {
                Objects.requireNonNull(runStatus, "runStatus");
                if (runStatus.codePointCount(0, runStatus.length()) > 64)
                    throw new IllegalArgumentException("runStatus exceeds budget");
            }
            if (queuedCount < 0L || queuedCount > 9007199254740991L)
                throw new IllegalArgumentException("Invalid queuedCount");
            if (lastActivityAtEpochMillis < 0L || lastActivityAtEpochMillis > 9007199254740991L)
                throw new IllegalArgumentException("Invalid lastActivityAtEpochMillis");
            if (revision < 0L || revision > 9007199254740991L) throw new IllegalArgumentException("Invalid revision");
        }
    }

    public record RunUsage(
            long inputTokens,
            long outputTokens,
            long cachedInputTokens,
            long modelCalls,
            long toolCalls,
            long childRuns,
            long costMinorUnits,
            long wallTimeMillis) {
        public RunUsage {
            if (inputTokens < 0L || inputTokens > 9007199254740991L)
                throw new IllegalArgumentException("Invalid inputTokens");
            if (outputTokens < 0L || outputTokens > 9007199254740991L)
                throw new IllegalArgumentException("Invalid outputTokens");
            if (cachedInputTokens < 0L || cachedInputTokens > 9007199254740991L)
                throw new IllegalArgumentException("Invalid cachedInputTokens");
            if (modelCalls < 0L || modelCalls > 9007199254740991L)
                throw new IllegalArgumentException("Invalid modelCalls");
            if (toolCalls < 0L || toolCalls > 9007199254740991L)
                throw new IllegalArgumentException("Invalid toolCalls");
            if (childRuns < 0L || childRuns > 9007199254740991L)
                throw new IllegalArgumentException("Invalid childRuns");
            if (costMinorUnits < 0L || costMinorUnits > 9007199254740991L)
                throw new IllegalArgumentException("Invalid costMinorUnits");
            if (wallTimeMillis < 0L || wallTimeMillis > 9007199254740991L)
                throw new IllegalArgumentException("Invalid wallTimeMillis");
        }
    }

    public record Artifact(String id, String type, String version, String title) {
        public Artifact {
            Objects.requireNonNull(id, "id");
            if (id.isEmpty()) throw new IllegalArgumentException("Empty id");
            if (id.codePointCount(0, id.length()) > 256) throw new IllegalArgumentException("id exceeds budget");
            Objects.requireNonNull(type, "type");
            if (type.codePointCount(0, type.length()) > 128) throw new IllegalArgumentException("type exceeds budget");
            Objects.requireNonNull(version, "version");
            if (version.codePointCount(0, version.length()) > 128)
                throw new IllegalArgumentException("version exceeds budget");
            Objects.requireNonNull(title, "title");
            if (title.codePointCount(0, title.length()) > 512)
                throw new IllegalArgumentException("title exceeds budget");
        }
    }

    public record RunResult(
            String outcome,
            String summary,
            String outputSchemaId,
            String outputSchemaVersion,
            String structuredOutputDisplay,
            List<Artifact> artifacts,
            List<String> warnings,
            boolean truncated) {
        public RunResult {
            Objects.requireNonNull(outcome, "outcome");
            if (outcome.codePointCount(0, outcome.length()) > 64)
                throw new IllegalArgumentException("outcome exceeds budget");
            Objects.requireNonNull(summary, "summary");
            if (summary.codePointCount(0, summary.length()) > 16384)
                throw new IllegalArgumentException("summary exceeds budget");
            Objects.requireNonNull(outputSchemaId, "outputSchemaId");
            if (outputSchemaId.codePointCount(0, outputSchemaId.length()) > 256)
                throw new IllegalArgumentException("outputSchemaId exceeds budget");
            Objects.requireNonNull(outputSchemaVersion, "outputSchemaVersion");
            if (outputSchemaVersion.codePointCount(0, outputSchemaVersion.length()) > 64)
                throw new IllegalArgumentException("outputSchemaVersion exceeds budget");
            Objects.requireNonNull(structuredOutputDisplay, "structuredOutputDisplay");
            if (structuredOutputDisplay.codePointCount(0, structuredOutputDisplay.length()) > 16384)
                throw new IllegalArgumentException("structuredOutputDisplay exceeds budget");
            artifacts = List.copyOf(artifacts);
            if (artifacts.size() > 32) throw new IllegalArgumentException("artifacts exceeds budget");
            warnings = List.copyOf(warnings);
            if (warnings.size() > 32) throw new IllegalArgumentException("warnings exceeds budget");
            if (warnings.stream().anyMatch(item -> item.codePointCount(0, item.length()) > 512))
                throw new IllegalArgumentException("warnings item exceeds budget");
        }
    }

    public record RunFact(
            String id,
            String status,
            long revision,
            long updatedAtEpochMillis,
            String output,
            String errorCode,
            String errorMessage,
            String diagnosticId,
            String terminationCode,
            String terminationDescription,
            RunUsage usage,
            RunResult result,
            boolean truncated) {
        public RunFact {
            Objects.requireNonNull(id, "id");
            if (id.isEmpty()) throw new IllegalArgumentException("Empty id");
            if (id.codePointCount(0, id.length()) > 256) throw new IllegalArgumentException("id exceeds budget");
            Objects.requireNonNull(status, "status");
            if (status.isEmpty()) throw new IllegalArgumentException("Empty status");
            if (status.codePointCount(0, status.length()) > 64)
                throw new IllegalArgumentException("status exceeds budget");
            if (revision < 0L || revision > 9007199254740991L) throw new IllegalArgumentException("Invalid revision");
            if (updatedAtEpochMillis < 0L || updatedAtEpochMillis > 9007199254740991L)
                throw new IllegalArgumentException("Invalid updatedAtEpochMillis");
            if (output != null) {
                Objects.requireNonNull(output, "output");
                if (output.codePointCount(0, output.length()) > 16384)
                    throw new IllegalArgumentException("output exceeds budget");
            }
            if (errorCode != null) {
                Objects.requireNonNull(errorCode, "errorCode");
                if (errorCode.codePointCount(0, errorCode.length()) > 128)
                    throw new IllegalArgumentException("errorCode exceeds budget");
            }
            if (errorMessage != null) {
                Objects.requireNonNull(errorMessage, "errorMessage");
                if (errorMessage.codePointCount(0, errorMessage.length()) > 4096)
                    throw new IllegalArgumentException("errorMessage exceeds budget");
            }
            if (diagnosticId != null) {
                Objects.requireNonNull(diagnosticId, "diagnosticId");
                if (diagnosticId.isEmpty()) throw new IllegalArgumentException("Empty diagnosticId");
                if (diagnosticId.codePointCount(0, diagnosticId.length()) > 256)
                    throw new IllegalArgumentException("diagnosticId exceeds budget");
            }
            if (terminationCode != null) {
                Objects.requireNonNull(terminationCode, "terminationCode");
                if (terminationCode.codePointCount(0, terminationCode.length()) > 128)
                    throw new IllegalArgumentException("terminationCode exceeds budget");
            }
            if (terminationDescription != null) {
                Objects.requireNonNull(terminationDescription, "terminationDescription");
                if (terminationDescription.codePointCount(0, terminationDescription.length()) > 4096)
                    throw new IllegalArgumentException("terminationDescription exceeds budget");
            }
            Objects.requireNonNull(usage, "usage");
            if (result != null) {
                Objects.requireNonNull(result, "result");
            }
        }
    }

    public record RunOutcome(String runId, String protocolStatus, List<String> diagnosticCodes) {
        public RunOutcome {
            Objects.requireNonNull(runId, "runId");
            if (runId.isEmpty()) throw new IllegalArgumentException("Empty runId");
            if (runId.codePointCount(0, runId.length()) > 256)
                throw new IllegalArgumentException("runId exceeds budget");
            Objects.requireNonNull(protocolStatus, "protocolStatus");
            if (protocolStatus.codePointCount(0, protocolStatus.length()) > 64)
                throw new IllegalArgumentException("protocolStatus exceeds budget");
            diagnosticCodes = List.copyOf(diagnosticCodes);
            if (diagnosticCodes.size() > 32) throw new IllegalArgumentException("diagnosticCodes exceeds budget");
            if (diagnosticCodes.stream().anyMatch(item -> item.codePointCount(0, item.length()) > 96))
                throw new IllegalArgumentException("diagnosticCodes item exceeds budget");
        }
    }

    public record ModelControl(
            String kind,
            boolean visible,
            boolean readOnly,
            List<String> allowedValues,
            String recommendedValue,
            String effectiveSummary) {
        public ModelControl {
            Objects.requireNonNull(kind, "kind");
            if (kind.codePointCount(0, kind.length()) > 64) throw new IllegalArgumentException("kind exceeds budget");
            allowedValues = List.copyOf(allowedValues);
            if (allowedValues.size() > 32) throw new IllegalArgumentException("allowedValues exceeds budget");
            if (allowedValues.stream().anyMatch(item -> item.codePointCount(0, item.length()) > 64))
                throw new IllegalArgumentException("allowedValues item exceeds budget");
            Objects.requireNonNull(recommendedValue, "recommendedValue");
            if (recommendedValue.codePointCount(0, recommendedValue.length()) > 64)
                throw new IllegalArgumentException("recommendedValue exceeds budget");
            Objects.requireNonNull(effectiveSummary, "effectiveSummary");
            if (effectiveSummary.codePointCount(0, effectiveSummary.length()) > 4096)
                throw new IllegalArgumentException("effectiveSummary exceeds budget");
        }
    }

    public record ImageProfile(
            List<String> allowedSources,
            List<String> supportedMediaTypes,
            long maxImagesPerRequest,
            long maxBytesPerItem,
            long maxTotalBytes,
            long maxUrlCharacters,
            boolean detailSupported,
            List<String> allowedDetails) {
        public ImageProfile {
            allowedSources = List.copyOf(allowedSources);
            if (allowedSources.size() > 16) throw new IllegalArgumentException("allowedSources exceeds budget");
            if (allowedSources.stream().anyMatch(item -> item.codePointCount(0, item.length()) > 64))
                throw new IllegalArgumentException("allowedSources item exceeds budget");
            supportedMediaTypes = List.copyOf(supportedMediaTypes);
            if (supportedMediaTypes.size() > 32)
                throw new IllegalArgumentException("supportedMediaTypes exceeds budget");
            if (supportedMediaTypes.stream().anyMatch(item -> item.codePointCount(0, item.length()) > 128))
                throw new IllegalArgumentException("supportedMediaTypes item exceeds budget");
            if (maxImagesPerRequest < 0L || maxImagesPerRequest > 9007199254740991L)
                throw new IllegalArgumentException("Invalid maxImagesPerRequest");
            if (maxBytesPerItem < 0L || maxBytesPerItem > 9007199254740991L)
                throw new IllegalArgumentException("Invalid maxBytesPerItem");
            if (maxTotalBytes < 0L || maxTotalBytes > 9007199254740991L)
                throw new IllegalArgumentException("Invalid maxTotalBytes");
            if (maxUrlCharacters < 0L || maxUrlCharacters > 9007199254740991L)
                throw new IllegalArgumentException("Invalid maxUrlCharacters");
            allowedDetails = List.copyOf(allowedDetails);
            if (allowedDetails.size() > 16) throw new IllegalArgumentException("allowedDetails exceeds budget");
            if (allowedDetails.stream().anyMatch(item -> item.codePointCount(0, item.length()) > 64))
                throw new IllegalArgumentException("allowedDetails item exceeds budget");
        }
    }

    public record ModelChoice(
            String id,
            String displayName,
            String providerId,
            String providerDisplayName,
            List<String> capabilities,
            long contextWindow,
            long maxOutputTokens,
            String connection,
            String bindingAvailability,
            String runtimeHealth,
            String runScope,
            String unavailableReason,
            ModelControl responseMode,
            ModelControl reasoningEffort,
            String recommendedResponseMode,
            String recommendedEffort,
            ImageProfile imageInput) {
        public ModelChoice {
            Objects.requireNonNull(id, "id");
            if (id.isEmpty()) throw new IllegalArgumentException("Empty id");
            if (id.codePointCount(0, id.length()) > 256) throw new IllegalArgumentException("id exceeds budget");
            Objects.requireNonNull(displayName, "displayName");
            if (displayName.codePointCount(0, displayName.length()) > 512)
                throw new IllegalArgumentException("displayName exceeds budget");
            Objects.requireNonNull(providerId, "providerId");
            if (providerId.codePointCount(0, providerId.length()) > 128)
                throw new IllegalArgumentException("providerId exceeds budget");
            Objects.requireNonNull(providerDisplayName, "providerDisplayName");
            if (providerDisplayName.codePointCount(0, providerDisplayName.length()) > 512)
                throw new IllegalArgumentException("providerDisplayName exceeds budget");
            capabilities = List.copyOf(capabilities);
            if (capabilities.size() > 64) throw new IllegalArgumentException("capabilities exceeds budget");
            if (capabilities.stream().anyMatch(item -> item.codePointCount(0, item.length()) > 64))
                throw new IllegalArgumentException("capabilities item exceeds budget");
            if (contextWindow < 0L || contextWindow > 9007199254740991L)
                throw new IllegalArgumentException("Invalid contextWindow");
            if (maxOutputTokens < 0L || maxOutputTokens > 9007199254740991L)
                throw new IllegalArgumentException("Invalid maxOutputTokens");
            Objects.requireNonNull(connection, "connection");
            if (connection.codePointCount(0, connection.length()) > 64)
                throw new IllegalArgumentException("connection exceeds budget");
            Objects.requireNonNull(bindingAvailability, "bindingAvailability");
            if (bindingAvailability.codePointCount(0, bindingAvailability.length()) > 64)
                throw new IllegalArgumentException("bindingAvailability exceeds budget");
            Objects.requireNonNull(runtimeHealth, "runtimeHealth");
            if (runtimeHealth.codePointCount(0, runtimeHealth.length()) > 64)
                throw new IllegalArgumentException("runtimeHealth exceeds budget");
            Objects.requireNonNull(runScope, "runScope");
            if (runScope.codePointCount(0, runScope.length()) > 64)
                throw new IllegalArgumentException("runScope exceeds budget");
            if (unavailableReason != null) {
                Objects.requireNonNull(unavailableReason, "unavailableReason");
                if (unavailableReason.codePointCount(0, unavailableReason.length()) > 512)
                    throw new IllegalArgumentException("unavailableReason exceeds budget");
            }
            Objects.requireNonNull(responseMode, "responseMode");
            Objects.requireNonNull(reasoningEffort, "reasoningEffort");
            Objects.requireNonNull(recommendedResponseMode, "recommendedResponseMode");
            if (recommendedResponseMode.codePointCount(0, recommendedResponseMode.length()) > 64)
                throw new IllegalArgumentException("recommendedResponseMode exceeds budget");
            if (recommendedEffort != null) {
                Objects.requireNonNull(recommendedEffort, "recommendedEffort");
                if (recommendedEffort.codePointCount(0, recommendedEffort.length()) > 64)
                    throw new IllegalArgumentException("recommendedEffort exceeds budget");
            }
            if (imageInput != null) {
                Objects.requireNonNull(imageInput, "imageInput");
            }
        }
    }

    public record ModelSelection(ModelChoice model, long revision, boolean available) {
        public ModelSelection {
            Objects.requireNonNull(model, "model");
            if (revision < 0L || revision > 9007199254740991L) throw new IllegalArgumentException("Invalid revision");
        }
    }

    public record QueuedMessage(String id, String sessionId, String summary, long sequence, long revision) {
        public QueuedMessage {
            Objects.requireNonNull(id, "id");
            if (id.isEmpty()) throw new IllegalArgumentException("Empty id");
            if (id.codePointCount(0, id.length()) > 256) throw new IllegalArgumentException("id exceeds budget");
            Objects.requireNonNull(sessionId, "sessionId");
            if (sessionId.isEmpty()) throw new IllegalArgumentException("Empty sessionId");
            if (sessionId.codePointCount(0, sessionId.length()) > 256)
                throw new IllegalArgumentException("sessionId exceeds budget");
            Objects.requireNonNull(summary, "summary");
            if (summary.codePointCount(0, summary.length()) > 16384)
                throw new IllegalArgumentException("summary exceeds budget");
            if (sequence < 0L || sequence > 9007199254740991L) throw new IllegalArgumentException("Invalid sequence");
            if (revision < 0L || revision > 9007199254740991L) throw new IllegalArgumentException("Invalid revision");
        }
    }

    public record Attachment(String id, String mediaType, String filename) {
        public Attachment {
            Objects.requireNonNull(id, "id");
            if (id.isEmpty()) throw new IllegalArgumentException("Empty id");
            if (id.codePointCount(0, id.length()) > 256) throw new IllegalArgumentException("id exceeds budget");
            Objects.requireNonNull(mediaType, "mediaType");
            if (mediaType.codePointCount(0, mediaType.length()) > 128)
                throw new IllegalArgumentException("mediaType exceeds budget");
            Objects.requireNonNull(filename, "filename");
            if (filename.codePointCount(0, filename.length()) > 512)
                throw new IllegalArgumentException("filename exceeds budget");
        }
    }

    public record RestoredMessage(
            String id, String sessionId, String text, List<Attachment> attachments, long revision) {
        public RestoredMessage {
            Objects.requireNonNull(id, "id");
            if (id.isEmpty()) throw new IllegalArgumentException("Empty id");
            if (id.codePointCount(0, id.length()) > 256) throw new IllegalArgumentException("id exceeds budget");
            Objects.requireNonNull(sessionId, "sessionId");
            if (sessionId.isEmpty()) throw new IllegalArgumentException("Empty sessionId");
            if (sessionId.codePointCount(0, sessionId.length()) > 256)
                throw new IllegalArgumentException("sessionId exceeds budget");
            Objects.requireNonNull(text, "text");
            if (text.codePointCount(0, text.length()) > 65536)
                throw new IllegalArgumentException("text exceeds budget");
            attachments = List.copyOf(attachments);
            if (attachments.size() > 32) throw new IllegalArgumentException("attachments exceeds budget");
            if (revision < 0L || revision > 9007199254740991L) throw new IllegalArgumentException("Invalid revision");
        }
    }

    public record ShellPlan(
            String token,
            String sessionId,
            String safeCommand,
            boolean includeInContext,
            String state,
            String reasonCode) {
        public ShellPlan {
            Objects.requireNonNull(token, "token");
            if (token.isEmpty()) throw new IllegalArgumentException("Empty token");
            if (token.codePointCount(0, token.length()) > 256)
                throw new IllegalArgumentException("token exceeds budget");
            Objects.requireNonNull(sessionId, "sessionId");
            if (sessionId.isEmpty()) throw new IllegalArgumentException("Empty sessionId");
            if (sessionId.codePointCount(0, sessionId.length()) > 256)
                throw new IllegalArgumentException("sessionId exceeds budget");
            Objects.requireNonNull(safeCommand, "safeCommand");
            if (safeCommand.codePointCount(0, safeCommand.length()) > 4096)
                throw new IllegalArgumentException("safeCommand exceeds budget");
            Objects.requireNonNull(state, "state");
            if (state.codePointCount(0, state.length()) > 64)
                throw new IllegalArgumentException("state exceeds budget");
            Objects.requireNonNull(reasonCode, "reasonCode");
            if (reasonCode.codePointCount(0, reasonCode.length()) > 256)
                throw new IllegalArgumentException("reasonCode exceeds budget");
        }
    }

    public record ShellResult(
            String processState,
            Long exitCode,
            String safeSummary,
            String outputReference,
            boolean truncated,
            boolean includedInContext) {
        public ShellResult {
            Objects.requireNonNull(processState, "processState");
            if (processState.codePointCount(0, processState.length()) > 64)
                throw new IllegalArgumentException("processState exceeds budget");
            if (exitCode != null) {
                if (exitCode < -2147483648L || exitCode > 2147483647L)
                    throw new IllegalArgumentException("Invalid exitCode");
            }
            Objects.requireNonNull(safeSummary, "safeSummary");
            if (safeSummary.codePointCount(0, safeSummary.length()) > 16384)
                throw new IllegalArgumentException("safeSummary exceeds budget");
            if (outputReference != null) {
                Objects.requireNonNull(outputReference, "outputReference");
                if (outputReference.isEmpty()) throw new IllegalArgumentException("Empty outputReference");
                if (outputReference.codePointCount(0, outputReference.length()) > 256)
                    throw new IllegalArgumentException("outputReference exceeds budget");
            }
        }
    }

    public record ShellExecution(
            String id,
            String sessionId,
            long acceptedAtEpochMillis,
            String state,
            boolean cancellationRequested,
            ShellResult result,
            String errorCode) {
        public ShellExecution {
            Objects.requireNonNull(id, "id");
            if (id.isEmpty()) throw new IllegalArgumentException("Empty id");
            if (id.codePointCount(0, id.length()) > 256) throw new IllegalArgumentException("id exceeds budget");
            Objects.requireNonNull(sessionId, "sessionId");
            if (sessionId.isEmpty()) throw new IllegalArgumentException("Empty sessionId");
            if (sessionId.codePointCount(0, sessionId.length()) > 256)
                throw new IllegalArgumentException("sessionId exceeds budget");
            if (acceptedAtEpochMillis < 0L || acceptedAtEpochMillis > 9007199254740991L)
                throw new IllegalArgumentException("Invalid acceptedAtEpochMillis");
            Objects.requireNonNull(state, "state");
            if (state.codePointCount(0, state.length()) > 64)
                throw new IllegalArgumentException("state exceeds budget");
            if (result != null) {
                Objects.requireNonNull(result, "result");
            }
            if (errorCode != null) {
                Objects.requireNonNull(errorCode, "errorCode");
                if (errorCode.codePointCount(0, errorCode.length()) > 128)
                    throw new IllegalArgumentException("errorCode exceeds budget");
            }
        }
    }

    public record Workspace(String id, String safeDisplayName, String mode, String status, boolean revocable) {
        public Workspace {
            Objects.requireNonNull(id, "id");
            if (id.isEmpty()) throw new IllegalArgumentException("Empty id");
            if (id.codePointCount(0, id.length()) > 256) throw new IllegalArgumentException("id exceeds budget");
            Objects.requireNonNull(safeDisplayName, "safeDisplayName");
            if (safeDisplayName.codePointCount(0, safeDisplayName.length()) > 512)
                throw new IllegalArgumentException("safeDisplayName exceeds budget");
            Objects.requireNonNull(mode, "mode");
            if (mode.codePointCount(0, mode.length()) > 64) throw new IllegalArgumentException("mode exceeds budget");
            Objects.requireNonNull(status, "status");
            if (status.codePointCount(0, status.length()) > 64)
                throw new IllegalArgumentException("status exceeds budget");
        }
    }

    public record Compaction(
            String summaryReference,
            long summaryVersion,
            long sourceMessageCount,
            long estimatedTokens,
            long throughSequence) {
        public Compaction {
            if (summaryReference != null) {
                Objects.requireNonNull(summaryReference, "summaryReference");
                if (summaryReference.isEmpty()) throw new IllegalArgumentException("Empty summaryReference");
                if (summaryReference.codePointCount(0, summaryReference.length()) > 256)
                    throw new IllegalArgumentException("summaryReference exceeds budget");
            }
            if (summaryVersion < 0L || summaryVersion > 9007199254740991L)
                throw new IllegalArgumentException("Invalid summaryVersion");
            if (sourceMessageCount < 0L || sourceMessageCount > 9007199254740991L)
                throw new IllegalArgumentException("Invalid sourceMessageCount");
            if (estimatedTokens < 0L || estimatedTokens > 9007199254740991L)
                throw new IllegalArgumentException("Invalid estimatedTokens");
            if (throughSequence < 0L || throughSequence > 9007199254740991L)
                throw new IllegalArgumentException("Invalid throughSequence");
        }
    }

    public record Export(String logicalPath, long messageCount, String schemaVersion) {
        public Export {
            Objects.requireNonNull(logicalPath, "logicalPath");
            if (logicalPath.codePointCount(0, logicalPath.length()) > 4096)
                throw new IllegalArgumentException("logicalPath exceeds budget");
            if (messageCount < 0L || messageCount > 9007199254740991L)
                throw new IllegalArgumentException("Invalid messageCount");
            Objects.requireNonNull(schemaVersion, "schemaVersion");
            if (schemaVersion.codePointCount(0, schemaVersion.length()) > 64)
                throw new IllegalArgumentException("schemaVersion exceeds budget");
        }
    }

    public record AuthenticationMethod(String id, String type, boolean available, String unavailableReason) {
        public AuthenticationMethod {
            Objects.requireNonNull(id, "id");
            if (id.isEmpty()) throw new IllegalArgumentException("Empty id");
            if (id.codePointCount(0, id.length()) > 256) throw new IllegalArgumentException("id exceeds budget");
            Objects.requireNonNull(type, "type");
            if (type.codePointCount(0, type.length()) > 64) throw new IllegalArgumentException("type exceeds budget");
            if (unavailableReason != null) {
                Objects.requireNonNull(unavailableReason, "unavailableReason");
                if (unavailableReason.codePointCount(0, unavailableReason.length()) > 512)
                    throw new IllegalArgumentException("unavailableReason exceeds budget");
            }
        }
    }

    public record AuthenticationProvider(
            String id,
            String displayName,
            String category,
            boolean connected,
            boolean recommended,
            boolean current,
            String accountLabel,
            String planLabel,
            List<AuthenticationMethod> methods,
            String unavailableReason) {
        public AuthenticationProvider {
            Objects.requireNonNull(id, "id");
            if (id.isEmpty()) throw new IllegalArgumentException("Empty id");
            if (id.codePointCount(0, id.length()) > 256) throw new IllegalArgumentException("id exceeds budget");
            Objects.requireNonNull(displayName, "displayName");
            if (displayName.codePointCount(0, displayName.length()) > 512)
                throw new IllegalArgumentException("displayName exceeds budget");
            Objects.requireNonNull(category, "category");
            if (category.codePointCount(0, category.length()) > 64)
                throw new IllegalArgumentException("category exceeds budget");
            if (accountLabel != null) {
                Objects.requireNonNull(accountLabel, "accountLabel");
                if (accountLabel.codePointCount(0, accountLabel.length()) > 512)
                    throw new IllegalArgumentException("accountLabel exceeds budget");
            }
            if (planLabel != null) {
                Objects.requireNonNull(planLabel, "planLabel");
                if (planLabel.codePointCount(0, planLabel.length()) > 512)
                    throw new IllegalArgumentException("planLabel exceeds budget");
            }
            methods = List.copyOf(methods);
            if (methods.size() > 16) throw new IllegalArgumentException("methods exceeds budget");
            if (unavailableReason != null) {
                Objects.requireNonNull(unavailableReason, "unavailableReason");
                if (unavailableReason.codePointCount(0, unavailableReason.length()) > 512)
                    throw new IllegalArgumentException("unavailableReason exceeds budget");
            }
        }
    }

    public record AuthenticationConnection(
            String id,
            String providerId,
            String method,
            String status,
            String accountLabel,
            String planType,
            Long resetAtEpochMillis,
            boolean unofficialLocalCompatibility) {
        public AuthenticationConnection {
            Objects.requireNonNull(id, "id");
            if (id.isEmpty()) throw new IllegalArgumentException("Empty id");
            if (id.codePointCount(0, id.length()) > 256) throw new IllegalArgumentException("id exceeds budget");
            Objects.requireNonNull(providerId, "providerId");
            if (providerId.isEmpty()) throw new IllegalArgumentException("Empty providerId");
            if (providerId.codePointCount(0, providerId.length()) > 256)
                throw new IllegalArgumentException("providerId exceeds budget");
            Objects.requireNonNull(method, "method");
            if (method.codePointCount(0, method.length()) > 64)
                throw new IllegalArgumentException("method exceeds budget");
            Objects.requireNonNull(status, "status");
            if (status.codePointCount(0, status.length()) > 64)
                throw new IllegalArgumentException("status exceeds budget");
            Objects.requireNonNull(accountLabel, "accountLabel");
            if (accountLabel.codePointCount(0, accountLabel.length()) > 512)
                throw new IllegalArgumentException("accountLabel exceeds budget");
            if (planType != null) {
                Objects.requireNonNull(planType, "planType");
                if (planType.codePointCount(0, planType.length()) > 512)
                    throw new IllegalArgumentException("planType exceeds budget");
            }
            if (resetAtEpochMillis != null) {
                if (resetAtEpochMillis < 0L || resetAtEpochMillis > 9007199254740991L)
                    throw new IllegalArgumentException("Invalid resetAtEpochMillis");
            }
        }
    }

    public record Authentication(
            boolean connectionRequired,
            List<AuthenticationProvider> providers,
            List<AuthenticationConnection> connections,
            String localManagementCommand) {
        public Authentication {
            providers = List.copyOf(providers);
            if (providers.size() > 64) throw new IllegalArgumentException("providers exceeds budget");
            connections = List.copyOf(connections);
            if (connections.size() > 64) throw new IllegalArgumentException("connections exceeds budget");
            if (localManagementCommand != null) {
                Objects.requireNonNull(localManagementCommand, "localManagementCommand");
                if (localManagementCommand.codePointCount(0, localManagementCommand.length()) > 4096)
                    throw new IllegalArgumentException("localManagementCommand exceeds budget");
            }
        }
    }

    public record QuestionAnswer(String header, List<String> optionIds, String customText) {
        public QuestionAnswer {
            Objects.requireNonNull(header, "header");
            if (header.codePointCount(0, header.length()) > 32)
                throw new IllegalArgumentException("header exceeds budget");
            optionIds = List.copyOf(optionIds);
            if (optionIds.size() > 100) throw new IllegalArgumentException("optionIds exceeds budget");
            if (optionIds.stream().anyMatch(item -> item.codePointCount(0, item.length()) > 512))
                throw new IllegalArgumentException("optionIds item exceeds budget");
            if (customText != null) {
                Objects.requireNonNull(customText, "customText");
                if (customText.codePointCount(0, customText.length()) > 65536)
                    throw new IllegalArgumentException("customText exceeds budget");
            }
        }
    }

    public record CommandOptions(boolean approved, boolean includeInContext, List<QuestionAnswer> answers) {
        public CommandOptions {
            answers = List.copyOf(answers);
            if (answers.size() > 3) throw new IllegalArgumentException("answers exceeds budget");
        }
    }

    public record CommandRequest(Command command, CommandOptions options) {
        public CommandRequest {
            Objects.requireNonNull(command, "command");
            Objects.requireNonNull(options, "options");
        }
    }

    public record InteractionReceipt(
            String interactionId,
            String runId,
            String status,
            String state,
            long interactionRevision,
            long runRevision) {
        public InteractionReceipt {
            Objects.requireNonNull(interactionId, "interactionId");
            if (interactionId.isEmpty()) throw new IllegalArgumentException("Empty interactionId");
            if (interactionId.codePointCount(0, interactionId.length()) > 256)
                throw new IllegalArgumentException("interactionId exceeds budget");
            Objects.requireNonNull(runId, "runId");
            if (runId.isEmpty()) throw new IllegalArgumentException("Empty runId");
            if (runId.codePointCount(0, runId.length()) > 256)
                throw new IllegalArgumentException("runId exceeds budget");
            Objects.requireNonNull(status, "status");
            if (status.codePointCount(0, status.length()) > 64)
                throw new IllegalArgumentException("status exceeds budget");
            Objects.requireNonNull(state, "state");
            if (state.codePointCount(0, state.length()) > 64)
                throw new IllegalArgumentException("state exceeds budget");
            if (interactionRevision < 0L || interactionRevision > 9007199254740991L)
                throw new IllegalArgumentException("Invalid interactionRevision");
            if (runRevision < 0L || runRevision > 9007199254740991L)
                throw new IllegalArgumentException("Invalid runRevision");
        }
    }

    public record CommandResult(
            Receipt receipt,
            SessionSummary session,
            QueuedMessage queuedMessage,
            RestoredMessage restoredMessage,
            ShellPlan shellPlan,
            ShellExecution shellExecution,
            ShellResult shellResult,
            Compaction compaction,
            Export export,
            ModelSelection modelSelection,
            InteractionReceipt interactionReceipt,
            Entry interaction) {
        public CommandResult {
            Objects.requireNonNull(receipt, "receipt");
            if (session != null) {
                Objects.requireNonNull(session, "session");
            }
            if (queuedMessage != null) {
                Objects.requireNonNull(queuedMessage, "queuedMessage");
            }
            if (restoredMessage != null) {
                Objects.requireNonNull(restoredMessage, "restoredMessage");
            }
            if (shellPlan != null) {
                Objects.requireNonNull(shellPlan, "shellPlan");
            }
            if (shellExecution != null) {
                Objects.requireNonNull(shellExecution, "shellExecution");
            }
            if (shellResult != null) {
                Objects.requireNonNull(shellResult, "shellResult");
            }
            if (compaction != null) {
                Objects.requireNonNull(compaction, "compaction");
            }
            if (export != null) {
                Objects.requireNonNull(export, "export");
            }
            if (modelSelection != null) {
                Objects.requireNonNull(modelSelection, "modelSelection");
            }
            if (interactionReceipt != null) {
                Objects.requireNonNull(interactionReceipt, "interactionReceipt");
            }
            if (interaction != null) {
                Objects.requireNonNull(interaction, "interaction");
            }
        }
    }

    public record Query(String operation, CommandTarget target, String search, long limit, String executionId) {
        public Query {
            Objects.requireNonNull(operation, "operation");
            if (operation.isEmpty()) throw new IllegalArgumentException("Empty operation");
            if (operation.codePointCount(0, operation.length()) > 64)
                throw new IllegalArgumentException("operation exceeds budget");
            if (!java.util.Set.of(
                            "sessions",
                            "run",
                            "outcome",
                            "models",
                            "queuedMessages",
                            "shellGet",
                            "shellList",
                            "workspaces",
                            "logicalPaths",
                            "resources",
                            "configuration",
                            "authentication")
                    .contains(operation)) throw new IllegalArgumentException("Unknown operation");
            Objects.requireNonNull(target, "target");
            if (search != null) {
                Objects.requireNonNull(search, "search");
                if (search.codePointCount(0, search.length()) > 256)
                    throw new IllegalArgumentException("search exceeds budget");
            }
            if (limit < 1L || limit > 100L) throw new IllegalArgumentException("Invalid limit");
            if (executionId != null) {
                Objects.requireNonNull(executionId, "executionId");
                if (executionId.isEmpty()) throw new IllegalArgumentException("Empty executionId");
                if (executionId.codePointCount(0, executionId.length()) > 256)
                    throw new IllegalArgumentException("executionId exceeds budget");
            }
        }
    }

    public record QueryResult(
            List<SessionSummary> sessions,
            RunFact run,
            RunOutcome outcome,
            List<ModelChoice> models,
            List<QueuedMessage> queuedMessages,
            List<ShellExecution> shellExecutions,
            List<Workspace> workspaces,
            List<String> lines,
            Authentication authentication,
            boolean truncated) {
        public QueryResult {
            sessions = List.copyOf(sessions);
            if (sessions.size() > 100) throw new IllegalArgumentException("sessions exceeds budget");
            if (run != null) {
                Objects.requireNonNull(run, "run");
            }
            if (outcome != null) {
                Objects.requireNonNull(outcome, "outcome");
            }
            models = List.copyOf(models);
            if (models.size() > 100) throw new IllegalArgumentException("models exceeds budget");
            queuedMessages = List.copyOf(queuedMessages);
            if (queuedMessages.size() > 100) throw new IllegalArgumentException("queuedMessages exceeds budget");
            shellExecutions = List.copyOf(shellExecutions);
            if (shellExecutions.size() > 128) throw new IllegalArgumentException("shellExecutions exceeds budget");
            workspaces = List.copyOf(workspaces);
            if (workspaces.size() > 100) throw new IllegalArgumentException("workspaces exceeds budget");
            lines = List.copyOf(lines);
            if (lines.size() > 100) throw new IllegalArgumentException("lines exceeds budget");
            if (lines.stream().anyMatch(item -> item.codePointCount(0, item.length()) > 4096))
                throw new IllegalArgumentException("lines item exceeds budget");
            if (authentication != null) {
                Objects.requireNonNull(authentication, "authentication");
            }
        }
    }

    public record PairingRequest(String challenge, String hostIncarnation) {
        public PairingRequest {
            Objects.requireNonNull(challenge, "challenge");
            if (challenge.isEmpty()) throw new IllegalArgumentException("Empty challenge");
            if (challenge.codePointCount(0, challenge.length()) > 256)
                throw new IllegalArgumentException("challenge exceeds budget");
            Objects.requireNonNull(hostIncarnation, "hostIncarnation");
            if (hostIncarnation.isEmpty()) throw new IllegalArgumentException("Empty hostIncarnation");
            if (hostIncarnation.codePointCount(0, hostIncarnation.length()) > 256)
                throw new IllegalArgumentException("hostIncarnation exceeds budget");
        }

        @Override
        public String toString() {
            return "PairingRequest[redacted]";
        }
    }

    public record BrowserGrant(String grant, String clientId, String hostIncarnation, long expiresAtEpochMillis) {
        public BrowserGrant {
            Objects.requireNonNull(grant, "grant");
            if (grant.isEmpty()) throw new IllegalArgumentException("Empty grant");
            if (grant.codePointCount(0, grant.length()) > 256)
                throw new IllegalArgumentException("grant exceeds budget");
            Objects.requireNonNull(clientId, "clientId");
            if (clientId.isEmpty()) throw new IllegalArgumentException("Empty clientId");
            if (clientId.codePointCount(0, clientId.length()) > 256)
                throw new IllegalArgumentException("clientId exceeds budget");
            Objects.requireNonNull(hostIncarnation, "hostIncarnation");
            if (hostIncarnation.isEmpty()) throw new IllegalArgumentException("Empty hostIncarnation");
            if (hostIncarnation.codePointCount(0, hostIncarnation.length()) > 256)
                throw new IllegalArgumentException("hostIncarnation exceeds budget");
            if (expiresAtEpochMillis < 1L || expiresAtEpochMillis > 9007199254740991L)
                throw new IllegalArgumentException("Invalid expiresAtEpochMillis");
        }

        @Override
        public String toString() {
            return "BrowserGrant[redacted]";
        }
    }

    public record BrowserInfo(
            String hostIncarnation, String protocolVersion, String browserAuthorization, String displayName) {
        public BrowserInfo {
            Objects.requireNonNull(hostIncarnation, "hostIncarnation");
            if (hostIncarnation.isEmpty()) throw new IllegalArgumentException("Empty hostIncarnation");
            if (hostIncarnation.codePointCount(0, hostIncarnation.length()) > 256)
                throw new IllegalArgumentException("hostIncarnation exceeds budget");
            Objects.requireNonNull(protocolVersion, "protocolVersion");
            if (!(protocolVersion.equals("1"))) throw new IllegalArgumentException("Invalid protocolVersion");
            Objects.requireNonNull(browserAuthorization, "browserAuthorization");
            if (!(browserAuthorization.equals("browser-grant-v1")))
                throw new IllegalArgumentException("Invalid browserAuthorization");
            Objects.requireNonNull(displayName, "displayName");
            if (displayName.codePointCount(0, displayName.length()) > 256)
                throw new IllegalArgumentException("displayName exceeds budget");
        }
    }

    public sealed interface Envelope permits Snapshot, Update, Resync {}
}

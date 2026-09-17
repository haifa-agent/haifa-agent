package io.haifa.agent.sdk.api;

import static org.assertj.core.api.Assertions.assertThat;

import io.haifa.agent.runtime.core.storage.RuntimePersistencePorts;
import io.haifa.agent.sdk.SdkTestFixtures;
import io.haifa.agent.sdk.contribution.InMemoryConversationContribution;
import io.haifa.agent.sdk.conversation.ConversationRecord;
import io.haifa.agent.sdk.conversation.ConversationRun;
import io.haifa.agent.sdk.conversation.InMemoryConversationStore;
import io.haifa.agent.sdk.conversation.StartConversationCommand;
import io.haifa.agent.sdk.conversation.SubmitConversationTurnCommand;
import io.haifa.agent.sdk.internal.InMemoryPersistenceContribution;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;

class ConcurrentConversationSubmitTest {

    @Test
    void concurrentSubmitOnTheSameRevisionStartsExactlyOneRun() throws Exception {
        var ports = RuntimePersistencePorts.inMemory();
        var conversations = new InMemoryConversationStore();
        try (HaifaAgent first = sharedAgent("concurrent-submit", ports, conversations);
                HaifaAgent second = sharedAgent("concurrent-submit", ports, conversations)) {
            ConversationRun started =
                    first.conversations().start(new StartConversationCommand("start-1", "Chat", "hello"));
            first.runs().await(started.runId());
            ConversationRecord idle =
                    first.conversations().find(started.record().sessionId()).orElseThrow();

            ExecutorService executor = Executors.newFixedThreadPool(2);
            try {
                CountDownLatch gate = new CountDownLatch(1);
                Callable<Object> firstSubmit = () -> {
                    gate.await();
                    return submit(first, idle, "first-key", "first-message");
                };
                Callable<Object> secondSubmit = () -> {
                    gate.await();
                    return submit(second, idle, "second-key", "second-message");
                };
                Future<Object> firstFuture = executor.submit(firstSubmit);
                Future<Object> secondFuture = executor.submit(secondSubmit);
                gate.countDown();
                Object firstOutcome = firstFuture.get();
                Object secondOutcome = secondFuture.get();
                List<Object> results = List.of(firstOutcome, secondOutcome);

                assertThat(results.stream().filter(ConversationRun.class::isInstance))
                        .as("exactly one concurrent submit must win: %s", results)
                        .hasSize(1);
                assertThat(results.stream()
                                .filter(RuntimeException.class::isInstance)
                                .map(failure -> ((RuntimeException) failure).getMessage()))
                        .containsExactly("CONVERSATION_REVISION_STALE");

                boolean firstWon = firstOutcome instanceof ConversationRun;
                HaifaAgent winner = firstWon ? first : second;
                ConversationRun winnerRun = (ConversationRun) (firstWon ? firstOutcome : secondOutcome);
                winner.runs().await(winnerRun.runId());
            } finally {
                executor.shutdownNow();
            }
        }
    }

    private static HaifaAgent sharedAgent(
            String productId, RuntimePersistencePorts ports, InMemoryConversationStore conversations) {
        return HaifaAgents.builder(SdkTestFixtures.profile(productId))
                .model(SdkTestFixtures.modelContribution())
                .persistence(new InMemoryPersistenceContribution(ports))
                .conversation(new InMemoryConversationContribution(conversations))
                .build();
    }

    private static Object submit(HaifaAgent agent, ConversationRecord conversation, String key, String message) {
        try {
            return agent.conversations()
                    .submit(new SubmitConversationTurnCommand(
                            conversation.sessionId(), conversation.revision(), key, message));
        } catch (RuntimeException failure) {
            return failure;
        }
    }
}

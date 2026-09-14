package io.haifa.agent.personalassistant.server.web.v1.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.haifa.agent.personalassistant.application.PersonalAssistantApplication;
import io.haifa.agent.personalassistant.application.mission.MissionApplicationService;
import io.haifa.agent.personalassistant.application.mission.MissionException;
import io.haifa.agent.personalassistant.application.mission.MissionExecutionSnapshot;
import io.haifa.agent.personalassistant.application.mission.MissionMode;
import io.haifa.agent.personalassistant.application.mission.MissionSnapshot;
import io.haifa.agent.personalassistant.application.mission.MissionState;
import io.haifa.agent.personalassistant.server.configuration.product.PersonalAssistantProperties;
import io.haifa.agent.personalassistant.server.web.v1.dto.PersonalApiDtos;
import io.haifa.agent.personalassistant.server.web.v1.mapper.PersonalApiMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class PersonalMissionControllerTest {

    private MissionApplicationService missions;
    private PersonalAssistantApplication application;
    private PersonalAssistantProperties properties;
    private PersonalApiMapper mapper;
    private PersonalMissionController controller;

    @BeforeEach
    void setUp() {
        missions = mock(MissionApplicationService.class);
        application = mock(PersonalAssistantApplication.class);
        properties = mock(PersonalAssistantProperties.class);
        mapper = mock(PersonalApiMapper.class);
        var caller = new PersonalAssistantProperties.Caller("tenant-a", "user-b", "reviewer-c");
        var missionProps = mock(PersonalAssistantProperties.Mission.class);
        when(properties.caller()).thenReturn(caller);
        when(properties.mission()).thenReturn(missionProps);
        when(missionProps.maxAcceptanceCriteria()).thenReturn(20);
        when(missionProps.maxTasks()).thenReturn(8);
        when(missionProps.maxDependencyDepth()).thenReturn(4);
        when(missionProps.maxWallClockMillis()).thenReturn(3600000L);

        Clock clock = Clock.fixed(Instant.parse("2026-09-14T10:00:00Z"), ZoneOffset.UTC);
        controller = new PersonalMissionController(
                missions,
                application,
                properties,
                mapper,
                clock,
                new ObjectMapper());
    }

    @Test
    void createWithPreviousMissionIdFindsPreviousMissionByMissionIdAndOwnerScope() {
        String conversationId = "conv-1";
        String previousMissionId = "prev-mission-123";
        String expectedOwnerScope = "tenant-a/user-b";

        when(application.conversation(conversationId)).thenReturn(Optional.of(mock(PersonalAssistantApplication.ConversationView.class)));
        when(application.missionModelBinding(conversationId)).thenReturn(mock(io.haifa.agent.personalassistant.application.mission.MissionModelBinding.class));

        var previousSnapshot = mock(MissionSnapshot.class);
        when(previousSnapshot.missionId()).thenReturn(previousMissionId);
        when(previousSnapshot.conversationId()).thenReturn(conversationId);
        when(previousSnapshot.state()).thenReturn(MissionState.COMPLETED);
        var previousExec = mock(MissionExecutionSnapshot.class);
        when(previousSnapshot.execution()).thenReturn(previousExec);
        when(previousExec.finalResult()).thenReturn(Optional.of("{\"directAnswer\":\"Baseline answer\",\"unresolvedQuestions\":[\"Q1\"],\"unverifiedClaims\":[\"C1\"]}"));

        when(missions.find(eq(previousMissionId), eq(expectedOwnerScope)))
                .thenReturn(Optional.of(previousSnapshot));

        var createdSnapshot = mock(MissionSnapshot.class);
        var snapshotDto = mock(PersonalApiDtos.MissionSnapshot.class);
        when(snapshotDto.missionId()).thenReturn("new-mission-456");
        when(snapshotDto.version()).thenReturn(1L);
        when(missions.create(any())).thenReturn(createdSnapshot);
        when(mapper.mission(createdSnapshot)).thenReturn(snapshotDto);

        var request = new PersonalApiDtos.CreateMission(
                conversationId,
                "New Objective",
                List.of("Verify Q1"),
                null,
                "DEEP_RESEARCH",
                "deep-research",
                new PersonalApiDtos.ResearchBrief("Q", "S", "T", "R", "A", List.of("academic"), List.of("none"), "DF", null),
                previousMissionId);

        var response = controller.create("idemp-key-1", request);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().missionId()).isEqualTo("new-mission-456");

        // Verify that missions.find was called with previousMissionId FIRST, then expectedOwnerScope
        verify(missions).find(previousMissionId, expectedOwnerScope);

        // Verify that command was passed with prior context extracted
        ArgumentCaptor<MissionApplicationService.CreateMission> captor =
                ArgumentCaptor.forClass(MissionApplicationService.CreateMission.class);
        verify(missions).create(captor.capture());
        var command = captor.getValue();
        assertThat(command.researchBrief()).isPresent();
        assertThat(command.researchBrief().orElseThrow().optionalPriorContext()).isPresent();
        var priorContext = command.researchBrief().orElseThrow().optionalPriorContext().orElseThrow();
        assertThat(priorContext.previousMissionId()).isEqualTo(previousMissionId);
        assertThat(priorContext.directAnswer()).isEqualTo("Baseline answer");
        assertThat(priorContext.unresolvedQuestions()).containsExactly("Q1");
        assertThat(priorContext.unverifiedClaims()).containsExactly("C1");
    }

    @Test
    void createWithPreviousMissionIdThrowsWhenPreviousMissionNotFound() {
        String conversationId = "conv-1";
        String previousMissionId = "prev-mission-999";
        String expectedOwnerScope = "tenant-a/user-b";

        when(application.conversation(conversationId)).thenReturn(Optional.of(mock(PersonalAssistantApplication.ConversationView.class)));
        when(missions.find(eq(previousMissionId), eq(expectedOwnerScope))).thenReturn(Optional.empty());

        var request = new PersonalApiDtos.CreateMission(
                conversationId,
                "New Objective",
                List.of("Verify Q1"),
                null,
                "DEEP_RESEARCH",
                "deep-research",
                null,
                previousMissionId);

        assertThatThrownBy(() -> controller.create("idemp-key-1", request))
                .isInstanceOf(MissionException.class)
                .satisfies(ex -> {
                    MissionException me = (MissionException) ex;
                    assertThat(me.code()).isEqualTo("MISSION_NOT_FOUND");
                    assertThat(me.getMessage()).contains("Previous mission not found");
                });
    }
}

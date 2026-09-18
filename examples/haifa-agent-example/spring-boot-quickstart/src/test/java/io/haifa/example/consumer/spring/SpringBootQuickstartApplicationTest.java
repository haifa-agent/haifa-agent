package io.haifa.example.consumer.spring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.haifa.agent.sdk.api.HaifaAgent;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
    "example.runner.enabled=false",
    "example.live=false",
    "haifa.agent.model.credential-environment-variable=PATH"
})
@AutoConfigureMockMvc
class SpringBootQuickstartApplicationTest {
    @Autowired
    private HaifaAgent agent;

    @Autowired
    private OfficeHoursTool officeHoursTool;

    @Autowired
    private AgentChatController chatController;

    @Autowired
    private MockMvc mockMvc;

    @Test
    void startsContextAndDiscoversTypedToolBeanWithoutCallingProvider() {
        assertThat(agent.conversations()).isNotNull();
        assertThat(agent.runs()).isNotNull();
        assertThat(officeHoursTool.spec().alias().value()).isEqualTo("office_hours");
        assertThat(chatController).isNotNull();
    }

    @Test
    void rejectsEmptyPromptOnWebEndpointWithoutCallingProvider() throws Exception {
        mockMvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"prompt\": \"\"}"))
                .andExpect(status().isBadRequest());
    }
}

package io.haifa.example.consumer.spring;

import static org.assertj.core.api.Assertions.assertThat;

import io.haifa.agent.sdk.api.HaifaAgent;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {
    "example.live=false",
    "haifa.agent.model.credential-environment-variable=PATH",
    "spring.main.web-application-type=none"
})
class SpringBootQuickstartApplicationTest {
    @Autowired
    private HaifaAgent agent;

    @Autowired
    private OfficeHoursTool officeHoursTool;

    @Test
    void startsContextAndDiscoversTypedToolBeanWithoutCallingProvider() {
        assertThat(agent.conversations()).isNotNull();
        assertThat(agent.runs()).isNotNull();
        assertThat(officeHoursTool.spec().alias().value()).isEqualTo("office_hours");
    }
}

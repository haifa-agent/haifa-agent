package io.haifa.agent.application.project.tool.web;

import java.net.URI;
import java.util.Map;

public interface WebUrlPolicy {
    String policyId();

    String policyVersion();

    Map<String, String> configuration();

    WebUrlDecision evaluate(URI url);
}

package io.haifa.agent.personalassistant.application.research;

import java.util.List;

/** Read-only Port for retrieving completed, code-authoritative web_fetch evidence for a Run. */
public interface ResearchFetchEvidenceReader {
    List<ResearchFetchEvidence> findCompletedFetches(String runId);
}

package io.haifa.agent.testing;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathFactory;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

class TestingModuleArchitectureTest {
    private static final String TESTING_DIRECTORY = "haifa-agent-testing";

    @Test
    void productionModulesDoNotDependOnTestingModules() throws Exception {
        Path repositoryRoot = findRepositoryRoot();
        List<String> violations = new ArrayList<>();

        try (var paths = Files.walk(repositoryRoot)) {
            for (Path pom : paths.filter(path -> path.getFileName().toString().equals("pom.xml"))
                    .filter(path -> !isBuildOutput(repositoryRoot, path))
                    .filter(path -> !isTestingModule(repositoryRoot, path))
                    .toList()) {
                inspectDirectDependencies(repositoryRoot, pom, violations);
            }
        }

        assertTrue(
                violations.isEmpty(),
                () -> "production modules must not depend on testing modules:\n" + String.join("\n", violations));
    }

    private static void inspectDirectDependencies(Path repositoryRoot, Path pom, List<String> violations)
            throws Exception {
        var factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");

        var document = factory.newDocumentBuilder().parse(pom.toFile());
        var xpath = XPathFactory.newInstance().newXPath();
        NodeList dependencies = (NodeList) xpath.evaluate(
                "/*[local-name()='project']/*[local-name()='dependencies']/*[local-name()='dependency']",
                document,
                XPathConstants.NODESET);
        for (int index = 0; index < dependencies.getLength(); index++) {
            Element dependency = (Element) dependencies.item(index);
            String groupId = childText(dependency, "groupId");
            String artifactId = childText(dependency, "artifactId");
            if ("io.haifa".equals(groupId) && isTestingArtifact(artifactId)) {
                violations.add(repositoryRoot.relativize(pom) + " -> " + groupId + ":" + artifactId);
            }
        }
    }

    private static String childText(Element parent, String localName) {
        NodeList children = parent.getElementsByTagName(localName);
        return children.getLength() == 0
                ? ""
                : children.item(0).getTextContent().trim();
    }

    private static boolean isTestingArtifact(String artifactId) {
        return artifactId.equals("haifa-agent-test-harness")
                || artifactId.equals("haifa-agent-test-fixtures")
                || artifactId.equals("haifa-agent-integration-tests")
                || artifactId.equals("haifa-agent-e2e-tests");
    }

    private static boolean isBuildOutput(Path root, Path path) {
        for (Path segment : root.relativize(path).normalize()) {
            if (segment.toString().equals("target")) {
                return true;
            }
        }
        return false;
    }

    private static boolean isTestingModule(Path root, Path path) {
        Path relative = root.relativize(path.toAbsolutePath()).normalize();
        return relative.startsWith(TESTING_DIRECTORY);
    }

    private static Path findRepositoryRoot() {
        Path current =
                Path.of(System.getProperty("basedir", ".")).toAbsolutePath().normalize();
        while (current != null) {
            if (Files.isDirectory(current.resolve(".mvn")) && Files.isRegularFile(current.resolve("pom.xml"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("cannot locate repository root from Maven basedir");
    }
}

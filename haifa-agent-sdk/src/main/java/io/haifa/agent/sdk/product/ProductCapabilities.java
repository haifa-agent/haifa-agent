package io.haifa.agent.sdk.product;

public final class ProductCapabilities {
    public static final ProductCapabilityId MODEL = new ProductCapabilityId("model");
    public static final ProductCapabilityId PERSISTENCE = new ProductCapabilityId("persistence");
    public static final ProductCapabilityId CONVERSATION = new ProductCapabilityId("conversation");
    public static final ProductCapabilityId TOOL = new ProductCapabilityId("tool");
    public static final ProductCapabilityId SKILL = new ProductCapabilityId("skill");
    public static final ProductCapabilityId MCP = new ProductCapabilityId("mcp");
    public static final ProductCapabilityId CONTEXT = new ProductCapabilityId("context");
    public static final ProductCapabilityId MEMORY = new ProductCapabilityId("memory");
    public static final ProductCapabilityId ARTIFACT = new ProductCapabilityId("artifact");
    public static final ProductCapabilityId POLICY = new ProductCapabilityId("policy");
    public static final ProductCapabilityId APPROVAL = new ProductCapabilityId("approval");
    public static final ProductCapabilityId CREDENTIAL = new ProductCapabilityId("credential");
    public static final ProductCapabilityId PROJECT = new ProductCapabilityId("project");
    public static final ProductCapabilityId WORKSPACE = new ProductCapabilityId("workspace");
    public static final ProductCapabilityId GIT = new ProductCapabilityId("git");
    public static final ProductCapabilityId SHELL = new ProductCapabilityId("shell");
    public static final ProductCapabilityId EXECUTION = new ProductCapabilityId("execution");
    /** Optional workflow graph orchestration; existing product profiles do not require it. */
    public static final ProductCapabilityId GRAPH = new ProductCapabilityId("graph");

    private ProductCapabilities() {}
}

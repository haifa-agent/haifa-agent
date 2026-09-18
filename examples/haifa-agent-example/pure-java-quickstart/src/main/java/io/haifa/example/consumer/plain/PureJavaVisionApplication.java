package io.haifa.example.consumer.plain;

import io.haifa.agent.core.content.StoredImageContentPart;
import io.haifa.agent.sdk.api.InMemoryImageStore;
import io.haifa.agent.starter.HaifaAgentStarter;
import java.io.InputStream;

/**
 * Multimodal vision example using DeepSeek Vision model (deepseek-v4-flash-vision-exp).
 *
 * <p>Demonstrates direct image data upload (Base64 data URI), without relying on external image URLs.
 */
public final class PureJavaVisionApplication {
    private static final String VISION_MODEL_ID = "deepseek-v4-flash-vision-exp";
    private static final String IMAGE_RESOURCE = "/fixtures/indoor-door-people.webp";

    private PureJavaVisionApplication() {}

    public static void main(String[] arguments) throws Exception {
        byte[] imageBytes = loadImageBytes();
        var imageStore = new InMemoryImageStore();
        StoredImageContentPart imagePart =
                imageStore.store(imageBytes, "image/webp", "indoor-door-people.webp");

        System.out.println("=== Haifa Agent 视觉多模态示例 (DeepSeek Vision) ===");
        System.out.println("已加载本地图片: indoor-door-people.webp (" + imageBytes.length + " bytes)");
        System.out.println("上传方式: 图片数据直接上传 (Data URI Base64，无需外网图片 URL)");
        System.out.println("使用模型: " + VISION_MODEL_ID);
        System.out.println("正在请求大模型解析图片故事...\n");

        try (var agent = HaifaAgentStarter.builder()
                .name("vision-story-agent")
                .instructions("你是一个富有洞察力的视觉分析助手。请根据用户提供的图片，生动、准确地解析画面中发生的故事。")
                .defaultModel(VISION_MODEL_ID)
                .modelImageResolver(imageStore)
                .build()) {

            var response = agent.chat(
                    "请仔细观察这张图片，详细解释画面中正在发生的故事。描述场景环境、人物的动作与神态，并推断他们正在做什么。",
                    imagePart)
                    .await();

            System.out.println("--- 大模型解析结果 ---");
            System.out.println(response.text());
        }
    }

    public static byte[] loadImageBytes() throws Exception {
        try (InputStream stream = PureJavaVisionApplication.class.getResourceAsStream(IMAGE_RESOURCE)) {
            if (stream != null) {
                return stream.readAllBytes();
            }
        }
        java.nio.file.Path fallback = java.nio.file.Path.of(
                "examples/haifa-agent-example/pure-java-quickstart/src/main/resources/fixtures/indoor-door-people.webp");
        if (java.nio.file.Files.isRegularFile(fallback)) {
            return java.nio.file.Files.readAllBytes(fallback);
        }
        throw new IllegalStateException("Fixture image not found: " + IMAGE_RESOURCE);
    }
}
package sample;

public final class ArticleServiceTest {
    public static void main(String[] args) {
        if (!new ArticleService().articlePath("Hello Agent").equals("/articles/hello-agent")) {
            throw new AssertionError("article path changed");
        }
    }
}

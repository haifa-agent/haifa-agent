package sample;

public final class ArticleService {
    public String articlePath(String title) {
        return "/articles/" + LegacySlugger.slug(title);
    }
}

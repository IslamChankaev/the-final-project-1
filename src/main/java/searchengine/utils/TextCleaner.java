package searchengine.utils;

import org.jsoup.Jsoup;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Pattern;

@Component
public class TextCleaner {

    public String cleanHtml(String html) {
        if (html == null || html.isEmpty()) {
            return "";
        }


        String text = Jsoup.parse(html).text();


        text = text.replaceAll("\\s+", " ").trim();

        return text;
    }

    public String extractTitle(String html) {
        if (html == null || html.isEmpty()) {
            return "";
        }

        try {
            return Jsoup.parse(html).title();
        } catch (Exception e) {
            return "";
        }
    }

    public String getSnippet(String text, List<String> queryWords, int snippetLength) {
        if (text == null || text.isEmpty()) {
            return "";
        }


        int pos = -1;
        for (String word : queryWords) {
            int idx = text.toLowerCase().indexOf(word.toLowerCase());
            if (idx != -1 && (pos == -1 || idx < pos)) {
                pos = idx;
            }
        }

        if (pos == -1) {

            pos = 0;
        }

        int start = Math.max(0, pos - snippetLength / 2);
        int end = Math.min(text.length(), start + snippetLength);

        while (start > 0 && !Character.isWhitespace(text.charAt(start - 1))) {
            start--;
        }

        while (end < text.length() && !Character.isWhitespace(text.charAt(end))) {
            end++;
        }

        String snippet = text.substring(start, end);

        for (String word : queryWords) {
            snippet = snippet.replaceAll("(?i)" + Pattern.quote(word),
                    "<b>" + word + "</b>");
        }

        if (start > 0) snippet = "..." + snippet;
        if (end < text.length()) snippet = snippet + "...";

        return snippet;
    }
}
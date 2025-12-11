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

        // Удаляем все теги, оставляем только текст
        String text = Jsoup.parse(html).text();

        // Удаляем лишние пробелы
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

        // Ищем первое вхождение любого слова запроса
        int pos = -1;
        for (String word : queryWords) {
            int idx = text.toLowerCase().indexOf(word.toLowerCase());
            if (idx != -1 && (pos == -1 || idx < pos)) {
                pos = idx;
            }
        }

        if (pos == -1) {
            // Если не нашли, берем начало текста
            pos = 0;
        }

        // Вычисляем начальную и конечную позиции сниппета
        int start = Math.max(0, pos - snippetLength / 2);
        int end = Math.min(text.length(), start + snippetLength);

        // Корректируем, чтобы не обрезать слова
        while (start > 0 && !Character.isWhitespace(text.charAt(start - 1))) {
            start--;
        }

        while (end < text.length() && !Character.isWhitespace(text.charAt(end))) {
            end++;
        }

        String snippet = text.substring(start, end);

        // Выделяем слова запроса жирным
        for (String word : queryWords) {
            snippet = snippet.replaceAll("(?i)" + Pattern.quote(word),
                    "<b>" + word + "</b>");
        }

        // Добавляем многоточие, если обрезали
        if (start > 0) snippet = "..." + snippet;
        if (end < text.length()) snippet = snippet + "...";

        return snippet;
    }
}
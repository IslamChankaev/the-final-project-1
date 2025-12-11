package searchengine.utils;

import org.apache.lucene.morphology.LuceneMorphology;
import org.apache.lucene.morphology.russian.RussianLuceneMorphology;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.*;
import java.util.regex.Pattern;

@Component
public class LemmaExtractor {
    private final LuceneMorphology luceneMorphology;
    private final Pattern wordPattern = Pattern.compile("[а-яА-ЯёЁ]+");
    private final Set<String> serviceTags = Set.of("ПРЕДЛ", "СОЮЗ", "МЕЖД", "ЧАСТ", "МС");

    public LemmaExtractor() throws IOException {
        this.luceneMorphology = new RussianLuceneMorphology();
    }

    public Map<String, Integer> extractLemmas(String text) {
        Map<String, Integer> lemmas = new HashMap<>();
        String[] words = text.toLowerCase().split("\\s+");

        for (String word : words) {
            if (!wordPattern.matcher(word).matches()) {
                continue;
            }

            List<String> wordInfo = luceneMorphology.getMorphInfo(word);
            if (wordInfo.isEmpty() || isServiceWord(wordInfo.get(0))) {
                continue;
            }

            List<String> normalForms = luceneMorphology.getNormalForms(word);
            if (!normalForms.isEmpty()) {
                String lemma = normalForms.get(0);
                lemmas.put(lemma, lemmas.getOrDefault(lemma, 0) + 1);
            }
        }

        return lemmas;
    }

    public List<String> getLemmasFromText(String text) {
        Map<String, Integer> lemmaMap = extractLemmas(text);
        return new ArrayList<>(lemmaMap.keySet());
    }

    private boolean isServiceWord(String wordInfo) {
        return serviceTags.stream().anyMatch(wordInfo::contains);
    }

    public List<String> extractQueryLemmas(String query) {
        return getLemmasFromText(query);
    }
}
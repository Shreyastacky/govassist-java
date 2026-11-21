package in.edu.thapar.govassist;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Component
public class Catalog {
    public record Translation(String ask, String help) {}
    public record Question(String key, String label, String ask, String help,
                           String kind, boolean whole, BigDecimal min, BigDecimal max,
                           Map<String, Translation> translations) {
        public boolean numeric() { return "number".equals(kind); }
        public boolean applicantType() { return "applicant".equals(kind); }
        public String askIn(String language) {
            Translation translation = translations.get(language);
            return translation == null ? null : translation.ask();
        }
        public String helpIn(String language) {
            Translation translation = translations.get(language);
            return translation == null ? null : translation.help();
        }
    }
    public record Test(String key, String operator, String expected) {}
    public record Evidence(String id, String plain, String quote, int page, String url) {}
    public record Condition(String id, List<Test> tests, Evidence evidence) {}
    public record Scheme(String slug, String title, String name, String category, String authority,
                         String summary, String sourceUrl, int version, List<Condition> conditions,
                         Map<String, List<Evidence>> nextSteps) {}
    public record SourceGroup(Scheme scheme, List<Evidence> evidence) {}
    private record Corpus(List<Question> questions, List<Scheme> schemes) {}

    private final Map<String, Question> questions = new LinkedHashMap<>();
    private final Map<String, Scheme> schemes = new LinkedHashMap<>();

    public Catalog(ObjectMapper mapper) throws IOException {
        try (var stream = new ClassPathResource("corpus/catalog.json").getInputStream()) {
            Corpus corpus = mapper.readValue(stream, Corpus.class);
            for (Question question : corpus.questions()) {
                if (questions.putIfAbsent(question.key(), question) != null) {
                    throw new IllegalStateException("Duplicate question " + question.key());
                }
            }
            for (Scheme scheme : corpus.schemes()) {
                if (schemes.putIfAbsent(scheme.slug(), scheme) != null || scheme.conditions().isEmpty()) {
                    throw new IllegalStateException("Invalid scheme " + scheme.slug());
                }
                for (Condition condition : scheme.conditions()) {
                    if (condition.tests().isEmpty() || condition.evidence().quote().isBlank()) {
                        throw new IllegalStateException("Incomplete condition " + condition.id());
                    }
                    for (Test test : condition.tests()) {
                        if (!questions.containsKey(test.key()) ||
                                !Set.of("==", ">=", "<=", ">", "<").contains(test.operator())) {
                            throw new IllegalStateException("Invalid comparison " + test);
                        }
                    }
                }
            }
        }
    }

    public List<Scheme> schemes() { return List.copyOf(schemes.values()); }
    public Scheme find(String slug) { return schemes.get(slug); }
    public List<SourceGroup> sources(List<Scheme> selected, List<String> terms) {
        return selected.stream().map(scheme -> {
            Map<String, Evidence> excerpts = new LinkedHashMap<>();
            scheme.conditions().forEach(condition -> excerpts.put(condition.evidence().id(), condition.evidence()));
            scheme.nextSteps().values().forEach(items -> items.forEach(item -> excerpts.put(item.id(), item)));
            var matches = excerpts.values().stream().filter(item -> {
                String searchable = (scheme.title() + " " + scheme.name() + " " + scheme.category()
                        + " " + item.plain() + " " + item.quote()).toLowerCase(Locale.ROOT);
                return terms.stream().allMatch(searchable::contains);
            }).toList();
            return new SourceGroup(scheme, matches);
        }).filter(group -> !group.evidence().isEmpty()).toList();
    }

    public List<Question> questionsFor(List<Scheme> selected) {
        Set<String> keys = new LinkedHashSet<>();
        for (Scheme scheme : selected) {
            for (Condition condition : scheme.conditions()) {
                for (Test test : condition.tests()) {
                    keys.add(test.key());
                }
            }
        }
        return questions.values().stream().filter(q -> keys.contains(q.key())).toList();
    }
}

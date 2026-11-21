package in.edu.thapar.govassist;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

@Component
public class Catalog {
    private static final Pattern DECIMAL = Pattern.compile("[0-9]{1,12}(\\.[0-9]{1,6})?");

    public record Translation(String ask, String help) {}
    public record Question(String key, String label, String ask, String help,
                           String kind, boolean whole, BigDecimal min, BigDecimal max,
                           Map<String, Translation> translations) {
        public Question {
            if (!Set.of("number", "applicant", "boolean").contains(kind)) {
                throw new IllegalArgumentException("Unknown question type for " + key);
            }
            translations = Map.copyOf(translations);
        }

        public boolean numeric() { return "number".equals(kind); }
        public boolean applicantType() { return "applicant".equals(kind); }

        String normalize(String value) {
            if (!numeric()) {
                Set<String> allowed = applicantType() ? Set.of("individual", "other") : Set.of("true", "false");
                if (!allowed.contains(value)) {
                    throw new IllegalArgumentException("Choose one of the listed answers.");
                }
                return value;
            }
            if (!DECIMAL.matcher(value).matches()) {
                throw new IllegalArgumentException(whole ?
                        "Enter a non-negative whole number." : "Enter a non-negative number.");
            }
            BigDecimal number = new BigDecimal(value).stripTrailingZeros();
            if (whole && number.scale() > 0) {
                throw new IllegalArgumentException("Enter a whole number.");
            }
            if ((min != null && number.compareTo(min) < 0) || (max != null && number.compareTo(max) > 0)) {
                throw new IllegalArgumentException("Enter a number between " + min + " and " + max + ".");
            }
            return number.toPlainString();
        }

        public String askIn(String language) {
            Translation translation = translations.get(language);
            return translation == null ? null : translation.ask();
        }
        public String helpIn(String language) {
            Translation translation = translations.get(language);
            return translation == null ? null : translation.help();
        }
    }
    public enum Operator {
        EQUAL("==", ""), GREATER(">", "More than "), AT_LEAST(">=", "At least "),
        LESS("<", "Less than "), AT_MOST("<=", "At most ");

        private final String symbol;
        private final String label;

        Operator(String symbol, String label) {
            this.symbol = symbol;
            this.label = label;
        }

        @JsonCreator
        public static Operator parse(String symbol) {
            for (Operator operator : values()) {
                if (operator.symbol.equals(symbol)) {
                    return operator;
                }
            }
            throw new IllegalArgumentException("Unsupported comparison " + symbol);
        }

        @JsonValue
        public String symbol() { return symbol; }

        String requirement(String value) { return label + value; }

        boolean matches(String actual, String expected) {
            if (this == EQUAL && !DECIMAL.matcher(expected).matches()) {
                return actual.equals(expected);
            }
            int comparison = new BigDecimal(actual).compareTo(new BigDecimal(expected));
            return switch (this) {
                case EQUAL -> comparison == 0;
                case GREATER -> comparison > 0;
                case AT_LEAST -> comparison >= 0;
                case LESS -> comparison < 0;
                case AT_MOST -> comparison <= 0;
            };
        }
    }

    public record Test(String key, Operator operator, String expected) {}
    public record Evidence(String id, String plain, String quote, int page, String url) {}
    public record Condition(String id, List<Test> tests, Evidence evidence) {
        public Condition { tests = List.copyOf(tests); }
    }
    public record Scheme(String slug, String title, String name, String category, String authority,
                         String summary, String sourceUrl, int version, List<Condition> conditions,
                         Map<String, List<Evidence>> nextSteps, String scopeCondition) {
        public Scheme {
            conditions = List.copyOf(conditions);
            var groups = new LinkedHashMap<String, List<Evidence>>();
            nextSteps.forEach((key, items) -> groups.put(key, List.copyOf(items)));
            nextSteps = Collections.unmodifiableMap(groups);
        }
    }
    public record SourceGroup(Scheme scheme, List<Evidence> evidence) {}
    private record Corpus(List<Question> questions, List<Scheme> schemes) {}

    private final Map<String, Question> questions = new LinkedHashMap<>();
    private final Map<String, Scheme> schemes = new LinkedHashMap<>();

    public Catalog(ObjectMapper mapper) throws IOException {
        try (var stream = new ClassPathResource("corpus/catalog.json").getInputStream()) {
            load(mapper.readValue(stream, Corpus.class));
        }
    }

    private void load(Corpus corpus) {
        for (Question question : corpus.questions()) {
            if (questions.putIfAbsent(question.key(), question) != null) {
                throw new IllegalStateException("Duplicate question " + question.key());
            }
        }
        for (Scheme scheme : corpus.schemes()) {
            if (schemes.putIfAbsent(scheme.slug(), scheme) != null || scheme.conditions().isEmpty()
                    || scheme.version() <= 0) {
                throw new IllegalStateException("Invalid scheme " + scheme.slug());
            }
            validateSource(scheme.sourceUrl());
            Set<String> conditionIds = new LinkedHashSet<>();
            for (Condition condition : scheme.conditions()) {
                if (!conditionIds.add(condition.id()) || condition.tests().isEmpty()) {
                    throw new IllegalStateException("Invalid condition " + condition.id());
                }
                validateEvidence(condition.evidence());
                condition.tests().forEach(this::validateTest);
            }
            if (scheme.scopeCondition() != null && !conditionIds.contains(scheme.scopeCondition())) {
                throw new IllegalStateException("Unknown scope condition " + scheme.scopeCondition());
            }
            scheme.nextSteps().values().forEach(group -> group.forEach(this::validateEvidence));
        }
    }

    private void validateTest(Test test) {
        Question question = questions.get(test.key());
        if (question == null || test.operator() == null || test.expected() == null
                || (!question.numeric() && test.operator() != Operator.EQUAL)) {
            throw new IllegalStateException("Invalid comparison " + test);
        }
        try {
            question.normalize(test.expected());
        } catch (IllegalArgumentException error) {
            throw new IllegalStateException("Invalid operand for " + test.key(), error);
        }
    }

    private void validateEvidence(Evidence evidence) {
        if (evidence.page() < 1 || evidence.quote().isBlank() || evidence.plain().isBlank()) {
            throw new IllegalStateException("Incomplete evidence " + evidence.id());
        }
        validateSource(evidence.url());
    }

    private void validateSource(String url) {
        URI uri = URI.create(url);
        if (uri.getHost() == null || !Set.of("http", "https").contains(uri.getScheme())) {
            throw new IllegalStateException("Expected an HTTP(S) source URL: " + url);
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

package in.edu.thapar.govassist;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

@Component
public class Catalog {
    private static final Pattern DECIMAL = Pattern.compile("[0-9]{1,12}(\\.[0-9]{1,6})?");

    public enum Topic {
        PERSONAL("Personal and household"), FINANCES("Banking and income"),
        WORK("Work and land"), BUSINESS("Food processing business");

        private final String title;
        Topic(String title) { this.title = title; }
        public String title() { return title; }
        public String anchor() { return "questions-" + name().toLowerCase(Locale.ROOT); }
    }

    public record Choice(String value, String label, String hindi, String punjabi, String tamil) {}
    private static final Choice UNKNOWN = new Choice("", "Not sure", "पता नहीं", "ਪਤਾ ਨਹੀਂ", "தெரியவில்லை");
    private static final List<Choice> YES_NO = List.of(
            new Choice("true", "Yes", "हाँ", "ਹਾਂ", "ஆம்"),
            new Choice("false", "No", "नहीं", "ਨਹੀਂ", "இல்லை"), UNKNOWN);
    private static final List<Choice> APPLICANT_TYPES = List.of(
            new Choice("individual", "Individual", "व्यक्तिगत", "ਵਿਅਕਤੀਗਤ", "தனிநபர்"),
            new Choice("other", "Group or other entity", "समूह या अन्य संस्था", "ਸਮੂਹ ਜਾਂ ਹੋਰ ਸੰਸਥਾ", "குழு அல்லது வேறு அமைப்பு"), UNKNOWN);

    public record Translation(String ask, String help) {}
    public record Question(String key, String label, String ask, String help,
                           String kind, boolean whole, BigDecimal min, BigDecimal max,
                           Map<String, Translation> translations, Topic topic) {
        public Question {
            if (!Set.of("number", "applicant", "boolean").contains(kind) || topic == null) {
                throw new IllegalArgumentException("Unknown question type for " + key);
            }
            translations = Map.copyOf(translations);
        }

        public boolean numeric() { return "number".equals(kind); }
        public boolean applicantType() { return "applicant".equals(kind); }
        public List<Choice> choices() { return applicantType() ? APPLICANT_TYPES : YES_NO; }

        String normalize(String value) {
            if (!numeric()) {
                if (value.isEmpty() || choices().stream().noneMatch(choice -> choice.value().equals(value))) {
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
    public record Category(String name, int count) {}
    public record QuestionSection(Topic topic, List<Question> questions) {}
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

    public List<Category> categories() {
        Map<String, Integer> counts = new TreeMap<>();
        schemes.values().forEach(scheme -> counts.merge(scheme.category(), 1, Integer::sum));
        return counts.entrySet().stream().map(entry -> new Category(entry.getKey(), entry.getValue())).toList();
    }

    public List<Scheme> search(String query, String category) {
        String text = query.toLowerCase(Locale.ROOT);
        return schemes.values().stream()
                .filter(scheme -> category.isEmpty() || scheme.category().equalsIgnoreCase(category))
                .filter(scheme -> (scheme.title() + " " + scheme.name() + " " + scheme.category() + " " + scheme.summary())
                        .toLowerCase(Locale.ROOT).contains(text))
                .toList();
    }
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

    public List<QuestionSection> sections(List<Question> selected, boolean combined) {
        if (!combined) {
            return List.of(new QuestionSection(null, selected));
        }
        Map<Topic, List<Question>> groups = new EnumMap<>(Topic.class);
        selected.forEach(question -> groups.computeIfAbsent(question.topic(), topic -> new ArrayList<>()).add(question));
        return groups.entrySet().stream()
                .map(entry -> new QuestionSection(entry.getKey(), List.copyOf(entry.getValue()))).toList();
    }
}

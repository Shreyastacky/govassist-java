package in.edu.thapar.govassist;

import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class EligibilityEngine {
    public enum Verdict {
        MATCH("Matches recorded criteria", "match"),
        NEEDS_INFO("More information needed", "pending"),
        NO_MATCH("A criterion was not met", "negative"),
        OUTSIDE_SCOPE("Different applicant track", "pending");

        private final String label;
        private final String style;

        Verdict(String label, String style) { this.label = label; this.style = style; }
        public String label() { return label; }
        public String style() { return style; }
    }

    public enum State {
        PASS("Met", "match"), FAIL("Not met", "negative"), UNKNOWN("Unanswered", "pending");

        private final String label;
        private final String style;

        State(String label, String style) { this.label = label; this.style = style; }
        public String label() { return label; }
        public String style() { return style; }
    }

    public record Check(State state, Catalog.Evidence evidence) {}
    public record Result(String slug, String title, int version, Verdict verdict, List<Check> checks,
                         List<String> missing, Map<String, String> answers) {}
    public record Input(Map<String, String> answers, Map<String, String> errors) {}

    public Input validate(List<Catalog.Question> questions, Map<String, String> submitted) {
        Map<String, String> answers = new LinkedHashMap<>();
        Map<String, String> errors = new LinkedHashMap<>();
        for (Catalog.Question question : questions) {
            String value = submitted.getOrDefault(question.key(), "").strip();
            if (value.isEmpty()) {
                continue;
            }
            try {
                answers.put(question.key(), normalize(question, value));
            } catch (IllegalArgumentException error) {
                errors.put(question.key(), error.getMessage());
            }
        }
        return new Input(Collections.unmodifiableMap(answers), Collections.unmodifiableMap(errors));
    }

    private String normalize(Catalog.Question question, String value) {
        if (question.numeric()) {
            if (!value.matches("[0-9]{1,12}(\\.[0-9]{1,6})?")) {
                throw new IllegalArgumentException(question.whole() ?
                        "Enter a non-negative whole number." : "Enter a non-negative number.");
            }
            BigDecimal number = new BigDecimal(value).stripTrailingZeros();
            if (question.whole() && number.scale() > 0) {
                throw new IllegalArgumentException("Enter a whole number.");
            }
            if ((question.min() != null && number.compareTo(question.min()) < 0)
                    || (question.max() != null && number.compareTo(question.max()) > 0)) {
                throw new IllegalArgumentException("Enter a number between " + question.min() + " and " + question.max() + ".");
            }
            return number.toPlainString();
        }
        Set<String> allowed = question.applicantType() ? Set.of("individual", "other") : Set.of("true", "false");
        if (!allowed.contains(value)) {
            throw new IllegalArgumentException("Choose one of the listed answers.");
        }
        return value;
    }

    public Result evaluate(Catalog.Scheme scheme, Map<String, String> answers) {
        Map<String, String> used = new LinkedHashMap<>();
        Set<String> missing = new LinkedHashSet<>();
        List<Check> checks = scheme.conditions().stream().map(condition -> {
            State state = State.PASS;
            for (Catalog.Test comparison : condition.tests()) {
                String actual = answers.get(comparison.key());
                if (actual == null) {
                    missing.add(comparison.key());
                    if (state == State.PASS) {
                        state = State.UNKNOWN;
                    }
                } else {
                    used.put(comparison.key(), actual);
                    if (!satisfies(comparison, actual)) {
                        state = State.FAIL;
                    }
                }
            }
            return new Check(state, condition.evidence());
        }).toList();

        Verdict verdict;
        if (scheme.slug().equals("pmfme") && "other".equals(answers.get("applicant_type"))) {
            verdict = Verdict.OUTSIDE_SCOPE;
            checks = checks.subList(0, 1);
            missing.clear();
            used.keySet().retainAll(Set.of("applicant_type"));
        } else if (checks.stream().anyMatch(check -> check.state() == State.FAIL)) {
            verdict = Verdict.NO_MATCH;
        } else {
            verdict = missing.isEmpty() ? Verdict.MATCH : Verdict.NEEDS_INFO;
        }
        return new Result(scheme.slug(), scheme.title(), scheme.version(), verdict, List.copyOf(checks),
                List.copyOf(missing), Collections.unmodifiableMap(used));
    }

    private boolean satisfies(Catalog.Test comparison, String actual) {
        if (comparison.operator().equals("==")) {
            return actual.equals(comparison.expected());
        }
        int order = new BigDecimal(actual).compareTo(new BigDecimal(comparison.expected()));
        return switch (comparison.operator()) {
            case ">" -> order > 0;
            case ">=" -> order >= 0;
            case "<" -> order < 0;
            case "<=" -> order <= 0;
            default -> throw new IllegalStateException("Unsupported comparison " + comparison.operator());
        };
    }
}

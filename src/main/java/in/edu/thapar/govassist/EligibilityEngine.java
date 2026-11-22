package in.edu.thapar.govassist;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
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

    public record Term(String key, Catalog.Operator operator, String expected, String actual, State state) {
        public String required() { return operator.requirement(display(expected)); }

        public String submitted() { return actual == null ? "Unanswered" : display(actual); }

        private static String display(String value) {
            return switch (value) {
                case "true" -> "Yes";
                case "false" -> "No";
                case "individual" -> "Individual";
                case "other" -> "Group or other entity";
                default -> value;
            };
        }
    }
    public record Check(State state, Catalog.Evidence evidence, List<Term> terms) {
        public Check { terms = terms == null ? List.of() : List.copyOf(terms); }
    }
    public record Result(String slug, String title, int version, Verdict verdict, List<Check> checks,
                         List<String> missing, Map<String, String> answers) {
        public Counts counts() {
            int met = 0;
            int unmet = 0;
            int unanswered = 0;
            for (Check check : checks) {
                switch (check.state()) {
                    case PASS -> met++;
                    case FAIL -> unmet++;
                    case UNKNOWN -> unanswered++;
                }
            }
            return new Counts(met, unmet, unanswered);
        }
    }
    public record Counts(int met, int unmet, int unanswered) {}
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
                answers.put(question.key(), question.normalize(value));
            } catch (IllegalArgumentException error) {
                errors.put(question.key(), error.getMessage());
            }
        }
        return new Input(Collections.unmodifiableMap(answers), Collections.unmodifiableMap(errors));
    }

    public Result evaluate(Catalog.Scheme scheme, Map<String, String> answers) {
        List<Check> checks = new ArrayList<>();
        for (Catalog.Condition condition : scheme.conditions()) {
            Check check = evaluate(condition, answers);
            if (check.state() == State.FAIL && condition.id().equals(scheme.scopeCondition())) {
                return summarize(scheme, Verdict.OUTSIDE_SCOPE, List.of(check));
            }
            checks.add(check);
        }
        State state = combinedState(checks.stream().map(Check::state).toList());
        Verdict verdict = switch (state) {
            case PASS -> Verdict.MATCH;
            case FAIL -> Verdict.NO_MATCH;
            case UNKNOWN -> Verdict.NEEDS_INFO;
        };
        return summarize(scheme, verdict, checks);
    }

    private Check evaluate(Catalog.Condition condition, Map<String, String> answers) {
        var terms = condition.tests().stream().map(test -> {
            String actual = answers.get(test.key());
            State state = State.UNKNOWN;
            if (actual != null) {
                state = test.operator().matches(actual, test.expected()) ? State.PASS : State.FAIL;
            }
            return new Term(test.key(), test.operator(), test.expected(), actual, state);
        }).toList();
        return new Check(combinedState(terms.stream().map(Term::state).toList()), condition.evidence(), terms);
    }

    private State combinedState(List<State> states) {
        if (states.contains(State.FAIL)) {
            return State.FAIL;
        }
        return states.contains(State.UNKNOWN) ? State.UNKNOWN : State.PASS;
    }

    private Result summarize(Catalog.Scheme scheme, Verdict verdict, List<Check> checks) {
        Map<String, String> used = new LinkedHashMap<>();
        Set<String> missing = new LinkedHashSet<>();
        for (Check check : checks) {
            for (Term term : check.terms()) {
                if (term.state() == State.UNKNOWN) {
                    missing.add(term.key());
                } else {
                    used.put(term.key(), term.actual());
                }
            }
        }
        return new Result(scheme.slug(), scheme.title(), scheme.version(), verdict, List.copyOf(checks),
                List.copyOf(missing), Collections.unmodifiableMap(used));
    }
}

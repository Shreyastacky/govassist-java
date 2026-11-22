package in.edu.thapar.govassist;

import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;

@Service
public class RuleLab {
    private static final Map<String, String> SAMPLE_ANSWERS = Map.ofEntries(
            Map.entry("applicant_type", "individual"),
            Map.entry("is_existing_micro_food_processing_unit", "true"),
            Map.entry("identified_in_slup_or_verified", "true"),
            Map.entry("is_unincorporated", "true"),
            Map.entry("worker_count", "9"),
            Map.entry("has_enterprise_ownership_right", "true"),
            Map.entry("passed_class_8", "true"),
            Map.entry("family_member_already_received_assistance", "false"),
            Map.entry("will_formalize", "true"),
            Map.entry("own_contribution_percent", "10"),
            Map.entry("will_take_bank_loan", "true"));

    public record Example(String age, EligibilityEngine.Result result, EligibilityEngine.Check criterion, String error) {}

    private final EligibilityEngine engine;
    private final Catalog.Scheme scheme;
    private final List<Catalog.Question> questions;
    private final List<Example> boundaryExamples;

    public RuleLab(Catalog catalog, EligibilityEngine engine) {
        this.engine = engine;
        this.scheme = catalog.find("pmfme");
        this.questions = catalog.questionsFor(List.of(scheme));
        this.boundaryExamples = List.of(evaluate("18"), evaluate("19"));
    }

    public List<Example> boundaryExamples() {
        return boundaryExamples;
    }

    public Example evaluate(String age) {
        Map<String, String> answers = new LinkedHashMap<>(SAMPLE_ANSWERS);
        answers.put("age", age);
        var input = engine.validate(questions, answers);
        if (!input.errors().isEmpty()) {
            return new Example(age, null, null, input.errors().values().iterator().next());
        }

        var result = engine.evaluate(scheme, input.answers());
        var criterion = result.checks().stream()
                .filter(check -> check.terms().stream().anyMatch(term -> term.key().equals("age")))
                .findFirst().orElseThrow();
        return new Example(age, result, criterion, null);
    }
}

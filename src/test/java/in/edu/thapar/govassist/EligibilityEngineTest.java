package in.edu.thapar.govassist;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static in.edu.thapar.govassist.EligibilityEngine.Verdict.*;
import static org.assertj.core.api.Assertions.assertThat;

class EligibilityEngineTest {
    private final Catalog catalog = new Catalog(new ObjectMapper());
    private final EligibilityEngine engine = new EligibilityEngine();

    EligibilityEngineTest() throws IOException {}

    @Test
    void fullCatalogueHasEightSchemesAndThirtyFourDistinctQuestions() {
        assertThat(catalog.schemes()).hasSize(8);
        assertThat(catalog.questionsFor(catalog.schemes())).hasSize(34);
        for (var scheme : catalog.schemes()) {
            assertThat(engine.evaluate(scheme, Map.of()).verdict()).isEqualTo(NEEDS_INFO);
            assertThat(scheme.conditions()).allSatisfy(condition -> {
                assertThat(condition.evidence().quote()).isNotBlank();
                assertThat(condition.evidence().page()).isPositive();
            });
        }
    }

    @Test
    void pmfmeAgeEighteenFailsAndNineteenPassesWithSameOtherAnswers() {
        var scheme = catalog.find("pmfme");
        Map<String, String> answers = new LinkedHashMap<>(Map.ofEntries(
                Map.entry("applicant_type", "individual"),
                Map.entry("is_existing_micro_food_processing_unit", "true"),
                Map.entry("identified_in_slup_or_verified", "true"),
                Map.entry("is_unincorporated", "true"),
                Map.entry("worker_count", "9"),
                Map.entry("has_enterprise_ownership_right", "true"),
                Map.entry("age", "18"),
                Map.entry("passed_class_8", "true"),
                Map.entry("family_member_already_received_assistance", "false"),
                Map.entry("will_formalize", "true"),
                Map.entry("own_contribution_percent", "10"),
                Map.entry("will_take_bank_loan", "true")));
        var rejected = engine.evaluate(scheme, answers);
        assertThat(rejected.verdict()).isEqualTo(NO_MATCH);
        assertThat(rejected.checks()).anySatisfy(check -> {
            assertThat(check.state()).isEqualTo(EligibilityEngine.State.FAIL);
            assertThat(check.evidence().id()).isEqualTo("individual-age-and-education");
        });
        answers.put("age", "19");
        assertThat(engine.evaluate(scheme, answers).verdict()).isEqualTo(MATCH);
    }

    @Test
    void failsKnownFalseCriterionEvenWhenAnotherTermIsMissing() {
        var result = engine.evaluate(catalog.find("pmfme"), Map.of("age", "18"));
        assertThat(result.verdict()).isEqualTo(NO_MATCH);
        assertThat(result.missing()).contains("passed_class_8");
    }

    @Test
    void keepsPmfmeGroupApplicantOutsideIndividualTrack() {
        var result = engine.evaluate(catalog.find("pmfme"), Map.of("applicant_type", "other", "age", "18"));
        assertThat(result.verdict()).isEqualTo(OUTSIDE_SCOPE);
        assertThat(result.checks()).hasSize(1);
        assertThat(result.missing()).isEmpty();
    }

    @Test
    void svanidhiRequiresLocalCoverage() {
        var scheme = catalog.find("pm-svanidhi");
        var answers = Map.of("is_street_vendor", "true", "vends_in_town_or_peri_urban", "true");
        var result = engine.evaluate(scheme, answers);
        assertThat(result.version()).isEqualTo(2);
        assertThat(result.verdict()).isEqualTo(NEEDS_INFO);
        assertThat(result.missing()).containsExactly("local_scheme_coverage_confirmed");
    }

    @Test
    void preventsAResultWhenNumbersAreMalformed() {
        var scheme = catalog.find("pmfme");
        var input = engine.validate(catalog.questionsFor(List.of(scheme)),
                Map.of("age", "19.5", "worker_count", "-1", "own_contribution_percent", "101"));
        assertThat(input.answers()).isEmpty();
        assertThat(input.errors()).containsOnlyKeys("age", "worker_count", "own_contribution_percent");
    }

    @Test
    void normalizesDecimalsAndIgnoresUnknownFields() {
        var input = engine.validate(catalog.questionsFor(List.of(catalog.find("pm-kmy"))),
                Map.of("land_hectares", "2.000", "age", "", "missing", "true"));
        assertThat(input.errors()).isEmpty();
        assertThat(input.answers()).containsExactlyEntriesOf(Map.of("land_hectares", "2"));
    }

    @Test
    void insuranceSchemesUseDifferentUpperAgeThresholds() {
        Map<String, String> answers = Map.of("age", "51", "has_bank_or_post_office_account", "true",
                "consents_to_auto_debit", "true");
        assertThat(engine.evaluate(catalog.find("pmjjby"), answers).verdict()).isEqualTo(NO_MATCH);
        assertThat(engine.evaluate(catalog.find("pmsby"), answers).verdict()).isEqualTo(MATCH);
    }
}

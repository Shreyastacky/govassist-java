package in.edu.thapar.govassist;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

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
            assertThat(check.terms()).containsExactly(
                    new EligibilityEngine.Term("age", Catalog.Operator.GREATER, "18", "18", EligibilityEngine.State.FAIL),
                    new EligibilityEngine.Term("passed_class_8", Catalog.Operator.EQUAL, "true", "true", EligibilityEngine.State.PASS));
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
        var scheme = catalog.find("pmfme");
        var reordered = new java.util.ArrayList<>(scheme.conditions());
        reordered.add(reordered.remove(0));
        var changed = new Catalog.Scheme(scheme.slug(), scheme.title(), scheme.name(), scheme.category(),
                scheme.authority(), scheme.summary(), scheme.sourceUrl(), scheme.version(),
                reordered, scheme.nextSteps(), scheme.scopeCondition());
        var result = engine.evaluate(changed, Map.of("applicant_type", "other", "age", "18"));
        assertThat(result.verdict()).isEqualTo(OUTSIDE_SCOPE);
        assertThat(result.checks()).singleElement().satisfies(check ->
                assertThat(check.evidence().id()).isEqualTo("individual-capital-subsidy"));
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

    @ParameterizedTest(name = "{0}: passing case, then a single failed input")
    @MethodSource("courseworkScenarios")
    void evaluateEachRemainingSchemeFromExplicitFixtures(String slug, Map<String, String> answers,
                                                         String changedField, String changedValue) {
        var scheme = catalog.find(slug);
        assertThat(engine.evaluate(scheme, answers).verdict()).isEqualTo(MATCH);
        Map<String, String> changed = new LinkedHashMap<>(answers);
        changed.put(changedField, changedValue);
        var result = engine.evaluate(scheme, changed);
        assertThat(result.verdict()).isEqualTo(NO_MATCH);
        assertThat(result.checks()).flatExtracting(EligibilityEngine.Check::terms)
                .anySatisfy(term -> {
                    assertThat(term.key()).isEqualTo(changedField);
                    assertThat(term.state()).isEqualTo(EligibilityEngine.State.FAIL);
                });
    }

    static Stream<Arguments> courseworkScenarios() {
        return Stream.of(
                Arguments.of("pm-svanidhi", Map.of("is_street_vendor", "true", "vends_in_town_or_peri_urban", "true",
                        "local_scheme_coverage_confirmed", "true"), "local_scheme_coverage_confirmed", "false"),
                Arguments.of("pmuy", Map.of("is_woman", "true", "age", "18", "is_indian_citizen", "true",
                        "resides_in_india", "true", "household_has_lpg_or_png", "false",
                        "household_previously_had_gas", "false", "will_give_deprivation_declaration", "true"),
                        "household_previously_had_gas", "true"),
                Arguments.of("apy", Map.of("age", "40", "is_indian_citizen", "true",
                        "has_savings_bank_account", "true", "ever_paid_income_tax", "false"), "age", "41"),
                Arguments.of("nsap", Map.of("age", "60", "household_is_bpl", "true"), "age", "59"),
                Arguments.of("pm-kmy", Map.of("age", "40", "owns_cultivable_land", "true", "land_hectares", "2",
                        "in_other_pension_or_social_security_scheme", "false", "is_institutional_landholder", "false",
                        "holds_or_held_public_office", "false", "is_government_employee", "false",
                        "paid_income_tax_last_year", "false", "is_practising_professional", "false"),
                        "land_hectares", "2.000001"),
                Arguments.of("pmjjby", Map.of("age", "50", "has_bank_or_post_office_account", "true",
                        "consents_to_auto_debit", "true"), "age", "51"),
                Arguments.of("pmsby", Map.of("age", "70", "has_bank_or_post_office_account", "true",
                        "consents_to_auto_debit", "true"), "age", "71"));
    }
}

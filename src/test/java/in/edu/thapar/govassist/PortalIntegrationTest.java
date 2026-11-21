package in.edu.thapar.govassist;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:govassist_test;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
class PortalIntegrationTest {
    @Autowired MockMvc http;
    @Autowired JdbcTemplate db;
    @Autowired AccountRepository accounts;
    @Autowired AssessmentRepository assessments;
    @Autowired Catalog catalog;
    @Autowired EligibilityEngine engine;

    @BeforeEach
    void clearUsers() { db.update("DELETE FROM app_user"); }

    @Test
    void catalogueSearchAndUnknownScheme() throws Exception {
        http.perform(get("/").param("q", "ujjwala"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Ujjwala Yojana")))
                .andExpect(result -> assertThat(result.getResponse().getContentAsString().split("<article", -1)).hasSize(2));
        http.perform(get("/check/pmuy")).andExpect(status().isOk());
        http.perform(get("/check/unknown")).andExpect(status().isNotFound());
    }

    @Test
    void guestComparisonRendersEightResultsAndWritesNothing() throws Exception {
        http.perform(post("/check/all").with(csrf()))
                .andExpect(status().isOk())
                .andExpect(result -> assertThat(result.getResponse().getContentAsString().split("class=\"result-panel\"", -1)).hasSize(9))
                .andExpect(content().string(containsString("More information needed")));
        assertThat(db.queryForObject("SELECT COUNT(*) FROM assessment", Integer.class)).isZero();
    }

    @Test
    void sourceLibrarySearchFiltersBySchemeAndTerms() throws Exception {
        http.perform(get("/sources").param("scheme", "pmfme").param("q", "standard age"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Source PDF · page 7")));
        http.perform(get("/sources").param("q", "unmatched-synthetic-term"))
                .andExpect(content().string(containsString("No passages match")));
    }

    @Test
    void registrationHashesPasswordAndEnforcesUniqueEmail() throws Exception {
        http.perform(post("/register").with(csrf()).param("email", "Sample@example.test").param("password", "Test-Password-12"))
                .andExpect(redirectedUrl("/login?created"));
        var account = accounts.find("sample@example.test").orElseThrow();
        assertThat(account.hash()).startsWith("$2a$").doesNotContain("Test-Password-12");
        assertThat(assessments.profiles(account.id())).hasSize(1);
        http.perform(post("/login").with(csrf()).param("username", "Sample@example.test").param("password", "Test-Password-12"))
                .andExpect(redirectedUrl("/dashboard"));
        http.perform(post("/register").with(csrf()).param("email", "sample@example.test").param("password", "AnotherPassword"))
                .andExpect(status().isConflict());
    }

    @Test
    void validationAndCsrfKeepInvalidChecksOutOfHistory() throws Exception {
        accounts.create("owner@example.test", "unused");
        http.perform(post("/check/pmfme").with(user("owner@example.test"))
                        .with(csrf()).param("submission", UUID.randomUUID().toString())
                        .param("revision", "0").param("worker_count", "-1"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(content().string(containsString("Enter a non-negative whole number.")));
        http.perform(post("/check/pmfme")).andExpect(status().isForbidden());
        assertThat(db.queryForObject("SELECT COUNT(*) FROM assessment", Integer.class)).isZero();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM profile_answer", Integer.class)).isZero();
    }

    @Test
    void profilePrefillAndHistorySnapshotsStayIsolated() throws Exception {
        accounts.create("owner@example.test", "unused");
        accounts.create("other@example.test", "unused");
        long owner = accounts.find("owner@example.test").orElseThrow().id();
        long profile = assessments.profiles(owner).get(0).id();
        var ownerRequest = user("owner@example.test");
        UUID submission = UUID.randomUUID();
        String location = "/dashboard/runs/" + submission;
        http.perform(get("/check/nsap").with(ownerRequest).param("profile", "" + profile))
                .andExpect(content().string(containsString("name=\"revision\" value=\"0\"")))
                .andExpect(content().string(containsString("name=\"submission\"")));
        http.perform(post("/check/nsap").with(ownerRequest).with(csrf()).param("profile", "" + profile)
                        .param("submission", submission.toString()).param("revision", "0")
                        .param("age", "60").param("household_is_bpl", "true"))
                .andExpect(status().isSeeOther()).andExpect(redirectedUrl(location));
        http.perform(get(location).with(ownerRequest))
                .andExpect(content().string(containsString("Matches recorded criteria")))
                .andExpect(content().string(containsString("Required: At least 60")));
        var first = assessments.history(owner, Long.MAX_VALUE).get(0);
        http.perform(get("/check/nsap").with(ownerRequest).param("profile", "" + profile))
                .andExpect(content().string(containsString("id=\"age\" name=\"age\" value=\"60\"")));
        UUID second = UUID.randomUUID();
        http.perform(post("/check/nsap").with(ownerRequest).with(csrf()).param("profile", "" + profile)
                        .param("submission", second.toString()).param("revision", "1")
                        .param("age", "59").param("household_is_bpl", "true"))
                .andExpect(status().isSeeOther());
        http.perform(get("/dashboard/runs/" + second).with(ownerRequest))
                .andExpect(content().string(containsString("A criterion was not met")));
        http.perform(get("/dashboard").with(ownerRequest)).andExpect(status().isOk());
        http.perform(get("/dashboard/results/" + first.id()).with(ownerRequest))
                .andExpect(content().string(containsString("Matches recorded criteria")));
        http.perform(get("/dashboard/results/" + first.id()).with(user("other@example.test")))
                .andExpect(status().isNotFound());
        http.perform(get(location).with(user("other@example.test"))).andExpect(status().isNotFound());
        http.perform(get("/check/nsap").with(user("other@example.test")).param("profile", "" + profile))
                .andExpect(status().isNotFound());
        http.perform(get("/dashboard")).andExpect(status().is3xxRedirection());
    }

    @Test
    void householdProfilesCanBeAddedWithoutSharingAnswers() throws Exception {
        accounts.create("owner@example.test", "unused");
        long id = accounts.find("owner@example.test").orElseThrow().id();
        http.perform(post("/dashboard/profiles").with(user("owner@example.test")).with(csrf()).param("name", "Parent"))
                .andExpect(redirectedUrl("/dashboard"));
        assertThat(assessments.profiles(id)).extracting(AssessmentRepository.Profile::label)
                .containsExactly("My profile", "Parent");
        long parent = assessments.profiles(id).get(1).id();
        http.perform(post("/check/nsap").with(user("owner@example.test")).with(csrf())
                        .param("submission", UUID.randomUUID().toString()).param("revision", "0")
                        .param("profile", "" + parent).param("age", "65"))
                .andExpect(status().isSeeOther());
        assertThat(assessments.draft(id, parent).answers()).containsEntry("age", "65");
        assertThat(assessments.draft(id, assessments.profiles(id).get(0).id()).answers()).isEmpty();
    }

    @Test
    void repeatSubmissionReusesTheWholeRunAndStaleFormsCannotChangeAnswers() throws Exception {
        String email = "owner@example.test";
        accounts.create(email, "unused");
        long userId = accounts.find(email).orElseThrow().id();
        long profile = assessments.profiles(userId).get(0).id();
        UUID submission = UUID.randomUUID();
        String location = "/dashboard/runs/" + submission;

        for (String age : new String[]{"60", "060"}) {
            http.perform(post("/check/all").with(user(email)).with(csrf())
                            .param("submission", submission.toString()).param("revision", "0").param("age", age))
                    .andExpect(status().isSeeOther()).andExpect(redirectedUrl(location));
        }
        http.perform(get(location).with(user(email)))
                .andExpect(status().isOk())
                .andExpect(result -> assertThat(result.getResponse().getContentAsString()
                        .split("class=\"result-panel\"", -1)).hasSize(9));

        for (UUID staleKey : new UUID[]{submission, UUID.randomUUID()}) {
            http.perform(post("/check/all").with(user(email)).with(csrf())
                            .param("submission", staleKey.toString()).param("revision", "0").param("age", "59"))
                    .andExpect(status().isConflict())
                    .andExpect(content().string(containsString("Open the latest saved answers")))
                    .andExpect(content().string(containsString("id=\"age\" name=\"age\" value=\"59\"")));
        }
        assertThat(assessments.history(userId, Long.MAX_VALUE)).hasSize(8);
        assertThat(assessments.draft(userId, profile).profile().revision()).isEqualTo(1);
        assertThat(assessments.draft(userId, profile).answers()).containsEntry("age", "60");
    }

    @Test
    void racingWritesFromTheSameRevisionHaveOneWinner() throws Exception {
        accounts.create("race@example.test", "unused");
        long user = accounts.find("race@example.test").orElseThrow().id();
        long profile = assessments.profiles(user).get(0).id();
        var scheme = catalog.find("nsap");
        var fields = catalog.questionsFor(List.of(scheme));
        var ready = new CountDownLatch(2);
        var go = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var requests = List.of("60", "59").stream().map(age -> pool.submit(() -> {
                var answers = Map.of("age", age, "household_is_bpl", "true");
                var submission = new AssessmentRepository.Submission(UUID.randomUUID(), "nsap", 0);
                ready.countDown();
                if (!go.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Test writers did not start");
                }
                try {
                    assessments.save(user, profile, submission, fields, answers, List.of(engine.evaluate(scheme, answers)));
                    return age;
                } catch (AssessmentRepository.SubmissionConflict conflict) {
                    return "conflict";
                }
            })).toList();
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            var first = requests.get(0).get(5, TimeUnit.SECONDS);
            var second = requests.get(1).get(5, TimeUnit.SECONDS);
            assertThat(List.of(first, second)).contains("conflict").doesNotContainSequence("conflict", "conflict");
            assertThat(assessments.draft(user, profile).answers()).containsEntry("age", first.equals("conflict") ? second : first);
            assertThat(assessments.history(user, Long.MAX_VALUE)).hasSize(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void failedBatchRollsBackAnswersAndEarlierResultInserts() {
        accounts.create("rollback@example.test", "unused");
        long user = accounts.find("rollback@example.test").orElseThrow().id();
        long profile = assessments.profiles(user).get(0).id();
        var scheme = catalog.find("nsap");
        var answers = Map.of("age", "60");
        var valid = engine.evaluate(scheme, answers);
        var invalid = new EligibilityEngine.Result("nsap", "x".repeat(151), 1, valid.verdict(),
                valid.checks(), valid.missing(), valid.answers());
        var request = new AssessmentRepository.Submission(UUID.randomUUID(), "all", 0);

        assertThatThrownBy(() -> assessments.save(user, profile, request, catalog.questionsFor(List.of(scheme)),
                answers, List.of(valid, invalid))).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(assessments.draft(user, profile).answers()).isEmpty();
        assertThat(assessments.draft(user, profile).profile().revision()).isZero();
        assertThat(assessments.history(user, Long.MAX_VALUE)).isEmpty();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM assessment_run", Integer.class)).isZero();
    }
}

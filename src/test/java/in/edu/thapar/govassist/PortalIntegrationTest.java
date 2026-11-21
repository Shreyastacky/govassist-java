package in.edu.thapar.govassist;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
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
                        .with(csrf()).param("worker_count", "-1"))
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
        http.perform(post("/check/nsap").with(ownerRequest).with(csrf()).param("profile", "" + profile)
                        .param("age", "60").param("household_is_bpl", "true"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Matches recorded criteria")));
        var first = assessments.history(owner, Long.MAX_VALUE).get(0);
        http.perform(get("/check/nsap").with(ownerRequest).param("profile", "" + profile))
                .andExpect(content().string(containsString("id=\"age\" name=\"age\" value=\"60\"")));
        http.perform(post("/check/nsap").with(ownerRequest).with(csrf()).param("profile", "" + profile)
                        .param("age", "59").param("household_is_bpl", "true"))
                .andExpect(content().string(containsString("A criterion was not met")));
        http.perform(get("/dashboard").with(ownerRequest)).andExpect(status().isOk());
        http.perform(get("/dashboard/results/" + first.id()).with(ownerRequest))
                .andExpect(content().string(containsString("Matches recorded criteria")));
        http.perform(get("/dashboard/results/" + first.id()).with(user("other@example.test")))
                .andExpect(status().isNotFound());
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
                        .param("profile", "" + parent).param("age", "65"))
                .andExpect(status().isOk());
        assertThat(assessments.answers(id, parent)).containsEntry("age", "65");
        assertThat(assessments.answers(id, assessments.profiles(id).get(0).id())).isEmpty();
    }
}

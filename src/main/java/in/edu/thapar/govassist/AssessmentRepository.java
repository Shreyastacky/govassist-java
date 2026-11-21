package in.edu.thapar.govassist;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Repository
public class AssessmentRepository {
    public record Profile(long id, String label) {}
    public record Summary(long id, String title, EligibilityEngine.Verdict verdict, String profile, OffsetDateTime date) {
        public String formattedDate() {
            return date.atZoneSameInstant(java.time.ZoneId.of("Asia/Kolkata"))
                    .format(DateTimeFormatter.ofPattern("dd MMM yyyy, HH:mm 'IST'", Locale.ENGLISH));
        }
    }

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public AssessmentRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public List<Profile> profiles(long userId) {
        return jdbc.query("SELECT id, label FROM applicant_profile WHERE user_id = ? ORDER BY id",
                (row, index) -> new Profile(row.getLong("id"), row.getString("label")), userId);
    }

    public void addProfile(long userId, String label) {
        jdbc.update("INSERT INTO applicant_profile (user_id, label) VALUES (?, ?)", userId, label);
    }

    public Map<String, String> answers(long userId, long profileId) {
        return jdbc.query("""
                SELECT a.attribute_key, a.answer_value
                FROM profile_answer a JOIN applicant_profile p ON p.id = a.profile_id
                WHERE p.id = ? AND p.user_id = ?
                """, rows -> {
            Map<String, String> answers = new LinkedHashMap<>();
            while (rows.next()) {
                answers.put(rows.getString(1), rows.getString(2));
            }
            return answers;
        }, profileId, userId);
    }

    @Transactional
    public void save(long userId, long profileId, List<Catalog.Question> fields, Map<String, String> answers,
                     List<EligibilityEngine.Result> results) {
        boolean owned = !jdbc.queryForList("SELECT id FROM applicant_profile WHERE id = ? AND user_id = ? FOR UPDATE",
                Long.class, profileId, userId).isEmpty();
        if (!owned) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }

        for (Catalog.Question question : fields) {
            String value = answers.get(question.key());
            if (value == null) {
                jdbc.update("DELETE FROM profile_answer WHERE profile_id = ? AND attribute_key = ?",
                        profileId, question.key());
            } else {
                jdbc.update("MERGE INTO profile_answer (profile_id, attribute_key, answer_value) KEY (profile_id, attribute_key) VALUES (?, ?, ?)",
                        profileId, question.key(), value);
            }
        }
        for (EligibilityEngine.Result result : results) {
            jdbc.update("""
                    INSERT INTO assessment (user_id, profile_id, scheme_slug, scheme_title, rule_version, verdict, result_json)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    """, userId, profileId, result.slug(), result.title(), result.version(),
                    result.verdict().name(), encode(result));
        }
    }

    public List<Summary> history(long userId, long beforeId) {
        return jdbc.query("""
                SELECT a.id, a.scheme_title, a.verdict, p.label, a.assessed_at
                FROM assessment a JOIN applicant_profile p ON p.id = a.profile_id
                WHERE a.user_id = ? AND a.id < ?
                ORDER BY a.id DESC FETCH FIRST 26 ROWS ONLY
                """, (row, index) -> new Summary(row.getLong("id"), row.getString("scheme_title"),
                EligibilityEngine.Verdict.valueOf(row.getString("verdict")), row.getString("label"),
                row.getObject("assessed_at", OffsetDateTime.class)), userId, beforeId);
    }

    public EligibilityEngine.Result result(long userId, long id) {
        return jdbc.query("SELECT result_json FROM assessment WHERE id = ? AND user_id = ?",
                (row, index) -> decode(row.getString("result_json")), id, userId).stream().findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    private String encode(EligibilityEngine.Result result) {
        try {
            return mapper.writeValueAsString(result);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("Could not encode assessment", error);
        }
    }

    private EligibilityEngine.Result decode(String json) {
        try {
            return mapper.readValue(json, EligibilityEngine.Result.class);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("Could not read assessment", error);
        }
    }
}

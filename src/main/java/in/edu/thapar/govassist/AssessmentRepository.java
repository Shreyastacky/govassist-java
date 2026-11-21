package in.edu.thapar.govassist;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

@Repository
public class AssessmentRepository {
    public record Profile(long id, String label, long revision) {}
    public record Draft(Profile profile, Map<String, String> answers) {}
    public record Submission(UUID id, String selection, long revision) {}
    public record Run(Profile profile, String selection, List<EligibilityEngine.Result> results) {}
    private record RunKey(long profileId, String selection, long revision, String fingerprint) {}
    public static class SubmissionConflict extends RuntimeException {}
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
        return jdbc.query("SELECT id, label, revision FROM applicant_profile WHERE user_id = ? ORDER BY id",
                (row, index) -> new Profile(row.getLong("id"), row.getString("label"), row.getLong("revision")), userId);
    }

    public void addProfile(long userId, String label) {
        jdbc.update("INSERT INTO applicant_profile (user_id, label) VALUES (?, ?)", userId, label);
    }

    public Draft draft(long userId, long profileId) {
        return jdbc.query("""
                SELECT p.label, p.revision, a.attribute_key, a.answer_value
                FROM applicant_profile p LEFT JOIN profile_answer a ON p.id = a.profile_id
                WHERE p.id = ? AND p.user_id = ?
                """, rows -> {
            if (!rows.next()) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND);
            }
            Profile profile = new Profile(profileId, rows.getString("label"), rows.getLong("revision"));
            Map<String, String> answers = new LinkedHashMap<>();
            do {
                String key = rows.getString("attribute_key");
                if (key != null) {
                    answers.put(key, rows.getString("answer_value"));
                }
            } while (rows.next());
            return new Draft(profile, answers);
        }, profileId, userId);
    }

    @Transactional
    public void save(long userId, long profileId, Submission submission, List<Catalog.Question> fields,
                     Map<String, String> answers, List<EligibilityEngine.Result> results) {
        long revision = jdbc.queryForList(
                "SELECT revision FROM applicant_profile WHERE id = ? AND user_id = ? FOR UPDATE",
                Long.class, profileId, userId).stream().findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        var expected = new RunKey(profileId, submission.selection(), submission.revision(), fingerprint(answers));
        var saved = jdbc.query("""
                SELECT profile_id, selection, submitted_revision, answer_fingerprint
                FROM assessment_run WHERE user_id = ? AND id = ?
                """, (row, index) -> new RunKey(row.getLong(1), row.getString(2), row.getLong(3), row.getString(4)),
                userId, submission.id()).stream().findFirst();
        // Check a replay before comparing revisions. Its successful write already advanced the revision.
        if (saved.filter(expected::equals).isPresent()) {
            return;
        }
        if (saved.isPresent() || revision != submission.revision()) {
            throw new SubmissionConflict();
        }

        jdbc.update("""
                INSERT INTO assessment_run (user_id, id, profile_id, selection, submitted_revision, answer_fingerprint)
                VALUES (?, ?, ?, ?, ?, ?)
                """, userId, submission.id(), profileId, expected.selection(), expected.revision(), expected.fingerprint());
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
                    INSERT INTO assessment (user_id, profile_id, scheme_slug, scheme_title, rule_version, verdict, result_json, run_id)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """, userId, profileId, result.slug(), result.title(), result.version(),
                    result.verdict().name(), encode(result), submission.id());
        }
        jdbc.update("UPDATE applicant_profile SET revision = revision + 1 WHERE id = ? AND user_id = ?",
                profileId, userId);
    }

    public Run run(long userId, UUID id) {
        return jdbc.query("""
                SELECT p.id, p.label, p.revision, r.selection, a.result_json
                FROM assessment_run r JOIN applicant_profile p ON p.id = r.profile_id
                JOIN assessment a ON a.user_id = r.user_id AND a.run_id = r.id
                WHERE r.user_id = ? AND r.id = ? ORDER BY a.id
                """, rows -> {
            if (!rows.next()) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND);
            }
            var profile = new Profile(rows.getLong("id"), rows.getString("label"), rows.getLong("revision"));
            String selection = rows.getString("selection");
            var results = new ArrayList<EligibilityEngine.Result>();
            do {
                results.add(decode(rows.getString("result_json")));
            } while (rows.next());
            return new Run(profile, selection, List.copyOf(results));
        }, userId, id);
    }

    private String fingerprint(Map<String, String> answers) {
        try {
            byte[] canonical = mapper.writeValueAsBytes(new TreeMap<>(answers));
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical));
        } catch (JsonProcessingException | NoSuchAlgorithmException error) {
            throw new IllegalStateException("Could not identify submission", error);
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

package in.edu.thapar.govassist;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Repository
public class AccountRepository {
    public record Account(long id, String email, String hash) {}
    private final JdbcTemplate jdbc;

    public AccountRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public Optional<Account> find(String email) {
        return jdbc.query("SELECT id, email, password_hash FROM app_user WHERE email = ?",
                (row, index) -> new Account(row.getLong("id"), row.getString("email"),
                        row.getString("password_hash")), email).stream().findFirst();
    }

    @Transactional
    public void create(String email, String hash) {
        var key = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var statement = connection.prepareStatement(
                    "INSERT INTO app_user (email, password_hash) VALUES (?, ?)", new String[]{"ID"});
            statement.setString(1, email);
            statement.setString(2, hash);
            return statement;
        }, key);
        jdbc.update("INSERT INTO applicant_profile (user_id, label) VALUES (?, 'My profile')", key.getKey());
    }
}

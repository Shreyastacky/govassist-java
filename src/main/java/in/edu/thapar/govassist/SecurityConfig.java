package in.edu.thapar.govassist;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

import java.util.Locale;

@Configuration
public class SecurityConfig {
    @Bean
    PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(12); }

    @Bean
    UserDetailsService userDetailsService(AccountRepository accounts) {
        return email -> accounts.find(email.strip().toLowerCase(Locale.ROOT))
                .map(account -> User.withUsername(account.email()).password(account.hash()).roles("USER").build())
                .orElseThrow(() -> new UsernameNotFoundException("Account not found"));
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return http
                .authorizeHttpRequests(requests -> requests
                        .requestMatchers("/dashboard", "/dashboard/**").hasRole("USER")
                        .anyRequest().permitAll())
                .formLogin(login -> login.loginPage("/login").defaultSuccessUrl("/dashboard", true).permitAll())
                .logout(logout -> logout.logoutSuccessUrl("/"))
                .build();
    }
}

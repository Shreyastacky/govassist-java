package in.edu.thapar.govassist;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.servlet.view.RedirectView;

import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Controller
public class PortalController {
    private final Catalog catalog;
    private final EligibilityEngine engine;
    private final AccountRepository accounts;
    private final AssessmentRepository assessments;
    private final PasswordEncoder passwords;

    public PortalController(Catalog catalog, EligibilityEngine engine, AccountRepository accounts,
                            AssessmentRepository assessments, PasswordEncoder passwords) {
        this.catalog = catalog;
        this.engine = engine;
        this.accounts = accounts;
        this.assessments = assessments;
        this.passwords = passwords;
    }

    @GetMapping("/")
    String index(@RequestParam(defaultValue = "") String q, Model model) {
        String query = q.strip().toLowerCase(Locale.ROOT);
        model.addAttribute("query", q.strip());
        model.addAttribute("schemes", catalog.schemes().stream()
                .filter(scheme -> (scheme.title() + " " + scheme.name() + " " + scheme.category())
                        .toLowerCase(Locale.ROOT).contains(query)).toList());
        return "index";
    }

    @GetMapping("/sources")
    String sources(@RequestParam(defaultValue = "") String q, @RequestParam(defaultValue = "all") String scheme,
                   Model model) {
        String query = q.strip();
        if (query.length() > 100) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        }
        List<String> terms = query.isEmpty() ? List.of() : Arrays.asList(query.toLowerCase(Locale.ROOT).split("\\s+"));
        model.addAttribute("query", query);
        model.addAttribute("selectedScheme", scheme);
        model.addAttribute("schemes", catalog.schemes());
        model.addAttribute("groups", catalog.sources(selectedSchemes(scheme), terms));
        return "sources";
    }

    @GetMapping("/check/{slug}")
    String check(@PathVariable String slug, @RequestParam(required = false) Long profile,
                 Principal principal, Model model) {
        List<Catalog.Scheme> schemes = selectedSchemes(slug);
        Long user = principal == null ? null : userId(principal);
        var applicant = applicant(user, profile, model);
        if (applicant == null) {
            return form(slug, schemes, Map.of(), Map.of(), model);
        }
        var draft = assessments.draft(user, applicant.id());
        model.addAttribute("submission", UUID.randomUUID());
        model.addAttribute("formRevision", draft.profile().revision());
        return form(slug, schemes, draft.answers(), Map.of(), model);
    }

    @PostMapping("/check/{slug}")
    ModelAndView assess(@PathVariable String slug, @RequestParam(required = false) Long profile,
                        @RequestParam(required = false) UUID submission, @RequestParam(required = false) Long revision,
                        @RequestParam MultiValueMap<String, String> form, Principal principal,
                        HttpServletResponse response, Model model) {
        List<Catalog.Scheme> schemes = selectedSchemes(slug);
        Long user = principal == null ? null : userId(principal);
        var applicant = applicant(user, profile, model);
        List<Catalog.Question> questions = catalog.questionsFor(schemes);
        if (applicant != null && (submission == null || revision == null || revision < 0)) {
            return conflict(slug, schemes, form.toSingleValueMap(), response, model);
        }
        model.addAttribute("submission", submission);
        model.addAttribute("formRevision", revision);
        var input = engine.validate(questions, form.toSingleValueMap());
        if (!input.errors().isEmpty()) {
            response.setStatus(HttpStatus.UNPROCESSABLE_ENTITY.value());
            return new ModelAndView(form(slug, schemes, form.toSingleValueMap(), input.errors(), model));
        }

        List<EligibilityEngine.Result> results = schemes.stream()
                .map(scheme -> engine.evaluate(scheme, input.answers())).toList();
        if (applicant != null) {
            var request = new AssessmentRepository.Submission(submission, slug, revision);
            try {
                assessments.save(user, applicant.id(), request, questions, input.answers(), results);
            } catch (AssessmentRepository.SubmissionConflict | DuplicateKeyException error) {
                return conflict(slug, schemes, form.toSingleValueMap(), response, model);
            }
            var redirect = new RedirectView("/dashboard/runs/" + submission, true);
            redirect.setStatusCode(HttpStatus.SEE_OTHER);
            redirect.setExposeModelAttributes(false);
            return new ModelAndView(redirect);
        }
        model.addAttribute("results", results);
        model.addAttribute("checkSlug", slug);
        return new ModelAndView("results");
    }

    private ModelAndView conflict(String slug, List<Catalog.Scheme> schemes, Map<String, String> answers,
                                  HttpServletResponse response, Model model) {
        response.setStatus(HttpStatus.CONFLICT.value());
        model.addAttribute("conflict", true);
        return new ModelAndView(form(slug, schemes, answers, Map.of(), model));
    }

    @GetMapping("/login")
    String login() { return "account"; }

    @GetMapping("/register")
    String register(Model model) {
        model.addAttribute("register", true);
        return "account";
    }

    @PostMapping("/register")
    String createAccount(@RequestParam(defaultValue = "") String email, @RequestParam(defaultValue = "") String password,
                         HttpServletResponse response, Model model) {
        String username = email.strip().toLowerCase(Locale.ROOT);
        model.addAttribute("register", true);
        model.addAttribute("email", username);
        if (username.length() > 254 || !username.matches("[a-z0-9.!#$%&'*+/=?^_`{|}~-]+@[a-z0-9-]+(?:\\.[a-z0-9-]+)+")) {
            response.setStatus(HttpStatus.UNPROCESSABLE_ENTITY.value());
            model.addAttribute("error", "Enter a valid email address.");
            return "account";
        }
        if (password.length() < 8 || password.getBytes(StandardCharsets.UTF_8).length > 72) {
            response.setStatus(HttpStatus.UNPROCESSABLE_ENTITY.value());
            model.addAttribute("error", "Use at least eight characters and at most 72 bytes.");
            return "account";
        }
        try {
            accounts.create(username, passwords.encode(password));
        } catch (DuplicateKeyException error) {
            response.setStatus(HttpStatus.CONFLICT.value());
            model.addAttribute("error", "An account already exists for this email address.");
            return "account";
        }
        return "redirect:/login?created";
    }

    @GetMapping("/dashboard")
    String dashboard(@RequestParam(defaultValue = "9223372036854775807") long before, Principal principal, Model model) {
        long userId = userId(principal);
        model.addAttribute("profiles", assessments.profiles(userId));
        var history = assessments.history(userId, before);
        model.addAttribute("history", history.stream().limit(25).toList());
        model.addAttribute("nextPage", history.size() > 25 ? history.get(24).id() : null);
        return "dashboard";
    }

    @PostMapping("/dashboard/profiles")
    String addProfile(@RequestParam(defaultValue = "") String name, Principal principal, RedirectAttributes redirect) {
        String label = name.strip();
        if (label.isEmpty() || label.length() > 50 || label.chars().anyMatch(Character::isISOControl)) {
            redirect.addFlashAttribute("profileError", "Use a profile name from 1 to 50 characters.");
            return "redirect:/dashboard";
        }
        try {
            assessments.addProfile(userId(principal), label);
            redirect.addFlashAttribute("notice", "Profile added.");
        } catch (DuplicateKeyException error) {
            redirect.addFlashAttribute("profileError", "A profile with that name already exists.");
        }
        return "redirect:/dashboard";
    }

    @GetMapping("/dashboard/results/{id}")
    String saved(@PathVariable long id, Principal principal, Model model) {
        model.addAttribute("results", List.of(assessments.result(userId(principal), id)));
        model.addAttribute("snapshot", true);
        return "results";
    }

    @GetMapping("/dashboard/runs/{id}")
    String run(@PathVariable UUID id, Principal principal, Model model) {
        var run = assessments.run(userId(principal), id);
        model.addAttribute("results", run.results());
        model.addAttribute("profile", run.profile());
        model.addAttribute("checkSlug", run.selection());
        model.addAttribute("saved", true);
        return "results";
    }

    @ModelAttribute
    void defaults(Principal principal, Model model) {
        model.addAttribute("signedIn", principal != null);
        model.addAttribute("saved", false);
        model.addAttribute("snapshot", false);
        model.addAttribute("register", false);
        model.addAttribute("conflict", false);
    }

    @ModelAttribute("questionsByKey")
    Map<String, Catalog.Question> questionsByKey() {
        Map<String, Catalog.Question> byKey = new LinkedHashMap<>();
        catalog.questionsFor(catalog.schemes()).forEach(question -> byKey.put(question.key(), question));
        return byKey;
    }

    @ModelAttribute("schemesBySlug")
    Map<String, Catalog.Scheme> schemesBySlug() {
        Map<String, Catalog.Scheme> bySlug = new LinkedHashMap<>();
        catalog.schemes().forEach(scheme -> bySlug.put(scheme.slug(), scheme));
        return bySlug;
    }

    private List<Catalog.Scheme> selectedSchemes(String slug) {
        if ("all".equals(slug)) {
            return catalog.schemes();
        }
        Catalog.Scheme scheme = catalog.find(slug);
        if (scheme == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        return List.of(scheme);
    }

    private AssessmentRepository.Profile applicant(Long user, Long id, Model model) {
        if (user == null) {
            return null;
        }
        var profiles = assessments.profiles(user);
        var selected = profiles.stream().filter(p -> id == null || p.id() == id).findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        model.addAttribute("profiles", profiles);
        model.addAttribute("profile", selected);
        return selected;
    }

    private long userId(Principal principal) {
        return accounts.find(principal.getName()).orElseThrow().id();
    }

    private String form(String slug, List<Catalog.Scheme> schemes, Map<String, String> answers,
                        Map<String, String> errors, Model model) {
        model.addAttribute("slug", slug);
        model.addAttribute("scheme", schemes.size() == 1 ? schemes.get(0) : null);
        model.addAttribute("questions", catalog.questionsFor(schemes));
        model.addAttribute("answers", answers);
        model.addAttribute("errors", errors);
        return "check";
    }
}

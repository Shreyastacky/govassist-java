# Demonstration and viva

## Five-minute demo

1. Start the packaged JAR and open the homepage. Open **Rule Lab** and switch between 18, 19, and unknown. The sample uses the same Java validator and evaluator as the full questionnaire.
2. Open PM SVANidhi. Answer Yes to street vending and vending in a town or peri-urban area. Leave local coverage blank and submit. Expand **Missing answers** and open the guideline citation for local coverage.
3. Open PMFME individual-unit support. Enter `-1` as the number of workers and submit. Show the inline validation. Then use the complete profile below to compare ages 18 and 19.
4. Sign in with a fictional local account and run a check. Open its saved record from the dashboard. Edit and rerun the form, then open the previous result to show that it keeps the original answers.
5. Open **Sources** and filter PMFME passages using `education` or `bank loan`. If internet access is unavailable, use the excerpts already bundled with the app.

For a two-tab check, open the same signed-in profile in both tabs before saving. Save age 19 in one tab, then submit age 18 from the other. The second tab shows the attempted value and asks you to reload the saved answers. Refresh the age 19 result and check the dashboard to show that the refresh created no extra row.

## PMFME boundary profile

Use these fictional inputs for the individual-unit route.

| Question or field | Answer |
| --- | --- |
| Applying individually | Yes |
| Unit already operating | Yes |
| Identified in the state plan or verified by the Resource Person | Yes |
| Business unincorporated | Yes |
| Worker count | 9 |
| Ownership rights | Yes |
| Age in completed years | 18 on the first run, 19 on the second |
| Passed Class VIII | Yes |
| Family member already received this support | No |
| Will formalize the unit | Yes |
| Own contribution | 10 |
| Will take a bank loan | Yes |

The first run returns **A criterion was not met**. Under **How this criterion was checked**, the age comparison shows **Answer: 18** and **Required: More than 18**, while the Class VIII comparison passes. The second run returns **Matches recorded criteria**. A negative worker count returns validation feedback and creates no assessment.

## Individual viva prompts

- **Trace a form submission.** The browser posts strings to `PortalController`. `EligibilityEngine.validate` accepts only defined values. The engine then evaluates the scheme, the controller adds the result to the model, and Thymeleaf renders it. Signed-in checks use the repository before showing the result.
- **What happens when one answer is missing?** The engine retains the missing key so the result can name the unanswered question. A known failure still controls the verdict when another criterion is missing.
- **How does an assessment remain unchanged after a new run?** Current profile answers and historical reports are in separate tables. A later check rewrites current answers and inserts a new row containing its own JSON snapshot.
- **Where are the collections used?** A `LinkedHashMap` indexes questions and holds submitted answers. A `LinkedHashSet` removes duplicate missing keys while keeping order. A `List` preserves criterion order for display.
- **What does the database transaction protect?** A signed-in check saves the run, current answers, result rows and profile revision together. If a late insert fails, earlier inserts and answer changes roll back too.
- **Why do you need both a revision and a submission UUID?** The revision stops a stale form from replacing newer answers. The UUID lets the same submission retry safely and return the result it already created.
- **What is the difference between a criterion and a comparison?** A criterion describes a requirement and points to a source passage. It can contain several comparisons. Age and education are two comparisons within one PMFME criterion.
- **Where are the rule thresholds?** In the JSON catalogue. Java represents the comparison and evaluates it using `BigDecimal` for numeric operands.
- **Which result is shown at age 18 for PMFME?** The individual-support criterion uses `age > 18`, so 18 fails and 19 passes the age part.
- **What can the demonstration establish?** It can show correct execution against the bundled rule versions, data persistence, and source traceability. The authority still decides applications.

# GovAssist Java

GovAssist checks a person's answers against a documented set of criteria for eight Indian government schemes. The result explains which criteria were met, which failed, and which still need an answer. Each criterion links to the source PDF and page.

The UCS553 edition is a standalone Java web application. It uses Spring Boot MVC, Thymeleaf, Spring Security, JDBC, and an embedded H2 database. The first build retrieves dependencies from Maven Central. After that, the packaged application runs locally without API keys or external services. Opening a linked government PDF requires internet access.

## Run the application

Install a JDK, version 17 or newer. From this repository:

| Platform | Build and test | Start the packaged application |
| --- | --- | --- |
| Windows PowerShell | `./mvnw.cmd verify` | `java -jar target/govassist-java-1.0.0.jar` |
| macOS or Linux | `./mvnw verify` | `java -jar target/govassist-java-1.0.0.jar` |

Open <http://127.0.0.1:8080>. To pick another port, append `--server.port=8081` to the Java command. Stop the server with Ctrl+C.

## What to demonstrate

- Browse and search the eight schemes. Open one questionnaire or use **Check across schemes** to reuse the same answers.
- Leave a question blank. Read the missing-answer list and the cited criteria in the result.
- In **Sources**, filter excerpts by scheme and search terms.
- Register with a fictional email such as `student@example.test`. Sign in, run a check, and open it again from the dashboard. Add a household profile to keep a second applicant's answers separate.
- The questionnaire can show questions and answer choices in English, Hindi, Punjabi, and Tamil. Navigation, validation messages, results, and source excerpts are in English.

The application screens against a coursework snapshot. Scheme authorities make the final decision. The project uses typed forms and keyword search for its local demonstration.

## Submission files

| Item | Location |
| --- | --- |
| Java source, templates and rule data | [src/main](src/main) |
| Database script | [schema.sql](src/main/resources/schema.sql) |
| Tests | [src/test](src/test) |
| Design, routes and data notes | [Design notes](docs/design.md) |
| Live demo and individual viva | [Demonstration guide](docs/demo.md) |
| Editable report | [GovAssist Java Project Report.docx](submission/GovAssist%20Java%20Project%20Report.docx) |
| Editable presentation | [GovAssist Java Presentation.pptx](submission/GovAssist%20Java%20Presentation.pptx) |

H2 initializes its tables from `schema.sql` at start-up and stores data in `runtime/govassist.mv.db`. Test runs use an in-memory database. Run the demo with fictional data, and use one server process per database file.

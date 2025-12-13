Title: Rollback to Java 17: update docs & configs

This PR rolls back migration artifacts and deployment configs to Java 17, verifies build and deploy with Java 17, and updates migration documentation.

Changes made:
- Updated migration docs (`.github/appmod/code-migration/...`) to reference Java 17 and correct JDK links
- Confirmed `back-end/pom.xml` compiler source/target set to 17
- Confirmed `back-end/src/main/appengine/app.yaml` runtime set to `java17`
- Performed local build and deploy verification with Java 17 (commit `dc49f753f46ca7b105a2b3882649f69ca05aa064`)

Testing performed:
- `mvn -f back-end/pom.xml clean package` succeeded locally with `JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-17.jdk/Contents/Home`
- `mvn -f back-end/pom.xml clean package appengine:deploy` succeeded and deployed to https://chem4ap.uc.r.appspot.com

Notes for reviewer:
- No application code changes were required; only docs/config updates
- CI should run as usual; please ensure CI uses Java 17 for the build

How to open PR via CLI (optional):

```bash
# requires GitHub CLI and authentication
cd /Users/wight/git/chem4ap
gh pr create --title "Rollback to Java 17: update docs & configs" --body-file .github/PULL_REQUEST_DRAFT.md --base main --head appmod/java-migration-20251212091028 --draft
```

---
description: Run the full build, tests and coverage gate, then report what actually passed
---

Run the complete verification pipeline and report the real results.

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
./gradlew check
```

Then report:

1. Whether compilation succeeded.
2. Unit test results — count passed and failed, and name any failure.
3. Integration test results (these need Docker; say so explicitly if the daemon is down
   rather than reporting them as passed).
4. The actual coverage percentage from
   `build/reports/jacoco/test/html/index.html`, and whether it clears the 85% gate.

Report failures with their output. Do not describe a step as passing if it did not run.

# kotoba CAD

Open-source CAD/CAM workbench as EDN geometry data + portable CLJC model.

This repository follows the kotoba industrial-app pattern:

- `resources/cad/domain.edn` is the data registry.
- `src/kotoba/cad/core.cljc` is the pure portable domain engine.
- `src/kotoba/cad/runner.clj` is a conservative host dry-run runner.
- `docs/index.html` is the GitHub Pages workbench.

Pages: https://kotoba-lang.github.io/cad/

## Scope

This is an OSS workbench skeleton for 産業 CAD/CAM. It does not claim proprietary compatibility with commercial systems. It focuses on open artifact registries, policy-gated runners, coverage/maturity scoring, and EDN handoff.

## Verify

```sh
clojure -M -e '(load-file "src/kotoba/cad/core.cljc") (println :ok)'
python3 -m http.server 8765 --directory docs
```

## Test

```sh
clojure -M:test
```

`test/kotoba/cad/core_test.clj` and `test/kotoba/cad/runner_test.clj` cover
artifact classification, coverage/maturity scoring, the runner-plan
policy-gate builder, and the dry-run runner (14 tests / 46 assertions, 0
failures). Notably, `score`/`coverage-assessment`/`co-sientist-review` throw
`IllegalArgumentException` on the JVM for realistic inputs — `Math/round`
there is fed exact `Ratio`/`Long` values from plain integer division rather
than doubles, which it has no overload for; this is JVM-only (the
ClojureScript/browser build has no separate Ratio/Long numeric types) and is
pinned down, not silently worked around, by the tests.

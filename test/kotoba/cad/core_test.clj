(ns kotoba.cad.core-test
  "Tests for the pure kotoba CAD domain engine (artifact classification,
  coverage/maturity scoring, dry-run runner-plan policy gates).

  IMPORTANT finding while writing these tests: `score`, `coverage-assessment`,
  and `co-sientist-review` use `Math/round` on values produced by plain
  Clojure integer division (`/`). On the JVM that division yields an exact
  `clojure.lang.Ratio` (e.g. 4/7) -- or, at the range boundaries, an exact
  `Long` -- rather than a `double`, *unless* one of the `min 1.0 ...` caps is
  pushed strictly above 1.0 (which forces the literal double `1.0` to win the
  comparison). `Math/round` only has overloads for `float`/`double`, and
  Clojure's reflective interop does not widen a boxed `Long`/`Ratio` argument
  to satisfy them, so the call throws `IllegalArgumentException`
  (\"No matching method round found taking 1 args\"). This makes `score`
  throw for the entire realistic 'within quota' input range (<=5 artifacts,
  <=4 approvals, any stage other than exactly 0 or 7), and makes
  `coverage-assessment` (and therefore `co-sientist-review`, which calls it
  unconditionally) throw for *every* input -- its own final average is always
  a plain Long, with no escape valve. This is a JVM-only failure mode: the
  ClojureScript build (app.cljs via shadow-cljs) has no separate Ratio/Long
  numeric types, so `/` and `Math/round` never hit it in the browser. See the
  `-always-throws-on-jvm` / `-throws-on-jvm-...` tests below, which pin down
  this real, current behavior rather than papering over it."
  (:require [clojure.test :refer [deftest is testing]]
            [kotoba.cad.core :as core]))

;; -- extension --------------------------------------------------------------

(deftest extension-normalizes-and-handles-edge-cases
  (testing "lower-cases and prefixes with a dot"
    (is (= ".dxf" (core/extension "drawing.DXF"))))
  (testing "uses the final segment when the filename has multiple dots"
    (is (= ".c" (core/extension "a.b.c"))))
  (testing "no dot in the filename -> nil"
    (is (nil? (core/extension "noext"))))
  (testing "nil filename -> nil"
    (is (nil? (core/extension nil)))))

;; -- classify-artifact --------------------------------------------------------

(deftest classify-artifact-maps-known-extensions
  (testing "dxf"
    (is (= {:artifact/id :cad/dxf
            :artifact/path "drawing.dxf"
            :artifact/description "2D drawing exchange"
            :artifact/phases ["drafting" "manufacturing"]}
           (core/classify-artifact "drawing.dxf"))))
  (testing "step and its stp alias share the same artifact id"
    (is (= :cad/step (:artifact/id (core/classify-artifact "part.step"))))
    (is (= :cad/step (:artifact/id (core/classify-artifact "part.stp")))))
  (testing "stl"
    (is (= :mesh/stl (:artifact/id (core/classify-artifact "mesh.stl")))))
  (testing "gcode and its nc alias share the same artifact id"
    (is (= :toolpath/gcode (:artifact/id (core/classify-artifact "toolpath.gcode"))))
    (is (= :toolpath/gcode (:artifact/id (core/classify-artifact "toolpath.nc")))))
  (testing "pdf"
    (is (= :drawing/pdf (:artifact/id (core/classify-artifact "released.pdf"))))))

(deftest classify-artifact-unknown-extension-fallback
  (is (= {:artifact/id :artifact/unknown
          :artifact/path "weird.xyz"
          :artifact/description "Unknown imported artifact"
          :artifact/phases []}
         (core/classify-artifact "weird.xyz"))))

;; -- score --------------------------------------------------------------------

(deftest score-computes-ratios-and-caps-over-quota-inputs
  (let [project {:stage 7
                 :artifacts (repeat 6 {})              ;; > 5 known artifact-formats
                 :approvals [:pm :qa :eng :lead :ceo]}  ;; > 4-person quorum
        result (core/score project)]
    (is (= 1 (:score/stage result)))
    (is (= 1.0 (:score/artifacts result))
        "6 artifacts against a 5-format registry is capped at 1.0, not 1.2")
    (is (= 1.0 (:score/approvals result))
        "5 approvals against a 4-person quorum is capped at 1.0, not 1.25")
    (is (= 100 (:score/overall result)))))

(deftest score-throws-on-jvm-for-projects-within-normalization-caps
  (testing "the reagent app's own :init db shape (stage 0, no artifacts, no approvals)"
    (is (thrown? IllegalArgumentException
                 (core/score {:stage 0 :artifacts [] :runner-results [] :runner-plan nil}))))
  (testing "a typical small in-progress project"
    (is (thrown? IllegalArgumentException
                 (core/score {:stage 3 :artifacts [{} {}] :approvals [:pm]}))))
  (testing "even a fully maxed-out but exactly-in-quota project"
    (is (thrown? IllegalArgumentException
                 (core/score {:stage 7 :artifacts (repeat 5 {}) :approvals [:a :b :c :d]})))))

;; -- coverage-assessment / co-sientist-review ---------------------------------

(deftest coverage-assessment-always-throws-on-jvm
  (testing "even the simplest empty project"
    (is (thrown? IllegalArgumentException (core/coverage-assessment {} []))))
  (testing "even a project for which `score` itself succeeds"
    ;; score/overall is a plain Long here (see score-computes-ratios... above),
    ;; but coverage-assessment's *own* final average over its 6 coverage-metrics
    ;; rows is always a plain Long too (never a Double) -- so it throws
    ;; independently of whether score succeeded.
    (is (thrown? IllegalArgumentException
                 (core/coverage-assessment
                  {:stage 7 :artifacts (repeat 6 {}) :approvals [:a :b :c :d :e]}
                  [{} {} {}])))))

(deftest co-sientist-review-always-throws-on-jvm
  (testing "co-sientist-review calls coverage-assessment unconditionally"
    (is (thrown? IllegalArgumentException (core/co-sientist-review {} [])))))

;; -- runner-plan ----------------------------------------------------------------

(deftest runner-plan-gates-adapters-by-input-availability
  (testing "no artifacts -> every adapter is blocked (missing-inputs)"
    (let [plan (core/runner-plan [])]
      (is (= 3 (count (:job/adapters plan))))
      (is (every? #(= :missing-inputs (:job.adapter/status %)) (:job/adapters plan)))
      (is (every? #(empty? (:job.adapter/inputs %)) (:job/adapters plan)))))
  (testing "a matching artifact per format -> every adapter passes the gate (ready)"
    (let [artifacts [{:artifact/id :cad/dxf :artifact/path "a.dxf"}
                      {:artifact/id :cad/step :artifact/path "b.step"}
                      {:artifact/id :toolpath/gcode :artifact/path "c.gcode"}]
          plan (core/runner-plan artifacts)
          by-id (into {} (map (juxt :job.adapter/id identity) (:job/adapters plan)))]
      (is (every? #(= :ready (:job.adapter/status %)) (:job/adapters plan)))
      (is (= [{:artifact/id :cad/dxf :artifact/path "a.dxf"}]
             (:job.adapter/inputs (by-id :runner/dxf-lint))))
      (is (= ["a.dxf"]
             (get-in (by-id :runner/dxf-lint) [:job.adapter/command :command/input-paths])))))
  (testing "only a dxf artifact -> the dxf-lint job passes the gate, the rest stay blocked"
    (let [plan (core/runner-plan [{:artifact/id :cad/dxf :artifact/path "only.dxf"}])
          by-id (into {} (map (juxt :job.adapter/id identity) (:job/adapters plan)))]
      (is (= :ready (:job.adapter/status (by-id :runner/dxf-lint))))
      (is (= :missing-inputs (:job.adapter/status (by-id :runner/step-metadata))))
      (is (= :missing-inputs (:job.adapter/status (by-id :runner/toolpath-check)))))))

(deftest runner-plan-embeds-conservative-dry-run-policy
  (let [plan (core/runner-plan [{:artifact/id :cad/dxf :artifact/path "a.dxf"}])]
    (is (= :dry-run-until-host-approved (:job/mode plan)))
    (doseq [adapter (:job/adapters plan)]
      (is (= {:network :deny :filesystem :workspace-only :approval :required-before-exec}
             (get-in adapter [:job.adapter/command :command/policy]))
          (str "policy gate missing/weakened for " (:job.adapter/id adapter))))))

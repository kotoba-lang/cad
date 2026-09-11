(ns kotoba.cad.runner
  "Conservative host-side dry-run runner for kotoba CAD EDN plans."
  (:require [clojure.edn :as edn]
            [clojure.java.process :as p]))

(def allowed #{"clojure" "fastqc" "bwa"})

(defn ready? [adapter] (= :ready (:job.adapter/status adapter)))
(defn argv [adapter] (get-in adapter [:job.adapter/command :command/argv]))

(defn dry-run [adapter]
  {:run/adapter (:job.adapter/id adapter)
   :run/tool (:job.adapter/software adapter)
   :run/operation (:job.adapter/operation adapter)
   :run/status :dry-run
   :run/argv (argv adapter)})

(defn execute! [adapter]
  (let [cmd (argv adapter)
        exe (first cmd)]
    (when-not (allowed exe)
      (throw (ex-info "Executable is not whitelisted" {:exe exe :adapter (:job.adapter/id adapter)})))
    (if (= "1" (System/getenv "KOTOBA_RUNNER_EXEC"))
      (let [res (apply p/exec cmd)]
        {:run/adapter (:job.adapter/id adapter)
         :run/status (if (zero? (:exit res)) :passed :failed)
         :run/stdout (:out res)
         :run/stderr (:err res)})
      (dry-run adapter))))

(defn -main [& [path]]
  (when-not path
    (throw (ex-info "Missing runner-plan.edn path" {})))
  (let [plan (edn/read-string (slurp path))]
    (prn {:runner/results (mapv execute! (filter ready? (:job/adapters plan)))})))

(ns codescene-ci-cd.utils)

(defn ex->str [e]
  (str e (or (ex-data e) "") (with-out-str (clojure.stacktrace/print-stack-trace e))))

(defn getenv-str [name default]
  (or (System/getenv name) default))

(defn getenv-bool [name default]
  (if-let [value (System/getenv name)]
    (#{"1"  "TRUE"} value)
    default))

(defn getenv-int [name default]
  (if-let [value (System/getenv name)]
    (Integer/parseInt value)
    default))

(defn delta-analysis-config [project-id repo]
  {:fail-on-failed-goal                  (getenv-bool "CODESCENE_CI_CD_FAIL_ON_FAILED_GOAL" true)
   :fail-on-declining-code-health        (getenv-bool "CODESCENE_CI_CD_FAIL_ON_DECLINING_CODE_HEALTH" true)
   :fail-on-high-risk                    (getenv-bool "CODESCENE_CI_CD_FAIL_ON_HIGH_RISK" true)
   :risk-threshold                       (getenv-int "CODESCENE_CI_CD_RISK_THRESHOLD" 7)
   :codescene-delta-analysis-url         (format "%s/projects/%s/delta-analysis"
                                                 (getenv-str "CODESCENE_URL" "http://localhost:3003")
                                                 project-id)
   :codescene-user                       (getenv-str "CODESCENE_USER" "bot")
   :codescene-password                   (getenv-str "CODESCENE_PASSWORD" "secret")
   :codescene-repository                 repo
   :codescene-coupling-threshold-percent (getenv-int "CODESCENE_CI_CD_COUPLING_THRESHOLD_PERCENT" 45)
   :http-timeout                          (getenv-int "CODESCENE_CI_CD_HTTP_TIMEOUT" 30000)})

(defn project-id [request]
  (or (get-in request [:query-params "project_id"]) "<unknown>"))
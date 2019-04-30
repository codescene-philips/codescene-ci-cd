(ns codescene-gitlab.core
  "Contains the entrypoint to the app, including argument parsing and validation"
  (:require [clojure.string :as string]
            [clojure.tools.cli :as cli]
            [codescene-gitlab.delta-analysis :as delta-analysis]
            [codescene-gitlab.gitlab-api :as gitlab]
            [codescene-gitlab.results :as results]
            [clojure.java.io :as io]
            [clojure.data.json :as json])
  (:gen-class))

(def ^:private codescene-note-identifier "4744e426-5795-11e9-8647-d663bd873d93")

(def ^:private cli-options
  [["-h" "--help"]
   ;; Codescene access settings
   [nil "--delta-analysis-url URL" "Project Delta Analysis URL"]
   ["-u" "--user USER" "CodeScene User"]
   ["-p" "--password PWD" "CodeScene Password"]
   ["-r" "--repository REPO" "Repository"]
   ;; Flags
   [nil "--analyze-individual-commits" "Individual Commits" :default false]
   [nil "--analyze-branch-diff" "By Branch" :default false]
   [nil "--use-biomarkers" "Use Biomarkers" :default false]
   [nil "--pass-on-failed-analysis" "Build Success on Failed Analysis" :default false]
   [nil "--fail-on-high-risk" "Mark as Unstable on High Risk" :default false]
   [nil "--fail-on-failed-goal" "Mark Build as Unstable on Failed Goals" :default false]
   [nil "--fail-on-declining-code-health" "Mark Build as Unstable on Code Health Decline" :default false]
   [nil "--create-gitlab-note" "Create Note For Gitlab Merge Request" :default false]
   ;; Arguments
   [nil "--coupling-threshold-percent THRESHOLD" "Temporal Coupling Threshold (in percent)" :default 75 :parse-fn #(Integer/parseInt %)]
   [nil "--risk-threshold THRESHOLD" "Risk Threshold" :default 9 :parse-fn #(Integer/parseInt %)]
   [nil "--previous-commit SHA" "Previous Commit Id"]
   [nil "--current-commit SHA" "Current Commit Id"]
   [nil "--base-revision SHA" "Base Revision Id"]
   [nil "--branch BRANCH" "Branch to analyze" :default "master"]
   [nil "--gitlab-api-url URL" "GitLab API URL"]
   [nil "--api-token TOKEN" "GitLab API Token"]
   [nil "--project-id ID" "GitLab Project ID" :parse-fn #(Integer/parseInt %)]
   [nil "--merge-request-iid IID" "GitLab Merge Request IID" :parse-fn #(Integer/parseInt %)]
   [nil "--result-path FILENAME" "Path where JSON output is generated"]
   [nil "--http-timeout TIMEOUT-MS" "Timeout for http API calls" :parse-fn #(Integer/parseInt %)]])

(defn- usage [options-summary]
  (->> ["Usage: codescene-gitlab [options]"
        "Options:"
        options-summary]
       (string/join \newline)))

(defn- error-msg [errors]
  (str "The following errors occurred while parsing your command:\n\n"
       (string/join \newline errors)))

(defn- validation-error-msg [errors]
  (str "The following validation errors occurred for your command:\n\n"
       (string/join \newline errors)))

(defn- exit [ok? msg log-fn]
  (log-fn msg)
  (System/exit (if ok? 0 1)))

(defn- exit-with-exception[ok? msg e log-fn]
  (log-fn msg)
  (log-fn (with-out-str (clojure.stacktrace/print-stack-trace e)))
  (System/exit (if ok? 0 1)))

(defn- validate-options [options]
  (let [{:keys [analyze-individual-commits analyze-branch-diff create-gitlab-note
                delta-analysis-url user password repository
                previous-commit current-commit base-revision
                gitlab-api-url api-token project-id merge-request-iid]} options]
    (filter
      some?
      (concat
        (when-not (some? delta-analysis-url) ["Delta analysis URL not specified"])
        (when-not (some? user) ["Codescene user not specified"])
        (when-not (some? password) ["Codescene password not specified"])
        (when-not (some? repository) ["Codescene repository not specified"])
        (when analyze-individual-commits
          [(when-not (some? current-commit) "Current commit not specified")
           (when-not (some? previous-commit) "Previous commit not specified")])
        (when analyze-branch-diff
          ;; TODO: Don't report this twice...
          [(when-not (some? current-commit) "Current commit not specified")
           (when-not (some? base-revision) "Base revision not specified")])
        (when create-gitlab-note
          [(when-not (some? gitlab-api-url) "GitLab API URL not specified")
           (when-not (some? api-token) "API token not specified")
           (when-not (some? project-id) "Project Id not specified")
           (when-not (some? merge-request-iid) "Merge request IID not specified")])))))

(defn parse-args
  [args]
  (let [{:keys [options arguments errors summary]} (cli/parse-opts args cli-options)]
    (cond
      ;; help => exit OK with usage summary
      (:help options) {:exit-message (usage summary) :ok? true}
      ;; errors => exit with description of errors
      errors {:exit-message (error-msg errors)}
      :else (let [validation-errors (validate-options options)]
              (if (seq validation-errors)
                ;; failed custom validation => exit with usage summary
                {:exit-message (validation-error-msg validation-errors)}
                ;; success => exit with options
                {:options options})))))

(defn run-analysis [options log-fn]
  (let [{:keys [analyze-individual-commits analyze-branch-diff previous-commit base-revision]} options]
    (concat
      (when (and analyze-individual-commits (some? previous-commit))
        (delta-analysis/analyze-individual-commits-for options log-fn))
      (when (and analyze-branch-diff (some? base-revision))
        (delta-analysis/analyze-work-on-branch-for options log-fn)))))

(defn find-codescene-note-ids [gitlab-api-url api-token project-id merge-request-iid codescene-note-identifier timeout]
  (->> (gitlab/get-merge-request-notes gitlab-api-url api-token project-id merge-request-iid timeout)
       (filter #(string/includes? (:body %) codescene-note-identifier))
       (map :id)))

(defn create-gitlab-note [options results log-fn]
  (log-fn "Create GitLab Note for merge request...")
  (let [{:keys [gitlab-api-url api-token project-id merge-request-iid http-timeout]} options
        note-ids (find-codescene-note-ids gitlab-api-url api-token project-id merge-request-iid codescene-note-identifier http-timeout)
        markdown (results/as-markdown results options )
        identifier-comment (format "<!--%s-->" codescene-note-identifier)]
    (doseq [note-id note-ids]
      (log-fn (format "Remove old GitLab Note with id %d for merge request..." note-id))
      (gitlab/delete-merge-request-note gitlab-api-url api-token project-id merge-request-iid note-id http-timeout))
    (gitlab/create-merge-request-note gitlab-api-url api-token project-id merge-request-iid
                                      (string/join \newline [identifier-comment markdown]) http-timeout)))

(defn log-result [options results log-fn]
  (log-fn (results/as-text results options)))

(defn ex->str [e]
  (str e (or (ex-data e) "") (with-out-str (clojure.stacktrace/print-stack-trace e))))

(defn run-analysis-and-handle-result [options log-fn]
  (try
    (let [results (run-analysis options log-fn)
          success (not-any? :unstable results)]
      (when-let [result-path (:result-path options)]
        (with-open [wr (io/writer result-path)]
          (.write wr (json/write-str results))))
      (when (:create-gitlab-note options)
        (create-gitlab-note options results log-fn))
      (when (:log-result options)
        (log-result options results log-fn))
      [success (if success "CodeScene delta analysis ok!" "CodeScene delta analysis detected problems!")])
    (catch Exception e
      [(:pass-on-failed-analysis options) (str "CodeScene couldn't perform the delta analysis:" (ex->str e))])))

(defn -main [& args]
  (let [{:keys [options exit-message ok?]} (parse-args args)
        log-fn println]
    (if exit-message
      (exit ok? exit-message log-fn)
      (let [[ok? exit-message] (run-analysis-and-handle-result options log-fn)]
        (exit ok? exit-message log-fn)))))

(comment
  (def options {:use-biomarkers true,
                :analyze-individual-commits false,
                :repository "cmake-project-template",
                :create-gitlab-note true,
                :password "0bca8fd9-c137-47c7-9c2b-98f6fbc2cd1c",
                :delta-analysis-url "http://localhost:3005/projects/2/delta-analysis",
                :analyze-branch-diff true,
                :fail-on-declining-code-health true,
                :risk-threshold 7,
                :pass-on-failed-analysis true,
                :base-revision "origin/master",
                :coupling-threshold-percent 45,
                :merge-request-iid 1,
                :branch "my-branch",
                :project-id 4,
                :api-token "Q9nE8fxxs5xymf-koUD-",
                :current-commit "96539487a532cadc1f9177cf4b6b1a61bad88049",
                :gitlab-api-url "http://gitlab:80/api/v4",
                :user "bot",
                :fail-on-failed-goal true,
                :fail-on-high-risk true})
  (def results (binding [clojure.java.shell/*sh-dir* d] (run-analysis-and-handle-result options println))))
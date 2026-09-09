(ns io.github.getcolors.k3s.workflow-test
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.test :refer [deftest is]]
   [green.workflow :as wf]
   [io.github.getcolors.k3s.tools :as tools]
   [io.github.getcolors.k3s.machine :as machine]
   [io.github.getcolors.k3s.validate-test :as vt]
   [io.github.getcolors.k3s.workflow :as workflow]))

(defn- temp-dir []
  (let [f (java.io.File/createTempFile "k3s-test-" "")]
    (.delete f)
    (.mkdirs f)
    (str f)))

(defn- steps-for [event step]
  (rest (workflow/wire-fn step {:green/event event})))

(deftest create-forks-after-compute
  (is (= [:k3s/compute] (steps-for :create :k3s/start)))
  (is (= [:k3s/ansible-local :k3s/ansible-remote]
         (steps-for :create :k3s/compute))))

(deftest delete-cleans-local-state-before-destroy
  (is (= [:k3s/ansible-cleanup] (steps-for :delete :k3s/start)))
  (is (= [:k3s/compute] (steps-for :delete :k3s/ansible-cleanup))))

(deftest every-side-effect-is-dry-runnable
  (is (= #{:k3s/compute :k3s/ansible-local :k3s/ansible-remote :k3s/ansible-cleanup}
         (set workflow/side-effecting-steps))))

(defn- start
  ([opts] (workflow/start-step opts {}))
  ([opts env] (workflow/start-step opts env)))

(deftest valid-build-needs-no-credentials
  (is (= 0 (:green/exit (start (assoc vt/base :green/event :build))))))

(deftest real-create-needs-provider-token
  (is (= 2 (:green/exit (start (assoc vt/base :green/event :create)))))
  (is (= 0 (:green/exit
            (start (assoc vt/base :green/event :create)
                   {"COLORS_PAR_HCLOUD_TOKEN" "token"})))))

(deftest dry-run-needs-no-credentials
  (is (= 0 (:green/exit
            (start (assoc vt/base :green/event :create :green/dry-run true))))))

(deftest delete-requires-owned-inventory-after-protection
  (with-redefs [machine/load-inventory (fn [& _] {:green/exit 1 :green/err "missing inventory"})]
    (let [token {"COLORS_PAR_HCLOUD_TOKEN" "token"}]
      (is (= 2 (:green/exit (start (assoc vt/base :green/event :delete) token))))
      (is (= 1 (:green/exit (start (assoc vt/base :green/event :delete) (assoc token "COLORS_PAR_COMPUTE_PREVENT_DESTROY" "false"))))))))

(deftest profile-overlay-stops-before-rendering
  (let [result (start (assoc vt/base :green/event :build)
                      {"COLORS_PAR_PROFILE" "once-colors"})]
    (is (= 2 (:green/exit result)))
    (is (str/includes? (:green/err result) "COLORS_PAR_PROFILE"))))

(deftest state-key-is-library-owned
  (let [opts (assoc vt/base :green/event :build :workdir (temp-dir))
        _ (machine/step opts)
        backend (slurp (str (tools/tool-dir opts tools/compute-tool) "/shared/backend.tf.json"))]
    (is (str/includes? backend "compute"))
    (is (not (str/includes? backend "k3s-compute.tfstate")))))

(deftest whole-build-renders-every-stage
  (let [dir (temp-dir)
        result (wf/run workflow/workflow
                       (assoc vt/base :green/event :build :workdir dir :profile "built"))
        root (str dir "/built/")]
    (is (= 0 (:green/exit result)))
    (doseq [file ["k3s-compute/shared/backend.tf.json"
                  "k3s-compute/nodes/0/node-none.tf.json"
                  "k3s-ansible-local/main.yml"
                  "k3s-ansible-local/inventory.ini"
                  "k3s-ansible-remote/main.yml"
                  "k3s-ansible-remote/gitops.yml"
                  "k3s-ansible-remote/inventory.json"]]
      (is (.exists (io/file (str root file))) (str file " should exist")))))

(deftest dry-run-touches-nothing
  (let [dir (temp-dir)
        result (wf/run workflow/workflow
                       (assoc vt/base :green/event :create :green/dry-run true
                              :workdir dir :profile "dry"))]
    (is (= 0 (:green/exit result)))
    (is (empty? (seq (.listFiles (io/file dir)))))))

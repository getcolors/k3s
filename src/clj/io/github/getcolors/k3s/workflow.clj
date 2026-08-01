(ns io.github.getcolors.k3s.workflow
  "The single-node K3s lifecycle DAG."
  (:require
   [clojure.string :as str]
   [green.cli :as green-cli]
   [green.dry-run :as dry-run]
   [green.progress :as progress]
   [green.tofu :as tofu]
   [green.workflow :as wf]
   [io.github.getcolors.k3s.tools :as tools]
   [io.github.getcolors.k3s.validate :as validate]))

(def ^:private lifecycle-events #{:create :delete})
(def ^:private defaults
  {:compute-prevent-destroy true
   :provider-compute "hcloud"
   :provider-dns "no-infra"
   :provider-backend "local"
   :repository-branch "main"
   :repository-path "./k8s"
   :workdir ".colors"})

(defn start-step
  "Overlay credentials, validate, and guard real destruction."
  ([opts] (start-step opts (System/getenv)))
  ([opts env]
   (let [opts (green-cli/read-pars (merge defaults opts) env)
         event (:green/event opts)
         real? (not (:green/dry-run opts))
         errors (vec
                 (concat
                  (validate/env-errors env)
                  (validate/state-errors opts)
                  (when (and real? (lifecycle-events event))
                    (validate/secret-errors opts))
                  (when (and real? (= :delete event)
                             (:compute-prevent-destroy opts))
                    [(str "compute destruction is protected; set "
                          (green-cli/par-name :compute-prevent-destroy)
                          "=false to delete")])))]
     (if (seq errors)
       (assoc opts :green/exit 2 :green/err (str/join "\n" errors))
       (assoc opts :green/exit 0)))))

(defn ansible-cleanup-step
  "Remove the SSH block and both rendered Ansible trees before compute destroy."
  [opts]
  (-> opts tools/ansible-local-step tools/ansible-remote-step))

(defn wire-fn
  [step run-opts]
  (case (:green/event run-opts)
    :delete
    (case step
      :k3s/start [start-step :k3s/ansible-cleanup]
      :k3s/ansible-cleanup [ansible-cleanup-step :k3s/compute]
      :k3s/compute [tools/compute-step])

    ;; :create and :build
    (case step
      :k3s/start [start-step :k3s/compute]
      :k3s/compute [tools/compute-step :k3s/ansible-local :k3s/ansible-remote]
      :k3s/ansible-local [tools/ansible-local-step]
      :k3s/ansible-remote [tools/ansible-remote-step])))

(defn backend-advice
  "Write the selected backend with a package-specific remote state key."
  [tool]
  (let [dir-fn #(tools/tool-dir % tool)
        state-key #(str (or (:profile %) "k3s") "/" tool ".tfstate")]
    (tofu/backends
     #(or (:provider-backend %) "local")
     {"local" (tofu/local-backend-advice dir-fn)
      "s3" (tofu/s3-backend-advice
            dir-fn
            (fn [opts]
              {:bucket (:s3-bucket opts)
               :key (state-key opts)
               :region (:s3-region opts)}))
      "r2" (tofu/r2-backend-advice
            dir-fn
            (fn [opts]
              {:bucket (:r2-bucket opts)
               :key (state-key opts)
               :endpoint (:r2-endpoint opts)}))})))

(def side-effecting-steps
  [:k3s/compute :k3s/ansible-local :k3s/ansible-remote :k3s/ansible-cleanup])

(def workflow
  (-> (wf/workflow {:start :k3s/start :wire-fn wire-fn})
      (wf/advice-add :k3s/compute :before ::backend
                     (backend-advice tools/compute-tool))
      progress/advise
      (dry-run/advise side-effecting-steps)))

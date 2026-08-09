(ns io.github.getcolors.k3s.workflow
  "The single-node K3s lifecycle DAG."
  (:require
   [clojure.string :as str]
   [green.cli :as green-cli]
   [green.dry-run :as dry-run]
   [green.lifecycle :as lifecycle]
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
   (lifecycle/preflight
    opts {:defaults defaults :overlay green-cli/read-pars
          :validators
          [(fn [_ env _] (validate/env-errors env))
           (fn [opts _ _] (validate/state-errors opts))
           (fn [opts _ {:keys [event real?]}]
             (when (and real? (lifecycle-events event)) (validate/secret-errors opts)))
           (fn [opts _ {:keys [event real?]}]
             (when (and real? (= :delete event) (:compute-prevent-destroy opts))
               [(str "compute destruction is protected; set "
                     (green-cli/par-name :compute-prevent-destroy) "=false to delete")]))]}
    env)))

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
  (tofu/conventional-backend-advice
   {:dir-fn #(tools/tool-dir % tool)
    :key-fn #(str (or (:profile %) "k3s") "/" tool ".tfstate")}))

(def side-effecting-steps
  [:k3s/compute :k3s/ansible-local :k3s/ansible-remote :k3s/ansible-cleanup])

(def workflow
  (-> (wf/workflow {:start :k3s/start :wire-fn wire-fn})
      (wf/advice-add :k3s/compute :before ::backend
                     (backend-advice tools/compute-tool))
      progress/advise
      (dry-run/advise side-effecting-steps)))

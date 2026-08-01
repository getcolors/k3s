(ns io.github.getcolors.k3s.tools
  "Compute and Ansible steps plus their deterministic render builders."
  (:require
   [cheshire.core :as json]
   [clojure.java.io :as io]
   [clojure.walk :as walk]
   [green.ansible :as ansible]
   [green.scaffold :as sc]
   [green.tofu :as tofu]
   [green.workflow :as wf]
   [io.github.getcolors.k3s.utils :as utils]
   [io.github.getcolors.k3s.validate :as validate]))

(def compute-tool "k3s-compute")
(def ansible-local-tool "k3s-ansible-local")
(def ansible-remote-tool "k3s-ansible-remote")

(def ^:private k3s-root "io.github.getcolors.k3s.tools")
(def ^:private once-root "io.github.getcolors.once.tools")
(def ^:private raw-template :io.github.getcolors.k3s/raw)
(def ^:private template-opts
  {:tag-open \< :tag-close \> :filter-open \{ :filter-close \}})

(defn tool-dir
  "Resolve a stage beside colors.yml, never relative to the caller."
  [opts tool]
  (let [workdir (io/file (or (:workdir opts) ".colors"))
        state-dir (when-not (.isAbsolute workdir)
                    (some-> (:green/state-file opts) io/file .getAbsoluteFile .getParent))
        root (if state-dir (io/file state-dir workdir) workdir)]
    (str (io/file root (or (:profile opts) "k3s") tool))))

(defn- once-template [tool provider file]
  (keyword (str once-root "." tool "." provider) file))

(defn- k3s-template [tool file]
  (keyword (str k3s-root "." tool) file))

(defn- template-spec [template target data]
  {:template template :target target :data data :opts template-opts})

(defn- raw-spec [target content]
  (template-spec raw-template target {:content content}))

(defn credential-env
  "Provider and backend environment additions, omitting absent credentials."
  [opts & slots]
  (not-empty
   (into {}
         (keep (fn [[k env-var]]
                 (when-let [v (not-empty (str (get opts k)))]
                   [env-var v])))
         (apply merge (map #(validate/tofu-env opts %)
                           (conj (vec slots) :provider-backend))))))

(defn fallback-compute-params
  "Stand-in values that keep build and dry-run credential-free."
  [{:keys [profile]}]
  {:ip "192.168.0.1"
   :sudoer "root"
   :name (or profile "k3s")
   :user "root"})

(defn compute-specs
  "ONCE's hcloud server plus this package's firewall and attachment."
  [opts dir]
  [(template-spec (once-template "tofu" "hcloud" "main.tf")
                  (str dir "/main.tf") opts)
   (template-spec (k3s-template "tofu.hcloud" "firewall.tf")
                  (str dir "/firewall.tf") opts)])

(defn- output-params [opts]
  (some-> (get-in opts [:tofu/outputs :params]) walk/keywordize-keys))

(defn compute-step
  "Render/apply compute, then adopt the server address for both Ansible stages."
  [opts]
  (let [dir (tool-dir opts compute-tool)
        fallback (fallback-compute-params opts)
        result (tofu/tofu-with-spec
                opts (compute-specs opts dir)
                {:dir dir :env (credential-env opts :provider-compute)})]
    (cond
      (wf/failed? result) result
      (= :build (:green/event opts))
      (merge result fallback {:k3s/compute-params fallback})
      (= :delete (:green/event opts)) result
      :else
      (let [params (merge fallback (output-params result))]
        (merge result params {:k3s/compute-params params})))))

(defn inventory
  "One-host JSON inventory keyed by the managed SSH alias."
  [{:keys [ip user host-alias]}]
  (json/generate-string
   {:all {:children {:k3s {:hosts {(or host-alias "k3s")
                                    {:ansible_host ip :ansible_user user}}}}}}
   {:pretty true}))

(defn data-fn
  "Complete deterministic template data for build as well as create."
  [opts]
  (assoc opts
         :ip (or (not-empty (str (:ip opts))) "192.168.0.1")
         :user (or (not-empty (str (:user opts))) "root")
         :host-alias (utils/host-alias opts)
         :repository-branch (or (not-empty (str (:repository-branch opts))) "main")
         :repository-path (or (not-empty (str (:repository-path opts))) "./k8s")))

(defn ansible-local-step
  "Add or remove the package-owned Host block in ~/.ssh/config."
  [opts]
  (let [dir (tool-dir opts ansible-local-tool)
        data (data-fn opts)
        specs [(template-spec (k3s-template "ansible-local" "ansible.cfg")
                              (str dir "/ansible.cfg") data)
               (template-spec (k3s-template "ansible-local" "inventory.ini")
                              (str dir "/inventory.ini") data)
               (template-spec (k3s-template "ansible-local" "main.yml")
                              (str dir "/main.yml") data)]
        delete? (= :delete (:green/event opts))]
    (ansible/ansible-with-spec
     opts
     {:dir dir
      :inventory "inventory.ini"
      :playbooks {:create "main.yml" :delete "main.yml"}
      :extra-vars {:host_alias (:host-alias data)
                   :ip (:ip data)
                   :user (:user data)
                   :block_state (if delete? "absent" "present")}}
     specs)))

(defn ansible-remote-step
  "Install K3s and Flux, then converge the public GitOps source."
  [opts]
  (let [dir (tool-dir opts ansible-remote-tool)
        data (data-fn opts)
        specs [(template-spec (k3s-template "ansible-remote" "ansible.cfg")
                              (str dir "/ansible.cfg") data)
               (template-spec (k3s-template "ansible-remote" "main.yml")
                              (str dir "/main.yml") data)
               (template-spec (k3s-template "ansible-remote" "gitops.yml")
                              (str dir "/gitops.yml") data)
               (raw-spec (str dir "/inventory.json") (inventory data))]
        rendered (sc/scaffold opts specs)]
    (if (or (= :build (:green/event opts))
            (= :delete (:green/event opts)))
      rendered
      (ansible/ansible-step
       rendered
       {:dir dir
        :inventory "inventory.json"
        :playbooks {:create "main.yml"}
        :host-key-checking false}))))

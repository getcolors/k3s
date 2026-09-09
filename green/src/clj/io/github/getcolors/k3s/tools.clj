(ns io.github.getcolors.k3s.tools
  "Compute and Ansible steps plus their deterministic render builders."
  (:require
   [cheshire.core :as json]
   [clojure.java.io :as io]
   [clojure.walk :as walk]
   [green.ansible :as ansible]
   [green.cli :as green-cli]
   [green.providers :as provider-ops]
   [green.scaffold :as sc]
   [green.tofu :as tofu]
   [green.workflow :as wf]
   [io.github.getcolors.k3s.utils :as utils]
   [io.github.getcolors.k3s.machine :as machine]
   [io.github.getcolors.k3s.validate :as validate]))

(def compute-tool "k3s-compute")
(def ansible-local-tool "k3s-ansible-local")
(def ansible-remote-tool "k3s-ansible-remote")

(def ^:private k3s-root "io.github.getcolors.k3s.tools")
(def ^:private template-opts sc/preserve-jinja-delimiters)

(defn tool-dir
  "Resolve a stage beside colors.yml, never relative to the caller."
  [opts tool]
  (green-cli/stage-dir opts tool {:default-profile "k3s"}))

(defn- k3s-template [tool file]
  (keyword (str k3s-root "." tool) file))

(defn- template-spec [template target data]
  {:template template :target target :data data :opts template-opts})

(defn- raw-spec [target content]
  (sc/content-spec target content))

(def fallback-compute-params machine/fallback-params)
(def compute-step machine/step)

(defn inventory
  "One-host JSON inventory keyed by the managed SSH alias."
  [{:keys [ip user host-alias ssh-private-key-path]}]
  (json/generate-string
   {:all {:children {:k3s {:hosts {(or host-alias "k3s")
                                    (cond-> {:ansible_host ip :ansible_user user} ssh-private-key-path (assoc :ansible_ssh_private_key_file ssh-private-key-path))}}}}}
   {:pretty true}))

(defn data-fn
  "Complete deterministic template data for build as well as create."
  [opts]
  (assoc opts
         :ip (str (:ip opts))
         :user (or (not-empty (str (:user opts))) "root")
         :host-alias (utils/host-alias opts)
         :provider-dns (or (not-empty (str (:provider-dns opts))) "no-infra")
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
      :extra-vars {:ssh_legacy_marker_prefix "k3s" :host_alias (:host-alias data)
                   :ssh_hosts [{:name (:host-alias data) :ip (:ip data) :user (:user data) :identity_file (:ssh-private-key-path opts)}]
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

(ns io.github.getcolors.k3s.tools-test
  (:require
   [cheshire.core :as json]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [io.github.getcolors.k3s.tools :as tools]))

(defn- temp-dir []
  (let [f (java.io.File/createTempFile "k3s-test-" "")]
    (.delete f)
    (.mkdirs f)
    (str f)))

(deftest stage-names-are-package-specific
  (is (= "k3s-compute" tools/compute-tool))
  (is (not= "tofu-compute" tools/compute-tool)))

(deftest workdir-resolves-next-to-colors
  (is (= "/srv/project/.colors/p/k3s-compute"
         (tools/tool-dir {:workdir ".colors" :profile "p"
                          :green/state-file "/srv/project/colors.yml"}
                         tools/compute-tool))))

(deftest compute-reuses-once-and-adds-the-firewall
  (let [specs (tools/compute-specs {:provider-compute "hcloud"} "/w")]
    (is (= :io.github.getcolors.once.tools.tofu.hcloud/main.tf
           (:template (first specs))))
    (is (= :io.github.getcolors.k3s.tools.tofu.hcloud/firewall.tf
           (:template (second specs))))
    (is (= "/w/firewall.tf" (:target (second specs))))))

(deftest inventory-has-one-k3s-host
  (is (= {"all" {"children" {"k3s" {"hosts" {"demo" {"ansible_host" "203.0.113.7"
                                                        "ansible_user" "root"}}}}}}
         (json/parse-string
          (tools/inventory {:ip "203.0.113.7" :user "root" :host-alias "demo"})))))

(deftest template-data-defaults-gitops-conventions
  (let [data (tools/data-fn {:profile "demo"})]
    (is (= "demo" (:host-alias data)))
    (is (= "main" (:repository-branch data)))
    (is (= "./k8s" (:repository-path data)))
    (is (some? (:ip data)))))

(defn- render-stage [step tool opts]
  (let [dir (temp-dir)
        merged (merge {:profile "p" :workdir dir :green/event :build
                       :repository "https://github.com/getcolors/k3s-helloworld.git"
                       :k3s-version "v1.36.2+k3s1"
                       :flux-version "v2.9.2"}
                      opts)]
    (step merged)
    (tools/tool-dir merged tool)))

(deftest firewall-allows-apps-but-not-the-kubernetes-api
  (let [dir (temp-dir)
        opts {:profile "p" :workdir dir :green/event :build
              :hcloud-name "p" :compute-prevent-destroy true}
        specs (tools/compute-specs opts (tools/tool-dir opts tools/compute-tool))]
    ((requiring-resolve 'green.scaffold/scaffold) opts specs)
    (let [rendered (slurp (str (tools/tool-dir opts tools/compute-tool) "/firewall.tf"))]
      (doseq [port ["22" "80" "443"]]
        (is (str/includes? rendered (str "port       = \"" port "\""))))
      (is (not (str/includes? rendered "port       = \"6443\"")))
      (is (str/includes? rendered "hcloud_server.node1.id")))))

(deftest remote-stage-pins-k3s-and-flux-and-renders-gitops
  (let [dir (render-stage tools/ansible-remote-step tools/ansible-remote-tool {})
        playbook (slurp (str dir "/main.yml"))
        gitops (slurp (str dir "/gitops.yml"))]
    (is (str/includes? playbook "k3s/v1.36.2+k3s1/install.sh"))
    (is (str/includes? playbook "flux2/releases/download/v2.9.2/install.yaml"))
    (is (str/includes? playbook "--secrets-encryption"))
    (is (str/includes? gitops "https://github.com/getcolors/k3s-helloworld.git"))
    (is (str/includes? gitops "path: \"./k8s\""))
    (testing "no cluster credential is rendered"
      (is (not (str/includes? playbook "client-key-data")))
      (is (not (str/includes? gitops "client-key-data"))))))

(deftest local-ssh-config-is-package-owned-and-usable-on-first-connect
  (let [dir (render-stage tools/ansible-local-step tools/ansible-local-tool {})
        rendered (slurp (str dir "/main.yml"))]
    (is (str/includes? rendered "k3s {{ host_alias }} ANSIBLE MANAGED BLOCK"))
    (is (str/includes? rendered "StrictHostKeyChecking accept-new")
        "kubectl must not fail on the first connection to a newly created host")
    (is (str/includes? rendered "ForwardAgent no"))))

(ns io.github.getcolors.k3s.tools-test
  (:require
   [cheshire.core :as json]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [io.github.getcolors.k3s.tools :as tools]
   [io.github.getcolors.k3s.machine :as machine]
   [io.github.getcolors.k3s.validate-test :as vt]))

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

(deftest compute-keeps-legacy-state-guard
  (is (= ["k3s-test/k3s-compute.tfstate"] (:legacy_state_keys (machine/requirements vt/base)))))

(deftest inventory-has-one-k3s-host
  (is (= {"all" {"children" {"k3s" {"hosts" {"demo" {"ansible_host" "203.0.113.7"
                                                        "ansible_user" "root"}}}}}}
         (json/parse-string
          (tools/inventory {:ip "203.0.113.7" :user "root" :host-alias "demo"})))))

(deftest template-data-defaults-gitops-conventions
  (let [data (tools/data-fn {:profile "demo"})]
    (is (= "demo" (:host-alias data)))
    (is (= "no-infra" (:provider-dns data)))
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

(deftest firewall-allows-apps-but-not-kubernetes-api
  (let [ports (mapv :from_port (filter #(= "tcp" (:protocol %)) (get-in (machine/requirements vt/base) [:security :ingress])))]
    (is (= [22 80 443] ports))
    (is (not (some #{6443} ports)))))

(deftest remote-stage-pins-k3s-and-flux-and-renders-gitops
  (let [dir (render-stage tools/ansible-remote-step tools/ansible-remote-tool
                          {:provider-dns "cloudflare"})
        playbook (slurp (str dir "/main.yml"))
        gitops (slurp (str dir "/gitops.yml"))]
    (is (str/includes? playbook "k3s/v1.36.2+k3s1/install.sh"))
    (is (str/includes? playbook "flux2/releases/download/v2.9.2/install.yaml"))
    (is (str/includes? playbook "--secrets-encryption"))
    (is (str/includes? playbook "COLORS_PAR_CLOUDFLARE_API_TOKEN"))
    (is (str/includes? playbook "namespace: cert-manager"))
    (is (str/includes? playbook "namespace: external-dns"))
    (is (not (str/includes? playbook "fixture-cloudflare-token")))
    (is (str/includes? gitops "https://github.com/getcolors/k3s-helloworld.git"))
    (is (str/includes? gitops "path: \"./k8s\""))
    (testing "no cluster credential is rendered"
      (is (not (str/includes? playbook "client-key-data")))
      (is (not (str/includes? gitops "client-key-data"))))))

(deftest local-ssh-config-is-package-owned-and-usable-on-first-connect
  (let [dir (render-stage tools/ansible-local-step tools/ansible-local-tool {})
        rendered (slurp (str dir "/main.yml"))]
    (is (str/includes? rendered "Reference copied into package-owned Ansible plays"))
    (is (str/includes? rendered "StrictHostKeyChecking accept-new")
        "kubectl must not fail on the first connection to a newly created host")
    (is (str/includes? rendered "ForwardAgent no"))))

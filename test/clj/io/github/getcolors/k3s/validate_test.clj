(ns io.github.getcolors.k3s.validate-test
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is]]
   [io.github.getcolors.k3s.validate :as validate]))

(def base
  {:profile "k3s-test"
   :workdir ".colors"
   :provider-compute "hcloud"
   :provider-dns "no-infra"
   :provider-backend "local"
   :compute-prevent-destroy true
   :repository "https://github.com/getcolors/k3s-helloworld.git"
   :k3s-version "v1.36.2+k3s1"
   :flux-version "v2.9.2"
   :hcloud-name "k3s-test"
   :hcloud-image "ubuntu-24.04"
   :hcloud-server-type "cx23"
   :hcloud-location "nbg1"
   :hcloud-ssh-keys "fixture-key"})

(defn- matching [opts re]
  (filter #(re-find re %) (validate/state-errors opts)))

(deftest complete-state-is-renderable
  (is (= [] (validate/state-errors base))))

(deftest required-values-and-placeholders-are-refused
  (is (seq (matching (dissoc base :repository) #":repository")))
  (is (seq (matching (assoc base :hcloud-ssh-keys "REPLACE_ME") #":hcloud-ssh-keys"))))

(deftest v1-is-hcloud-only
  (is (= #{"hcloud"} validate/supported-compute))
  (is (seq (matching (assoc base :provider-compute "digitalocean")
                     #"supports hcloud only")))
  (is (seq (matching (assoc base :provider-compute "azure")
                     #"unsupported :provider-compute"))))

(deftest providers-come-from-onces-registry
  (is (= [:provider-compute :provider-dns :provider-backend] validate/slots))
  (is (seq (matching (assoc base :provider-backend "gcs")
                     #"unsupported :provider-backend")))
  (let [r2 (assoc base :provider-backend "r2"
                  :r2-bucket "b" :r2-endpoint "https://r2.example"
                  :hcloud-token "token")]
    (is (= [] (validate/state-errors r2)))
    (is (= 2 (count (validate/secret-errors r2))))))

(deftest secret-errors-name-colors-variables
  (is (str/includes? (first (validate/secret-errors base))
                     "COLORS_PAR_HCLOUD_TOKEN"))
  (is (= [] (vec (validate/secret-errors (assoc base :hcloud-token "token")))))
  (let [cloudflare (assoc base :provider-dns "cloudflare")]
    (is (str/includes? (str/join "\n" (validate/secret-errors cloudflare))
                       "COLORS_PAR_CLOUDFLARE_API_TOKEN"))
    (is (= [] (vec (validate/secret-errors
                    (assoc cloudflare
                           :hcloud-token "token"
                           :cloudflare-api-token "token")))))))

(deftest versions-are-explicit-release-pins
  (is (seq (matching (assoc base :k3s-version "stable") #":k3s-version")))
  (is (seq (matching (assoc base :flux-version "latest") #":flux-version"))))

(deftest repository-is-public-https-and-conventional
  (is (seq (matching (assoc base :repository "git@github.com:getcolors/app.git")
                     #":repository")))
  (is (= [] (validate/state-errors
             (assoc base :repository-branch "release/v1"
                    :repository-path "./deploy/production"))))
  (is (seq (matching (assoc base :repository-path "/etc")
                     #":repository-path"))))

(deftest prevent-destroy-is-boolean
  (is (seq (matching (assoc base :compute-prevent-destroy "true")
                     #":compute-prevent-destroy"))))

(deftest colors-par-profile-is-refused
  (let [errors (validate/env-errors {"COLORS_PAR_PROFILE" "once-colors"})]
    (is (= 1 (count errors)))
    (is (str/includes? (first errors) "COLORS_PAR_PROFILE")))
  (is (nil? (validate/env-errors {"COLORS_PAR_HCLOUD_TOKEN" "x"}))))

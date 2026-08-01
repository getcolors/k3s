(ns io.github.getcolors.k3s.validate
  "Desired-state validation driven by ONCE's provider registry."
  (:require
   [clojure.string :as str]
   [green.cli :as green-cli]
   [io.github.getcolors.once.validate :as once-validate]))

(def providers once-validate/providers)
(def slots [:provider-compute :provider-backend])
(def supported-compute #{"hcloud"})

(defn- entry [opts slot]
  (get-in providers [slot (get opts slot)]))

(defn tofu-env
  "Flat credential key to the environment variable consumed by OpenTofu."
  [opts slot]
  (:tofu-env (entry opts slot) {}))

(defn placeholder?
  [x]
  (or (nil? x)
      (and (string? x)
           (or (str/blank? x)
               (= "REPLACE_ME" (str/upper-case x))))))

(defn- slot-keys [opts field]
  (mapcat #(get (entry opts %) field []) slots))

(defn- missing-keys [opts ks]
  (keep #(when (placeholder? (get opts %)) %) ks))

(def profile-par (green-cli/par-name :profile))

(defn env-errors
  "Refuse the one environment overlay that could redirect remote state."
  [env]
  (when (not-empty (str (get env profile-par)))
    [(str profile-par " is set. K3s takes its profile from colors.yml only — "
          "run from the project directory rather than overriding it.")]))

(def ^:private k3s-version-re #"^v[0-9]+\.[0-9]+\.[0-9]+\+k3s[0-9]+$")
(def ^:private flux-version-re #"^v[0-9]+\.[0-9]+\.[0-9]+$")
(def ^:private https-repository-re #"^https://[A-Za-z0-9._~-]+(?:/[A-Za-z0-9._~-]+)+(?:\.git)?$")
(def ^:private branch-re #"^[A-Za-z0-9._/-]+$")
(def ^:private path-re #"^\./[A-Za-z0-9._/-]+$")

(defn state-errors
  "All credential-free validation errors."
  [opts]
  (let [compute (:provider-compute opts)]
    (vec
     (concat
      (map #(str % " is required")
           (missing-keys opts
                         (concat [:profile :workdir :repository :k3s-version :flux-version]
                                 (slot-keys opts :required))))
      (for [slot slots
            :let [provider (get opts slot)]
            :when (not (contains? (get providers slot) provider))]
        (str "unsupported " slot " " (pr-str provider)))
      (when (and (contains? (get providers :provider-compute) compute)
                 (not (contains? supported-compute compute)))
        [(str "unsupported :provider-compute " (pr-str compute)
              " — K3s v1 supports hcloud only because it owns and tests that "
              "provider's firewall")])
      (when-not (boolean? (:compute-prevent-destroy opts))
        [":compute-prevent-destroy must be true or false"])
      (when (and (not (placeholder? (:repository opts)))
                 (not (re-matches https-repository-re (str (:repository opts)))))
        [":repository must be a public HTTPS Git URL"])
      (when (and (not (placeholder? (:k3s-version opts)))
                 (not (re-matches k3s-version-re (str (:k3s-version opts)))))
        [":k3s-version must look like v1.36.2+k3s1"])
      (when (and (not (placeholder? (:flux-version opts)))
                 (not (re-matches flux-version-re (str (:flux-version opts)))))
        [":flux-version must look like v2.9.2"])
      (when-not (or (nil? (:repository-branch opts))
                    (re-matches branch-re (str (:repository-branch opts))))
        [":repository-branch contains unsupported characters"])
      (when-not (or (nil? (:repository-path opts))
                    (re-matches path-re (str (:repository-path opts))))
        [":repository-path must be a relative path beginning with ./"])))))

(defn secret-errors
  "Credentials required by the selected compute and backend providers."
  [opts]
  (map #(str "required credential is not set: " (green-cli/par-name %))
       (distinct (missing-keys opts (slot-keys opts :secrets)))))

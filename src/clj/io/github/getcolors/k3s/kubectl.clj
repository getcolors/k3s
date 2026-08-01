(ns io.github.getcolors.k3s.kubectl
  "Secure kubectl access through the managed SSH alias.

  No kubeconfig is copied to generated output. The remote K3s binary supplies
  kubectl and its root-owned kubeconfig, while stdin/stdout/stderr remain
  attached so `apply -f -` and ordinary terminal use work naturally."
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]
   [green.cli :as green-cli]
   [io.github.getcolors.k3s.utils :as utils]
   [io.github.getcolors.k3s.validate :as validate]))

(defn shell-quote
  "Quote one remote POSIX-shell argument without allowing command injection."
  [x]
  (str "'" (str/replace (str x) "'" "'\\''") "'"))

(defn command
  "The local ssh argv for remote `k3s kubectl`."
  [opts args]
  (let [remote (str/join " "
                         (map shell-quote
                              (concat ["sudo" "-n" "k3s" "kubectl"] args)))]
    ["ssh" "--" (utils/host-alias opts) remote]))

(defn inherit-run
  "Run argv with the caller's terminal streams attached."
  [argv]
  (try
    (let [process (-> (ProcessBuilder. ^java.util.List (mapv str argv))
                      .inheritIO
                      .start)]
      {:exit (.waitFor process)})
    (catch Exception e
      {:exit -1 :err (or (.getMessage e) (str (class e)))})))

(defn run
  "Read desired state and invoke kubectl through SSH. Returns an outcome map."
  ([state-file args] (run state-file args inherit-run (System/getenv)))
  ([state-file args runner env]
   (try
     (let [file (io/file state-file)]
       (if-not (.exists file)
         {:green/exit 2 :green/err (str "desired state file not found: " file)}
         (let [opts (-> (green-cli/read-state file (slurp file))
                        (assoc :green/state-file (.getAbsolutePath file))
                        (green-cli/read-pars env))
               errors (validate/env-errors env)]
           (if (seq errors)
             {:green/exit 2 :green/err (str/join "\n" errors)}
             (let [{:keys [exit err]} (runner (command opts args))]
               (cond-> {:green/exit (if (zero? exit) 0 (max 1 exit))}
                 (and (not (zero? exit)) (not-empty err))
                 (assoc :green/err err)))))))
     (catch Throwable t
       {:green/exit 2 :green/err (or (ex-message t) (str (class t)))}))))

(ns io.github.getcolors.k3s.kubectl-test
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is]]
   [io.github.getcolors.k3s.kubectl :as kubectl]))

(defn- temp-dir []
  (let [f (java.io.File/createTempFile "k3s-test-" "")]
    (.delete f)
    (.mkdirs f)
    (str f)))

(deftest command-uses-the-profile-ssh-alias
  (is (= ["ssh" "--" "k3s-hetzner"
          "'sudo' '-n' 'k3s' 'kubectl' 'get' 'nodes'"]
         (kubectl/command {:profile "k3s-hetzner"} ["get" "nodes"]))))

(deftest remote-arguments-are-shell-quoted
  (let [cmd (last (kubectl/command {:profile "p"}
                                    ["get" "pods; touch /tmp/pwned" "it's-safe"]))]
    (is (str/includes? cmd "'pods; touch /tmp/pwned'"))
    (is (str/includes? cmd "'it'\\''s-safe'"))))

(deftest run-reads-profile-and-delegates
  (let [file (str (temp-dir) "/colors.yml")
        seen (atom nil)]
    (spit file "profile: demo\n")
    (is (= 0 (:green/exit
              (kubectl/run file ["get" "nodes"]
                            (fn [argv] (reset! seen argv) {:exit 0}) {}))))
    (is (= "demo" (nth @seen 2)))))

(deftest run-refuses-profile-overlay
  (let [file (str (temp-dir) "/colors.yml")]
    (spit file "profile: demo\n")
    (let [result (kubectl/run file [] (fn [_] {:exit 0})
                              {"COLORS_PAR_PROFILE" "other"})]
      (is (= 2 (:green/exit result)))
      (is (str/includes? (:green/err result) "COLORS_PAR_PROFILE")))))

(deftest a-failed-ssh-is-a-failed-command
  (let [file (str (temp-dir) "/colors.yml")]
    (spit file "profile: demo\n")
    (let [result (kubectl/run file ["get" "nodes"]
                              (fn [_] {:exit 255 :err "unreachable"}) {})]
      (is (= 255 (:green/exit result)))
      (is (= "unreachable" (:green/err result))))))

(ns io.github.getcolors.k3s.utils
  "Launcher compatibility and small pure helpers.")

(def contract
  "Minimum interface version required by the bundled launcher."
  1)

(defn host-alias
  "The managed SSH alias, derived from the project profile."
  [opts]
  (or (not-empty (str (:profile opts))) "k3s"))

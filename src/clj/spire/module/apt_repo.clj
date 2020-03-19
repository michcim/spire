(ns spire.module.apt-repo
  (:require [spire.ssh :as ssh]
            [spire.facts :as facts]
            [spire.utils :as utils]
            [clojure.string :as string]
            ))

;; defaults
(def options-match-choices #{:first :last :all})
(def options-match-default :first)

(def failed-result {:exit 1 :out "" :err "" :result :failed})

(defmulti make-script (fn [command opts] command))

(defmulti preflight (fn [command opts] command))

(defmulti process-result (fn [command opts result] command))

(defn make-filename-from-repo [repo]
  (-> repo
      #_ "deb [arch=amd64] http://dl.google.com/linux/chrome/deb/ stable main"
      #_ "ppa:ondrej/php"
      (string/replace #"\[[^\]]+\]" "")
      (string/replace #"\w+://" "")
      (string/split #"\s+")
      (->> (filter (complement #{"deb" "deb-src"}))
           (string/join "___"))
      (string/replace #"[^0-9A-Za-z]+" "_")
      (str ".list")))

;;
;; (line-in-file :present ...)
;;
(defmethod preflight :present [_ _]
  (facts/check-bins-present #{:sed :grep :awk :apt-key :curl :bash}))

(defmethod make-script :present [_ {:keys [repo filename]}]
  (let [ppa? (string/starts-with? repo "ppa:")
        codename (name (facts/get-fact [:system :codename]))]
    (if ppa?
      ;; ppa repository source
      (let [[_ ppa] (string/split repo #":")
            [ppa-owner ppa-name] (string/split ppa #"/")
            ppa-name (or ppa-name "ppa")
            deb-line (format "deb http://ppa.launchpad.net/%s/%s/ubuntu %s main" ppa-owner ppa-name codename)
            debsrc-line (format "deb-src http://ppa.launchpad.net/%s/%s/ubuntu %s main" ppa-owner ppa-name codename)
            contents (str deb-line "\n" "# " debsrc-line)]
        (utils/make-script
         "apt_repo_present.sh"
         {:FILE (some->
                 (or filename
                     (format "%s-%s-%s-%s" ppa-owner "ubuntu" ppa-name codename))
                 (str ".list")
                 (->> (str "/etc/apt/sources.list.d/"))
                 utils/path-escape)
          :CONTENTS (some->> contents utils/path-escape)
          :PPA_NAME ppa-name
          :PPA_OWNER ppa-owner
          :CODENAME codename
          }))

      ;; non ppa repository source
      (utils/make-script
       "apt_repo_present.sh"
       {:FILE (some->
               (or filename (make-filename-from-repo repo))
               (str ".list")
               (->> (str "/etc/apt/sources.list.d/"))
               utils/path-escape)
        :CONTENTS (some-> repo utils/path-escape)
        :CODENAME codename}))))

(defmethod process-result :present
  [_ _ {:keys [out err exit] :as result}]
  (cond
    (zero? exit)
    (assoc result
           :result :ok
           :out-lines (string/split out #"\n")
           :err-lines (string/split err #"\n")
           )

    (= 255 exit)
    (assoc result
           :result :changed
           :out-lines (string/split out #"\n")
           :err-lines (string/split err #"\n")
           )

    :else
    (assoc result
           :result :failed
           :out-lines (string/split out #"\n")
           :err-lines (string/split err #"\n"))))

;;
;; (line-in-file :absent ...)
;;
(defmethod preflight :absent [_ _]
  (facts/check-bins-present #{:sed :grep :awk :apt-key :curl :bash}))

(defmethod make-script :absent [_ {:keys [repo filename]}]
  (let [ppa? (string/starts-with? repo "ppa:")
        codename (name (facts/get-fact [:system :codename]))]
    (if ppa?
      ;; ppa repository source
      (let [[_ ppa] (string/split repo #":")
            [ppa-owner ppa-name] (string/split ppa #"/")
            ppa-name (or ppa-name "ppa")
            deb-line (format "deb http://ppa.launchpad.net/%s/%s/ubuntu %s main" ppa-owner ppa-name codename)
            ;;debsrc-line (format "deb-src http://ppa.launchpad.net/%s/%s/ubuntu %s main" ppa-owner ppa-name codename)
            ]
        (utils/make-script
         "apt_repo_absent.sh"
         {:REGEX (some-> deb-line
                         (some->> (str "^"))
                         (string/replace #"\[" "\\\\[")
                         (string/replace #"\]" "\\\\]")
                         re-pattern
                         utils/re-pattern-to-sed)
          :LINE (some-> deb-line utils/path-escape)
          :FILES "/etc/apt/sources.list.d/*.list"}))

      ;; non ppa repository source
      (utils/make-script
       "apt_repo_absent.sh"
       {:REGEX (some-> repo
                       (some->> (str "^"))
                       (string/replace #"\[" "\\\\[")
                       (string/replace #"\]" "\\\\]")
                       re-pattern
                       utils/re-pattern-to-sed)
        :LINE (some-> repo utils/path-escape)
        :FILES "/etc/apt/sources.list.d/*.list"}))))

(defmethod process-result :absent
  [_ _ {:keys [out err exit] :as result}]
  (cond
    (zero? exit)
    (assoc result
           :result :ok
           :out-lines (string/split out #"\n")
           :err-lines (string/split err #"\n"))

    (= 255 exit)
    (assoc result
           :result :changed
           :out-lines (string/split out #"\n")
           :err-lines (string/split err #"\n"))

    :else
    (assoc result
           :result :failed
           :out-lines (string/split out #"\n")
           :err-lines (string/split err #"\n"))))


(utils/defmodule apt-repo* [command opts]
  [host-config session {:keys [shell-fn stdin-fn] :as shell-context}]
  (or
   (preflight command opts)
   (->>
    (ssh/ssh-exec session (shell-fn "bash") (stdin-fn (make-script command opts)) "UTF-8" {})
    (process-result command opts))))

(defmacro apt-repo [& args]
  `(utils/wrap-report ~*file* ~&form (apt-repo* ~@args)))

(def documentation
  {
   :module "apt-repo"
   :blurb "Manage extra apt repositories"
   :description
   [
    "This module manages the presence of extra apt repositories."]
   :form "(apt-repo command opts)"
   :args
   [{:arg "command"
     :desc "The overall command to execute. Should be one of `:present` or `:absent`"
     :values
     [[:present "Ensure the specified apt repository is present on the machine"]
      [:absent "Ensure the specified apt repository is absent on the machine"]]}
    {:arg "repository"
     :desc "A string describing the repository to add"}]

   :examples
   [
    {:description
     "Add specified repository into sources list using specified filename."
     :form "
(apt-repo :present {:repo \"deb http://dl.google.com/linux/chrome/deb/ stable main\"
                    :filename \"google-chrome\"})"}
    {:description
     "Install an ubuntu ppa apt source for php packages"
     :form "
(apt-repo :present {:repo \"ppa:ondrej/php\"})"}

    {:description
     "Remove the ubuntu php ppa"
     :form "
(apt-repo :absent {:repo \"ppa:ondrej/php\"})"}

    ]
   })

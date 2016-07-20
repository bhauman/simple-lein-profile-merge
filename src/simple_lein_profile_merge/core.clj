(ns simple-lein-profile-merge.core
  (:require
   [clojure.walk :as walk]
   [clojure.set :as clset]
   [clojure.java.io :as io]))

(def default-profiles [:base :system :user :provided :dev])

(defn windows? []
  (.contains (System/getProperty "os.name") "Windows"))

;; Most of this code has been copied from the excellent Leiningen.
;; It has been copied specifically from
;; https://github.com/technomancy/leiningen/blob/master/leiningen-core/src/leiningen/core/project.clj

;; All copyright issues need to refer back to Leiningen's Copyright

(defn meta*
  "Returns the metadata of an object, or nil if the object cannot hold
  metadata."
  [obj]
  (if (instance? clojure.lang.IObj obj)
    (meta obj)
    nil))

(defn with-meta*
  "Returns an object of the same type and value as obj, with map m as its
  metadata if the object can hold metadata."
  [obj m]
  (if (instance? clojure.lang.IObj obj)
    (with-meta obj m)
    obj))

(defn displace?
  "Returns true if the object is marked as displaceable"
  [obj]
  (-> obj meta* :displace))

(defn replace?
  "Returns true if the object is marked as replaceable"
  [obj]
  (-> obj meta* :replace))

(defn top-displace?
  "Returns true if the object is marked as top-displaceable"
  [obj]
  (-> obj meta* :top-displace))

(defn different-priority?
  "Returns true if either left has a higher priority than right or vice versa."
  [left right]
  (boolean
   (or (some (some-fn nil? displace? replace?) [left right])
       (top-displace? left))))

(defn remove-top-displace [obj]
  (if-not (top-displace? obj)
    obj
    (vary-meta obj dissoc :top-displace)))

(defn pick-prioritized
  "Picks the highest prioritized element of left and right and merge their
  metadata."
  [left right]
  (cond (nil? left) right
        (nil? right) (remove-top-displace left)

        ;; TODO: support :reverse?
        (top-displace? left) right
        (and (displace? left) (top-displace? right)) left

        (and (displace? left)   ;; Pick the rightmost
             (displace? right)) ;; if both are marked as displaceable
        (with-meta* right
          (merge (meta* left) (meta* right)))

        (and (replace? left)    ;; Pick the rightmost
             (replace? right))  ;; if both are marked as replaceable
        (with-meta* right
          (merge (meta* left) (meta* right)))

        (or (displace? left)
            (replace? right))
        (with-meta* right
          (merge (-> left meta* (dissoc :displace))
                 (-> right meta* (dissoc :replace))))

        (or (replace? left)
            (displace? right))
        (with-meta* left
          (merge (-> right meta* (dissoc :displace))
                 (-> left meta* (dissoc :replace))))))

(defn simple-lein-merge
  "Recursively merge values based on the information in their metadata."
  [left right]
  (cond (different-priority? left right)
        (pick-prioritized left right)

        (-> left meta :reduce)
        (-> left meta :reduce
            (reduce left right)
            (with-meta (meta left)))

        (and (map? left) (map? right))
        (merge-with simple-lein-merge left right)

        (and (set? left) (set? right))
        (clset/union right left)

        (and (coll? left) (coll? right))
        (if (or (-> left meta :prepend)
                (-> right meta :prepend))
          (-> (concat right left)
              (with-meta (merge (meta right) (meta left))))
          (concat left right))

        (= (class left) (class right)) right

        :else
        (do (println (str left "and" right "have a type mismatch merging profiles."))
            right)))

(comment
  (every?
   identity
   [(= {:hey [:hei2]}
     (simple-lein-merge {:hey ^:displace [:hei1]} {:hey [:hei2]}))
    (= {:hey [:hei1]}
       (simple-lein-merge {:hey ^:replace [:hei1]} {:hey [:hei2]}))
    (= {:hey [:hei1]}
       (simple-lein-merge {:hey [:hei1]} {:hey ^:displace [:hei2]}))
    (= {:hey [:hei2]}
       (simple-lein-merge {:hey [:hei1]} {:hey ^:replace [:hei2]}))
    (= {:hey [:hei1 :hei2]}
       (simple-lein-merge {:hey [:hei1]} {:hey  [:hei2]}))
    ])  
  )


(defn apply-profiles [project profiles]
  (reduce (fn [project profile]
            (with-meta
              (simple-lein-merge project profile)
              (simple-lein-merge (meta project) (meta profile))))
          project
          (filter map? profiles)))

(defn simple-lein-merge-profiles [left profiles includes]
  (apply-profiles left
                  (keep #(get profiles %) includes)))

(defn read-edn-file [file-name]
  (let [file (io/file file-name)]
    (when-let [body (and (.exists file)
                         (slurp file))]
      (try
        (read-string body)
        (catch Throwable e
          (println
           (str "Failed to read file " (pr-str (str file))
                " : "
                (.getMessage ^Exception e))))))))

(defn read-config-file [file-name]
  (let [data (read-edn-file file-name)]
    (if (map? data) data {})))

(defn read-profile-d-file [file-name]
  (let [data (read-edn-file file-name)]
    (if ((some-fn map? vector?) data)
      data
      {})))

#_(read-profile-d-file "/Users/bhauman/.lein/profiles.d/user.clj")

#_(read-config-file (io/file (System/getProperty "user.home")
                                 ".lein"
                                 "profiles.clj"))

;; gather profile info - without warnings

(defn system-profiles []
  (read-config-file
   (if (windows?)
     (io/file (System/getenv "AllUsersProfile") "Leiningen")
     (io/file "/etc" "leiningen"))))

(defn leiningen-home
  "Return full path to the user's Leiningen home directory."
  []
  (let [lein-home (System/getenv "LEIN_HOME")
        lein-home (or (and lein-home (io/file lein-home))
                      (io/file (System/getProperty "user.home") ".lein"))]
    (.getAbsolutePath (doto ^java.io.File lein-home .mkdirs))))

(defn user-global-profiles []
  (->> (.listFiles (io/file (leiningen-home) "profiles.d"))
       (filter #(-> ^java.io.File % .getName (.endsWith ".clj")))
       (mapv (fn [f]
               [(->> ^java.io.File f .getName (re-find #".+(?=\.clj)") keyword)
                (read-profile-d-file f)]))
       (into {})
       (merge (read-config-file
               (io/file (leiningen-home)
                        "profiles.clj")))))

(defn read-profiles
  "read and merge all system profiles"
  [project]
  (merge (system-profiles)
         (user-global-profiles)
         (:profiles project)
         (read-config-file (io/file (System/getProperty "user.dir")
                                    "profiles.clj"))))


(defn- lookup-profile*
  "Lookup a profile in the given profiles map, warning when the profile doesn't
  exist. Recurse whenever a keyword or vector is found, combining all profiles
  in the vector."
  [profiles profile]
  (cond (keyword? profile)
        (let [result (get profiles profile)]
          (when-not (or result (#{:provided :dev :user :test :base :default
                                  :production :system :repl}
                                profile))
            (binding [*out* *err*]
              (println "Warning: profile" profile "not found.")))
          (lookup-profile* profiles result))

        (vector? profile)
        (reduce simple-lein-merge {}
                (map (partial lookup-profile* profiles) profile))
        
        :else (or profile {})))

(defn pull-together-profiles [project]
  (let [profs (read-profiles project)]
    (into {} (map (juxt identity
                        (partial lookup-profile* profs))
                  (keys profs)))))

;;; read raw project
;; steal some more code from leiningen

(defn- unquote-project
  "Inside defproject forms, unquoting (~) allows for arbitrary evaluation."
  [args]
  (walk/walk (fn [item]
               (cond (and (seq? item) (= `unquote (first item))) (second item)
                     ;; needed if we want fn literals preserved
                     (or (seq? item) (symbol? item)) (list 'quote item)
                     :else (let [result (unquote-project item)]
                             ;; clojure.walk strips metadata
                             (if-let [m (meta item)]
                               (with-meta result m)
                               result))))
             identity
             args))

(defn- argument-list->argument-map
  [args]
  (let [keys (map first (partition 2 args))
        unique-keys (set keys)]
    (if (= (count keys) (count unique-keys))
      (apply hash-map args)
      (let [duplicates (->> (frequencies keys)
                            (remove #(> 2 (val %)))
                            (map first))]
        (throw
         (IllegalArgumentException.
          (format "Duplicate keys: %s"
                  (clojure.string/join ", " duplicates))))))))

(defmacro defproject [project-name version & args]
  `(def ~'simple-lein-project
     ~(unquote-project (argument-list->argument-map args))))

(defn read-raw-project
  ([]
   (read-raw-project "project.clj"))
  ([file]
   (locking read-project
     (binding [*ns* (find-ns 'simple-lein-profile-merge.core)]
       (try (load-file file)
            (catch Exception e
              (throw (Exception. (format "Error loading %s" file) e)))))
     (let [project
           (resolve 'simple-lein-profile-merge.core/simple-lein-project)]
       (when-not project
         (throw (Exception. (format "%s must define project map" file))))
       ;; return it to original state
       (ns-unmap 'simple-lein-profile-merge.core 'validate-project)
       @project))))

#_(read-raw-project "sample.project.clj")

#_(defn read-raw-project
  ([] (read-raw-project
       (io/file
        (System/getProperty "user.dir")
        "project.clj")))
  ([project-file]
   (if (.exists (io/file project-file))
     (->> (str "[" (slurp project-file) "]")
          read-string
          (filter #(= 'defproject (first %)))
          first
          (drop 3)
          (partition 2)
          (map vec)
          (into {}))
     {})))

(defn apply-lein-profiles [raw-project profile-names]
  (let [profiles (pull-together-profiles raw-project)]
    (reduce
     simple-lein-merge
     raw-project
     (map profiles profile-names))))

(defn safe-apply-lein-profiles [project profiles]
  (try
    (apply-lein-profiles project profiles)
    (catch Throwable e
      (println "simple-lein-profile-merge: Attempted to merge leiningen profiles into your project and failed with this error:")
      
      (println (.getMessage e))
      (println "Falling back to project data without profiles merged.")
      project)))

(defn subtract-profiles [included-profiles excluded-profiles]
  (filter #(not ((set excluded-profiles) %)) included-profiles))

(defn profile-top-level-keys
  "Given a project returns all the top level config entries mentioned in the profiles."
  [project]
  (->> (pull-together-profiles project)
       vals
       (filter map?)
       (mapcat keys)
       (filter keyword?)
       set))

(defn safe-profile-top-level-keys [project]
  (try
    (profile-top-level-keys project)
    (catch Throwable e
      (println "simple-lein-profile-merge: Attempted to determine all the top level keys affected by profile merging.")
      
      (println (.getMessage e))
      (println "Falling back to an empty set.")
      #{})))

(comment

  (simple-lein-merge {:hey ^:displace [:hei1]} {:hey [:hei2]})
  
  (def r (leiningen.core.project/read))
  #_(meta (raw-project-with-profile-meta r))
  (config-data-from-project (:without-profiles (meta r)))
  (= (config-data-from-project r)
     (config-data-from-project
      (apply-simple-lein-merge (raw-project-with-profile-meta r))))
  
  

  
  (profile-merging? r config-data-from-project)
  (simple-merge-works? r config-data-from-project)

  (config-data-from-project (apply-simple-lein-merge r))
  (config-data-from-project r)
  
  
  (apply-simple-lein-merge
   (with-meta {}
     {:without-profiles  {:figwheel {:once [2]
                                     :sets #{2}}
                          :profiles {:dev {:figwheel {:once [1]
                                                      :sets #{1}}}}}
      :excluded-profiles []
      :included-profiles [:dev :user]}))
  
  )

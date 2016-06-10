(ns simple-lein-profile-merge.core
  (:require
   [clojure.set :as clset]
   [clojure.java.io :as io]))

(def default-profiles [:base :system :user :provided :dev])

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
          profiles))

(defn simple-lein-merge-profiles [left profiles includes]
  (apply-profiles left (keep #(get profiles %) includes)))

(defn user-global-profiles []
  (let [global-profiles (io/file (System/getProperty "user.home")
                                 ".lein"
                                 "profiles.clj")]
    (try
      (when (.exists global-profiles)
        (or (read-string (slurp global-profiles)) {}))
      (catch Throwable e
        {}))))

(defn read-raw-project
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
  (let [profiles (:profiles raw-project)]
    (reduce
     #(simple-lein-merge-profiles %1 %2 profile-names)
     raw-project
     [profiles
      (user-global-profiles)])))

(defn safe-apply-lein-profiles [project profiles]
  (try
    (apply-lein-profiles project profiles)
    (catch Throwable e
      (println "Attempted to merge leiningen profiles into your project and failed with this error:")
      
      (println (.getMessage e))
      (println "Falling back to project data without profiles merged.")
      project)))

(defn subtract-profiles [included-profiles excluded-profiles]
  (filter #(not ((set excluded-profiles) %)) included-profiles))

(defn profile-top-level-keys
  "Given a project returns all the top level config entries mentioned in the profiles."
  [project]
  (->> project
       :profiles
       vals
       (concat (vals (user-global-profiles)))
       (mapcat keys)))

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

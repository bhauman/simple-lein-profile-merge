# simple-lein-profile-merge

A simple Clojure library to help fetch configuration information from your project.clj without needing to load Leiningen.

## Usage

```
(use 'simple-lein-profile-merge.core)

;; read in project data
(def project-data (read-raw-project))

;; merge in the profiles you want
(apply-lein-profiles project-data [ :user :dev ])

;; or just use the default profiles
(apply-lein-profiles project-data default-profiles)
```

## License

Copyright © 2016 Bruce Hauman

Distributed under the Eclipse Public License either version 1.0 or (at
your option) any later version.

# simple-lein-profile-merge

A simple Clojure library to help fetch configuration information from
your project.clj without needing to load Leiningen.

The motivation here is that programmers may want to store their
configuration data in their profile.clj and take advantage of
[Leiningen](https://github.com/technomancy/leiningen) [profile merging](https://github.com/technomancy/leiningen/blob/master/doc/PROFILES.md).

But in order to extract that information they have to load
`leiningen-core` which includes some big dependencies that will most
likely affect their runtime environment.

By extracting the profile merging code from Leiningen there is now a
much lighter way to get at the information that is in your project.clj
while honoring merging in most situations.

This is a simple small library that is intended to work on data that
isn't part the Leiningen core top-level keys (:target-path etc).

The default base project.clj values are not merged in or initialized.

#### Be warned, not thoroughly tested

I'm not advocating the use of this code, it works for my needs of
getting configuration info for Figwheel out of the project.clj.

But if you want to give it a try and or contribute go for it...

## Usage

```
(use 'simple-lein-profile-merge.core)

;; read in project.clj data
(def project-data (read-raw-project))

;; merge in the profiles you want
(apply-lein-profiles project-data [ :user :dev ])

;; or just use the default profiles
(apply-lein-profiles project-data default-profiles)
```

## License

As this is clearly copying code from Leiningen, please refer to that
project for license information.
[https://github.com/technomancy/leiningen#license](https://github.com/technomancy/leiningen#license)

Distributed under the Eclipse Public License either version 1.0 or (at
your option) any later version.

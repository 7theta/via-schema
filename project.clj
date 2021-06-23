;;   Copyright (c) 7theta. All rights reserved.
;;   The use and distribution terms for this software are covered by the
;;   Eclipse Public License 1.0 (http://www.eclipse.org/legal/epl-v10.html)
;;   which can be found in the LICENSE file at the root of this
;;   distribution.
;;
;;   By using this software in any fashion, you are agreeing to be bound by
;;   the terms of this license.
;;   You must not remove this notice, or any others, from this software.

(defproject com.7theta/via-schema "0.3.1"
  :description "Schema validation for via events and subs."
  :url "https://github.com/7theta/via"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.10.2"]
                 [com.7theta/utilis "1.12.2"]
                 [com.7theta/via "8.1.2"]
                 [borkdude/sci "0.2.3"]
                 [metosin/malli "0.2.1"]]
  :source-paths ["src" "aave/src"])

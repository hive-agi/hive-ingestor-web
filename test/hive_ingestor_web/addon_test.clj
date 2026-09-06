(ns hive-ingestor-web.addon-test
  "What the addon contributes, and what it must not overwrite.

   Registering a source is only half the wiring: a param absent from the SERVED
   schema never reaches a handler, and a contributed param that collides with a
   host one redefines it. Both halves are asserted here. The addon no longer
   resolves the host, so the collision guard is a naming rule the addon can
   check on its own: every contributed name is `url` or carries the `crawl-`
   prefix, which no host serves."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [hive-addon.protocol :as proto]
            [hive-ingestor-web.addon :as addon]
            [hive-ingestor-web.source :as source]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: MIT

(deftest every-contributed-param-declares-a-type-and-a-description
  (doseq [[k v] addon/crawl-params]
    (is (string? (:type v)) k)
    (is (seq (:description v)) k)
    (is (str/includes? (:description v) "web-crawl") k)))

(deftest no-contributed-param-can-redefine-a-host-one
  (testing "the host merges an extension OVER its properties, so every name is namespaced"
    (doseq [k (keys addon/crawl-params)]
      (is (or (= "url" k) (str/starts-with? k "crawl-")) k))))

(deftest the-served-names-are-the-names-the-source-reads
  (testing "a call spelled the way the schema advertises reaches the spec"
    (let [spec (:ok (source/->spec {"url"                "https://example.com/a"
                                    "crawl-depth"        "4"
                                    "crawl-pages"        "12"
                                    "crawl-delay-ms"     "900"
                                    "crawl-robots"       false
                                    "crawl-same-domain"  false
                                    "crawl-user-agent"   "agent/2"}))]
      (is (= 4 (:spec/max-depth spec)))
      (is (= 12 (:spec/max-pages spec)))
      (is (= 900 (:spec/delay-ms spec)))
      (is (false? (:spec/respect-robots? spec)))
      (is (nil? (:spec/link-pattern spec)))
      (is (= "agent/2" (:spec/user-agent spec)))))
  (testing "the addon's own vocabulary still works, for a REPL or a test caller"
    (is (= 4 (:spec/max-depth (:ok (source/->spec {:url "https://example.com/a"
                                                   :max-depth 4})))))))

(deftest every-registered-param-is-a-param-the-schema-advertises
  (is (= (set (keys addon/crawl-params))
         (set (keys (:params addon/source-registration))))
      "`ingest sources` must describe exactly what a caller may actually send"))

(deftest the-addon-contributes-to-the-composite-host
  (let [a (addon/addon-ctor {})]
    (is (= {"memory" addon/crawl-params} (proto/schema-extensions a)))
    (is (empty? (proto/tools a)) "the host already owns the ingest command tree")))

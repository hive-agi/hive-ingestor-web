(ns hive-ingestor-web.tck-test
  "The web-crawl source proven against the hive-spi.ingest conformance kit,
   with a fake frontier so nothing touches the network."
  (:require [clojure.test :refer [deftest is testing]]
            [hive-dsl.result :as r]
            [hive-ingestor-web.frontier :as frontier]
            [hive-ingestor-web.source :as source]
            [hive-spi.ingest.ports :as ports]
            [hive-spi.ingest.tck :as tck]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: MIT

(defn- page [n]
  {:url  (str "https://example.com/p" n)
   :html (str "<html><head><title>Page " n "</title></head><body><h1>Page " n
              "</h1><p>Body of page " n ".</p></body></html>")
   :depth 1})

(defn- fake-frontier
  "A frontier that answers with PAGES for any spec, and reports healthy."
  [pages]
  (reify
    frontier/ICrawlFrontier
    (frontier-id [_] "fake")
    (crawl-pages [_ _spec] (r/ok pages))
    frontier/IFrontierHealth
    (frontier-health [_] {:status :ok :details {:backend "fake"}})))

(def ^:private fixtures
  {:opts       {:url "https://example.com/a"}
   :limit-opts {:url "https://example.com/a" :limit 2}})

(deftest web-crawl-conforms-to-the-ingest-tck
  (let [src    (source/web-crawl-source {:frontier (fake-frontier (mapv page (range 5)))})
        report (tck/conform src fixtures)]
    (is (:ok report) (tck/explain src fixtures))
    (testing "measured at BOTH rungs, with nothing skipped"
      (is (= #{:rung/descriptor :rung/behaviour} (:rungs-measured report)))
      (is (empty? (:skips report)) (pr-str (:skips report))))))

(deftest a-frontier-error-is-a-result-not-a-throw
  (let [src (source/web-crawl-source
             {:frontier (reify frontier/ICrawlFrontier
                          (frontier-id [_] "broken")
                          (crawl-pages [_ _] (r/err :frontier/crawl-failed {})))})]
    (is (r/err? (ports/fetch-documents src {:url "https://example.com/a"})))))

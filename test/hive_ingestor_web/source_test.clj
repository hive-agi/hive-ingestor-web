(ns hive-ingestor-web.source-test
  "The crawl source, tested with the network cut.

   Everything that decides anything is pure, and the one effectful seam is the
   frontier port, so a fake frontier is enough to exercise the whole source."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [hive-dsl.result :as r]
            [hive-ingestor-web.frontier :as frontier]
            [hive-ingestor-web.schema :as schema]
            [hive-ingestor-web.source :as source]
            [hive-spi.ingest.ports :as source-proto]
            [malli.core :as m]))

(def ^:private article-html
  (str "<html><head><title>Displacement</title>"
       "<meta property='og:type' content='article'></head>"
       "<body><h1>Displacement</h1><p>A claim.</p>"
       "<h2>A pattern</h2><p>Its body.</p></body></html>"))

(defrecord FakeFrontier [pages seen]
  frontier/ICrawlFrontier
  (frontier-id [_] "fake")
  (crawl-pages [_ spec]
    (swap! seen conj spec)
    (r/ok pages))
  frontier/IFrontierHealth
  (frontier-health [_] {:status :ok :details {:backend "fake"}}))

(defn- fake-frontier
  ([pages] (fake-frontier pages (atom [])))
  ([pages seen] (->FakeFrontier pages seen)))

;; =============================================================================
;; Collect
;; =============================================================================

(deftest spec-defaults-are-polite
  (let [spec (:ok (source/->spec {:url "https://martinfowler.com/articles/x"}))]
    (is (m/validate schema/CrawlSpec spec))
    (is (= 2 (:spec/max-depth spec)))
    (is (= 25 (:spec/max-pages spec)))
    (is (true? (:spec/respect-robots? spec)))
    (is (pos? (:spec/delay-ms spec)))))

(deftest a-seed-url-is-required
  (doseq [opts [{} {:url ""} {:url "not-a-url"} {:url "ftp://example.com/x"}]]
    (let [result (source/->spec opts)]
      (is (r/err? result) (pr-str opts))
      (is (= :source/invalid-config (:error result))))))

(deftest numbers-may-arrive-as-strings
  (testing "MCP hands params through as text; the spec still validates"
    (let [spec (:ok (source/->spec {:url "https://example.com/a"
                                    :max-depth "3"
                                    :max_pages "7"
                                    :delay-ms "1000"}))]
      (is (m/validate schema/CrawlSpec spec))
      (is (= 3 (:spec/max-depth spec)))
      (is (= 7 (:spec/max-pages spec)) "underscored spelling is read too")
      (is (= 1000 (:spec/delay-ms spec))))))

(deftest a-crawl-stays-on-the-seed-host-by-default
  (let [spec    (:ok (source/->spec {:url "https://martinfowler.com/articles/x"}))
        pattern (re-pattern (:spec/link-pattern spec))]
    (is (re-find pattern "https://martinfowler.com/articles/y"))
    (is (not (re-find pattern "https://example.com/articles/y")))
    (is (not (re-find pattern "https://martinfowler.com.evil.test/y"))
        "the host must end where the pattern says it does")))

(deftest an-explicit-pattern-wins-over-the-host-filter
  (let [spec (:ok (source/->spec {:url          "https://martinfowler.com/articles/x"
                                  :link-pattern "^https://martinfowler.com/bliki/"}))]
    (is (= "^https://martinfowler.com/bliki/" (:spec/link-pattern spec))))
  (testing "same-domain? false and no pattern means no filter at all"
    (is (nil? (:spec/link-pattern
               (:ok (source/->spec {:url "https://martinfowler.com/articles/x"
                                    :same-domain? false})))))))

;; =============================================================================
;; Pipeline
;; =============================================================================

(deftest a-page-becomes-a-document-with-its-structure
  (let [doc (:ok (source/page->document {:url "https://martinfowler.com/articles/x"
                                         :html article-html}
                                        {}))]
    (is (str/includes? (:document/content doc) "# Displacement"))
    (is (str/includes? (:document/content doc) "## A pattern"))
    (is (= :content/article (get-in doc [:document/metadata :content-kind])))))

(deftest html-is-preferred-over-the-crawler-text-dump
  (let [doc (:ok (source/page->document {:url  "https://example.com/a"
                                         :html article-html
                                         :text "Displacement A claim. A pattern Its body."}
                                        {}))]
    (is (str/includes? (:document/content doc) "\n\n")
        "the flattened text has no block boundaries; the HTML does"))
  (testing "text is the fallback when a backend surrendered no HTML"
    (let [doc (:ok (source/page->document {:url "https://example.com/a" :text "plain words"} {}))]
      (is (str/includes? (:document/content doc) "plain words")))))

(deftest the-same-page-is-ingested-once
  (let [pages [{:url "https://example.com/a" :html article-html}
               {:url "https://example.com/a" :html article-html}
               {:url "https://example.com/b" :html article-html}]]
    (is (= 2 (count (source/pages->documents pages {}))))))

(deftest empty-pages-are-dropped-not-fatal
  (let [pages [{:url "https://example.com/a" :html article-html}
               {:url "https://example.com/empty" :html "" :text ""}]
        docs  (source/pages->documents pages {})]
    (is (= 1 (count docs)))
    (is (= "https://example.com/a" (:document/source (first docs))))))

;; =============================================================================
;; The source
;; =============================================================================

(deftest fetch-documents-crawls-then-ingests
  (let [seen   (atom [])
        source (source/web-crawl-source
                {:frontier (fake-frontier [{:url "https://example.com/a" :html article-html}
                                           {:url "https://example.com/b" :html article-html}]
                                          seen)})
        result (source-proto/fetch-documents source {:url "https://example.com/a"
                                                     :max-pages 5})]
    (is (r/ok? result))
    (is (= 2 (count (:ok result))))
    (testing "the opts reached the frontier as a validated spec"
      (is (= 1 (count @seen)))
      (is (m/validate schema/CrawlSpec (first @seen)))
      (is (= 5 (:spec/max-pages (first @seen)))))))

(deftest an-invalid-request-never-reaches-the-network
  (let [seen   (atom [])
        source (source/web-crawl-source {:frontier (fake-frontier [] seen)})
        result (source-proto/fetch-documents source {:url "nonsense"})]
    (is (r/err? result))
    (is (empty? @seen) "the frontier must not be called for a spec that did not validate")))

(deftest constructor-defaults-are-merged-under-per-call-opts
  (let [seen   (atom [])
        source (source/web-crawl-source {:frontier   (fake-frontier [] seen)
                                         :max-depth  4
                                         :user-agent "custom-agent/1.0"})]
    (source-proto/fetch-documents source {:url "https://example.com/a" :max-depth 1})
    (let [spec (first @seen)]
      (is (= 1 (:spec/max-depth spec)) "the call wins")
      (is (= "custom-agent/1.0" (:spec/user-agent spec)) "the default stands where the call is silent"))))

;; =============================================================================
;; The port
;; =============================================================================

(deftest the-source-id-is-the-registry-key
  (is (= "web-crawl" (source-proto/source-id (source/web-crawl-source {:frontier (fake-frontier [])})))))

(deftest health-reports-the-frontier
  (is (= :ok (:status (source-proto/source-health
                       (source/web-crawl-source {:frontier (fake-frontier [])}))))))

(deftest hive-crawl-frontier-is-down-not-broken-when-hive-crawl-is-absent
  (testing "the adapter answers rather than throwing when its library is missing"
    (let [health (frontier/frontier-health (frontier/hive-crawl-frontier))]
      (is (contains? #{:ok :down} (:status health)))))
  (testing "an injected crawl fn is used instead of resolving hive-crawl"
    (let [called (atom nil)
          f      (fn [url & kwargs]
                   (reset! called {:url url :kwargs (apply hash-map kwargs)})
                   (r/ok {:results [{:url url :html article-html :text "t" :depth 0}]}))
          pages  (frontier/crawl-pages (frontier/hive-crawl-frontier f)
                                       (:ok (source/->spec {:url "https://example.com/a"})))]
      (is (r/ok? pages))
      (is (= 1 (count (:ok pages))))
      (is (m/validate schema/CrawledPages (:ok pages)))
      (is (= :crawler4j (:backend (:kwargs @called)))
          "only crawler4j honours robots.txt and surrenders the html"))))

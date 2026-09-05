(ns hive-ingestor-web.http-frontier-test
  "The HTTP frontier, driven by a fake web.

   The crawl is a loop over an injected GET and an injected sleep, so depth
   caps, page caps, robots and link filtering are all testable without a
   socket or a second of wall clock."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [hive-dsl.result :as r]
            [hive-ingestor-web.frontier :as frontier]
            [hive-ingestor-web.http-frontier :as http-frontier]
            [hive-ingestor-web.source :as source]))

(defn- page [title & links]
  (str "<html><head><title>" title "</title></head><body><h1>" title "</h1>"
       (apply str (for [l links] (str "<a href='" l "'>" l "</a>")))
       "<p>Body of " title ".</p></body></html>"))

(def ^:private web
  {"https://example.com/a"          (page "A" "/b" "/c" "https://elsewhere.test/x")
   "https://example.com/b"          (page "B" "/d")
   "https://example.com/c"          (page "C")
   "https://example.com/d"          (page "D")
   "https://elsewhere.test/x"       (page "X")
   "https://example.com/robots.txt" "User-agent: *\nDisallow: /c\n"})

(defn- fake-get
  "A GET over the map WEB, recording every URL it was asked for."
  ([log] (fake-get log web))
  ([log pages]
   (fn [url _opts]
     (swap! log conj url)
     (if-let [body (get pages url)]
       {:status 200 :headers {"content-type" "text/html"} :body body}
       {:status 404 :headers {} :body ""}))))

(defn- crawl [opts & {:keys [pages]}]
  (let [log (atom [])
        f   (http-frontier/http-frontier (fake-get log (or pages web)) (fn [_] nil))]
    {:log    log
     :result (frontier/crawl-pages f (:ok (source/->spec (merge {:url "https://example.com/a"}
                                                                opts))))}))

;; =============================================================================
;; Pure parts
;; =============================================================================

(deftest relative-links-resolve-against-the-page
  (is (= "https://example.com/b" (http-frontier/absolute-url "https://example.com/a" "/b")))
  (is (= "https://example.com/a/b" (http-frontier/absolute-url "https://example.com/a/" "b")))
  (testing "fragments name a place in a page, not another page"
    (is (= "https://example.com/a" (http-frontier/absolute-url "https://example.com/a" "#section"))))
  (testing "non-http schemes are not pages"
    (is (nil? (http-frontier/absolute-url "https://example.com/a" "mailto:x@example.com")))
    (is (nil? (http-frontier/absolute-url "https://example.com/a" "javascript:void(0)")))))

(deftest robots-rules-are-read-per-agent
  (let [rules (http-frontier/parse-robots
               (str "User-agent: BadBot\nDisallow: /\n\n"
                    "User-agent: *\nDisallow: /private\nAllow: /private/ok\nCrawl-delay: 2\n")
               "hive-ingestor-web/0.1")]
    (is (= ["/private"] (:disallow rules)))
    (is (= 2000 (:crawl-delay rules)) "seconds in the file, milliseconds in the spec")
    (testing "the rules of a group we do not belong to do not apply"
      (is (http-frontier/robots-allows? rules "/anything")))
    (is (not (http-frontier/robots-allows? rules "/private/secret")))
    (testing "the longest match wins, Allow breaking the tie"
      (is (http-frontier/robots-allows? rules "/private/ok/page")))))

(deftest an-empty-robots-file-allows-everything
  (is (http-frontier/robots-allows? (http-frontier/parse-robots "" "agent") "/anything")))

;; =============================================================================
;; The crawl
;; =============================================================================

(deftest depth-bounds-how-far-the-crawl-walks
  (let [{:keys [result]} (crawl {:max-depth 0 :respect-robots? false})]
    (is (r/ok? result))
    (is (= ["https://example.com/a"] (mapv :url (:ok result)))))
  (let [{:keys [result]} (crawl {:max-depth 1 :respect-robots? false})]
    (is (= #{"https://example.com/a" "https://example.com/b" "https://example.com/c"}
           (set (mapv :url (:ok result)))))
    (testing "depth is recorded per page"
      (is (= {0 1, 1 2} (frequencies (mapv :depth (:ok result)))))))
  (let [{:keys [result]} (crawl {:max-depth 2 :respect-robots? false})]
    (is (= 4 (count (:ok result))) "d is reached only at depth 2")))

(deftest the-page-cap-stops-the-crawl
  (let [{:keys [result]} (crawl {:max-depth 5 :max-pages 2 :respect-robots? false})]
    (is (= 2 (count (:ok result))))))

(deftest the-crawl-stays-on-the-seed-host
  (let [{:keys [result log]} (crawl {:max-depth 3 :respect-robots? false})]
    (is (not-any? #(str/includes? % "elsewhere.test") (mapv :url (:ok result))))
    (is (not-any? #(str/includes? % "elsewhere.test") @log)
        "an off-host link must not even be fetched")))

(deftest robots-is-honoured-and-can-be-waived
  (let [{:keys [result log]} (crawl {:max-depth 2})]
    (is (some #(str/ends-with? % "/robots.txt") @log))
    (is (not-any? #(= "https://example.com/c" %) (mapv :url (:ok result)))
        "/c is disallowed")
    (is (not-any? #(= "https://example.com/c" %) @log)
        "a disallowed page must not be fetched, not merely dropped"))
  (testing "respect-robots? false skips the fetch of robots.txt entirely"
    (let [{:keys [log]} (crawl {:max-depth 1 :respect-robots? false})]
      (is (not-any? #(str/ends-with? % "/robots.txt") @log)))))

(deftest a-page-is-fetched-once-however-many-links-reach-it
  (let [pages (assoc web
                     "https://example.com/b" (page "B" "/a" "/c")
                     "https://example.com/c" (page "C" "/a" "/b"))
        {:keys [log]} (crawl {:max-depth 3 :respect-robots? false} :pages pages)
        content-urls (remove #(str/ends-with? % "/robots.txt") @log)]
    (is (= (count content-urls) (count (distinct content-urls))))))

(deftest a-dead-link-does-not-fail-the-crawl
  (let [{:keys [result]} (crawl {:max-depth 2 :respect-robots? false}
                                :pages (dissoc web "https://example.com/b"))]
    (is (r/ok? result))
    (is (= #{"https://example.com/a" "https://example.com/c"}
           (set (mapv :url (:ok result)))))))

(deftest the-politeness-delay-is-waited-between-fetches
  (let [waits (atom [])
        log   (atom [])
        f     (http-frontier/http-frontier (fake-get log) (fn [ms] (swap! waits conj ms)))
        spec  (:ok (source/->spec {:url "https://example.com/a" :max-depth 1
                                   :delay-ms 700 :respect-robots? false}))]
    (frontier/crawl-pages f spec)
    (is (seq @waits))
    (is (every? #(= 700 %) @waits) "every gap between fetches is the configured one"))
  (testing "a robots Crawl-delay raises the floor but never lowers it"
    (let [waits (atom [])
          log   (atom [])
          f     (http-frontier/http-frontier (fake-get log) (fn [ms] (swap! waits conj ms)))
          spec  (:ok (source/->spec {:url "https://example.com/a" :max-depth 1 :delay-ms 100}))]
      (frontier/crawl-pages f spec)
      (is (every? #(= 100 %) @waits) "this robots.txt states no Crawl-delay"))))

;; =============================================================================
;; The source over this frontier
;; =============================================================================

(deftest the-http-frontier-is-the-default
  (is (= "http" (frontier/frontier-id (source/frontier-for nil))))
  (is (= "hive-crawl" (frontier/frontier-id (source/frontier-for "hive-crawl")))))

(def ^:private chrome-web
  {"https://example.com/seed"
   (str "<html><head><title>Seed</title></head><body>"
        "<nav><a href='/nav-only'>Everything else</a></nav>"
        "<p>The claim, which cites <a href='/cited'>this</a>.</p>"
        "<footer><a href='/footer-only'>Colophon</a></footer>"
        "</body></html>")
   "https://example.com/cited"       (page "Cited")
   "https://example.com/nav-only"    (page "Nav only")
   "https://example.com/footer-only" (page "Footer only")})

(deftest a-crawl-follows-what-the-article-cites-not-the-site-chrome
  (let [{:keys [result]} (crawl {:url "https://example.com/seed" :max-depth 1 :respect-robots? false}
                                :pages chrome-web)]
    (is (= #{"https://example.com/seed" "https://example.com/cited"}
           (set (mapv :url (:ok result))))
        "nav and footer links are on every page; following them sweeps the site"))

  (testing "crawl-all-links widens it again, for a docs site whose nav IS the index"
    (let [{:keys [result]} (crawl {:url "https://example.com/seed" :max-depth 1
                                   :respect-robots? false :crawl-all-links true}
                                  :pages chrome-web)]
      (is (= 4 (count (:ok result))))))

  (testing "the link filter is off when nothing marks a link, so a stale host still crawls"
    (is (= 1 (count (http-frontier/page-links
                     "<html><body><p><a href='/x'>x</a></p></body></html>"
                     "https://example.com/a" nil true))))))

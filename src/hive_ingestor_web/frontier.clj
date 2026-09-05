(ns hive-ingestor-web.frontier
  "The crawl frontier port, and the hive-crawl adapter behind it.

   Depth, politeness and robots.txt are a crawler's job, not an ingestion
   pipeline's. The pipeline sees only this port; hive-crawl is one adapter and
   a fake is another, which is what keeps the suite offline.

   The adapter resolves hive-crawl at CALL time: hive-crawl is unpublished and
   deliberately absent from deps.edn, so this namespace must load - and the
   suite must pass - with it off the classpath."
  (:require [hive-dsl.result :as r]))

(defprotocol ICrawlFrontier
  (frontier-id [this]
    "Stable id of the frontier implementation.")
  (crawl-pages [this spec]
    "Crawl per SPEC (a hive-ingestor-web.schema/CrawlSpec).
     Returns Result<[CrawledPage]>."))

(defprotocol IFrontierHealth
  (frontier-health [this]
    "Returns {:status :ok|:degraded|:down :details {...}}."))

;; =============================================================================
;; hive-crawl adapter
;; =============================================================================

(defn resolve-crawl-site
  "The hive-crawl entry point, or nil when hive-crawl is not on the classpath."
  []
  (try
    (some-> (requiring-resolve 'hive-crawl.core/crawl-site) var-get)
    (catch Throwable _ nil)))

(defn spec->kwargs
  "CrawlSpec as the trailing keyword arguments hive-crawl.core/crawl-site takes.

   The crawler4j backend is not a preference: it is the only one of the three
   that honours robots.txt and a politeness delay, and the only one that
   surrenders the page HTML rather than a flattened dump."
  [spec]
  [:backend         :crawler4j
   :max-depth       (:spec/max-depth spec)
   :max-pages       (:spec/max-pages spec)
   :delay-ms        (:spec/delay-ms spec)
   :respect-robots? (:spec/respect-robots? spec)
   :user-agent      (:spec/user-agent spec)
   :num-crawlers    (:spec/num-crawlers spec)
   :link-pattern    (:spec/link-pattern spec)])

(defn crawl-output->pages
  "The crawl-site payload as a vector of CrawledPage."
  [output]
  (into []
        (keep (fn [{:keys [url text html depth]}]
                (when (seq (str url))
                  (cond-> {:url (str url)}
                    html  (assoc :html html)
                    text  (assoc :text text)
                    depth (assoc :depth depth)))))
        (:results output)))

(defrecord HiveCrawlFrontier [crawl-fn]
  ICrawlFrontier
  (frontier-id [_] "hive-crawl")

  (crawl-pages [_ spec]
    (if-let [f (or crawl-fn (resolve-crawl-site))]
      (let [result (try
                     (apply f (:spec/url spec) (spec->kwargs spec))
                     (catch Throwable t
                       (r/err :frontier/crawl-failed
                              {:url     (:spec/url spec)
                               :message (ex-message t)})))]
        (if (r/ok? result)
          (r/ok (crawl-output->pages (:ok result)))
          result))
      (r/err :frontier/unavailable
             {:reason "hive-crawl is not on the classpath"
              :hint   "add io.github.hive-agi/hive-crawl to local.deps.edn"})))

  IFrontierHealth
  (frontier-health [_]
    (if (or crawl-fn (resolve-crawl-site))
      {:status :ok :details {:backend "crawler4j"}}
      {:status :down :details {:reason "hive-crawl is not on the classpath"}})))

(defn hive-crawl-frontier
  "A frontier backed by hive-crawl, resolved lazily.

   With CRAWL-FN supplied the resolution is skipped, which is the seam a test
   or an alternative crawler uses."
  ([] (->HiveCrawlFrontier nil))
  ([crawl-fn] (->HiveCrawlFrontier crawl-fn)))

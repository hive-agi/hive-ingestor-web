(ns hive-ingestor-web.addon
  "The IAddon that teaches hive-ingestor to crawl.

   The addon owns no tool of its own: hive-ingestor already exposes
   `ingest source :source <id>`, which looks a factory up in its source
   registry. So the whole contribution is one registration, and one retraction
   at shutdown - the seam the host built for exactly this."
  (:require [hive-addon.protocol :as proto]
            [hive-dsl.result :as r]
            [hive-ingestor-web.source :as source]
            [hive-ingestor.source.registry :as source-registry]
            [taoensso.timbre :as log]
            [hive-ingestor-web.frontier :as frontier]))

(def owner
  "Registry owner key. Registrations are owner-scoped, so nothing else can
   replace or retract this source."
  "hive.ingestor-web")

(def source-id "web-crawl")

(def source-registration
  {:factory     (fn [opts] (source/web-crawl-source opts))
   :description (str "Recursively crawl a site and ingest each page. Depth, page cap, "
                     "politeness delay and robots.txt are honoured by the crawler; the "
                     "page HTML is parsed by the host extractor, so headings, lists and "
                     "code blocks survive into the chunks.")
   :params      {"url"             "seed URL (required)"
                 "max-depth"       "link depth to follow (default 2, max 10)"
                 "max-pages"       "page cap for the run (default 25)"
                 "delay-ms"        "politeness delay between fetches (default 300)"
                 "respect-robots?" "honour robots.txt (default true)"
                 "same-domain?"    "restrict to the seed's host (default true)"
                 "link-pattern"    "explicit regex for links to follow; overrides same-domain?"
                 "num-crawlers"    "crawler threads (default 2)"
                 "user-agent"      "user agent string"}})

(defonce ^:private addon-state (atom nil))

(defrecord IngestorWebAddon [id config]
  proto/IAddon

  (addon-id [_] id)

  (addon-type [_] :native)

  (capabilities [_] #{})

  (initialize! [_ cfg]
    (if @addon-state
      {:success? true :already-initialized? true}
      (let [result (source-registry/register-source! owner source-id source-registration)]
        (if (r/ok? result)
          (do (reset! addon-state {:initialized-at (java.time.Instant/now)
                                   :config         (merge config cfg)})
              (log/info "hive-ingestor-web registered its source"
                        {:addon id :source source-id})
              {:success? true :errors [] :metadata {:source source-id}})
          (do (log/error "hive-ingestor-web could not register its source"
                         {:addon id :error (:message result)})
              {:success? false :errors [(str (:message result))]})))))

  (shutdown! [_]
    (source-registry/retract-all! owner)
    (reset! addon-state nil)
    nil)

  (tools [_] [])

  (schema-extensions [_] {})

  (health [_]
    (let [crawler (frontier/frontier-health (frontier/hive-crawl-frontier))]
      (if @addon-state
        {:status  (if (= :ok (:status crawler)) :ok :degraded)
         :details {:source source-id :crawler crawler}}
        {:status :down :details {:source source-id :crawler crawler}})))

  (excluded-tools [_] #{}))

(defn addon-ctor
  "Pure constructor (config -> IAddon). The mounter drives register!/initialize!."
  [config]
  (->IngestorWebAddon "hive.ingestor-web" (or config {})))

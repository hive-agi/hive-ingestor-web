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

(def composite-host
  "Root supertool the ingest command tree composes into. The schema extension
   must reach the SAME host, or the params are advertised nowhere."
  "memory")

(def crawl-params
  "Params contributed to the served schema.

   A parameter absent from the served schema is never delivered to a handler,
   however the caller spells it - so registering the source is not enough, and
   `ingest source :source web-crawl :url ...` would arrive with no url at all.

   Named `crawl-*` rather than `max-depth`/`delay-ms`: the host merges an
   extension OVER its own properties, and `max-depth` is already the ingestor's
   corpus-classify depth. Contributing that name would silently redefine it."
  {"url"                {:type "string"
                        :description "[source web-crawl] Seed URL to crawl (required)"}
   "crawl-depth"        {:type "integer"
                        :description "[source web-crawl] Link depth to follow from the seed (default 2, max 10)"}
   "crawl-pages"        {:type "integer"
                        :description "[source web-crawl] Page cap for the run (default 25)"}
   "crawl-delay-ms"     {:type "integer"
                        :description "[source web-crawl] Politeness delay between fetches in ms (default 300). A robots.txt Crawl-delay raises this, never lowers it"}
   "crawl-robots"       {:type "boolean"
                        :description "[source web-crawl] Honour robots.txt (default true)"}
   "crawl-same-domain"  {:type "boolean"
                        :description "[source web-crawl] Follow only links on the seed's host (default true)"}
   "crawl-all-links"    {:type "boolean"
                        :description "[source web-crawl] Follow links from the whole page, nav and footer included, instead of only the ones inside the content (default false). True is what a documentation site wants, where the nav IS the index"}
   "crawl-link-pattern" {:type "string"
                        :description "[source web-crawl] Regex a link must match to be followed; overrides crawl-same-domain"}
   "crawl-user-agent"   {:type "string"
                        :description "[source web-crawl] User-Agent sent with every fetch, and matched against robots.txt groups"}
   "crawl-threads"      {:type "integer"
                        :description "[source web-crawl] Crawler threads (hive-crawl frontier only, default 2)"}
   "crawl-frontier"     {:type "string"
                        :description "[source web-crawl] Which frontier walks the links: 'http' (default) or 'hive-crawl'"}})

(def source-registration
  {:factory     (fn [opts] (source/web-crawl-source opts))
   :description (str "Recursively crawl a site and ingest each page. Depth, page cap, "
                     "politeness delay and robots.txt are honoured by the frontier; the "
                     "page HTML is parsed by the host extractor, so headings, lists and "
                     "code blocks survive into the chunks.")
   :params      (into {} (map (fn [[k v]] [k (:description v)])) crawl-params)})

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

  (schema-extensions [_] {composite-host crawl-params})

  (health [_]
    (let [crawler (frontier/frontier-health (frontier/hive-crawl-frontier))]
      (if @addon-state
        {:status  :ok
         :details {:source source-id :hive-crawl crawler}}
        {:status :down :details {:source source-id :hive-crawl crawler}})))

  (excluded-tools [_] #{}))

(defn addon-ctor
  "Pure constructor (config -> IAddon). The mounter drives register!/initialize!."
  [config]
  (->IngestorWebAddon "hive.ingestor-web" (or config {})))

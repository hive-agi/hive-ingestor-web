(ns hive-ingestor-web.source
  "The `web-crawl` ISource: a recursive crawl that lands as ingestor Documents.

   Stratified as Collect (params -> CrawlSpec), Pipeline (pages -> Documents)
   and Boundary (the frontier call). Only `fetch-documents` reaches the network,
   so everything that decides anything is testable without one."
  (:require [clojure.string :as str]
            [hive-dsl.result :as r]
            [hive-ingestor-web.frontier :as frontier]
            [hive-ingestor-web.schema :as schema]
            [hive-ingestor.source.protocol :refer [ISource ISourceHealth]]
            [hive-ingestor.source.web-docs :as web-docs]
            [malli.core :as m]
            [hive-ingestor-web.http-frontier :as http-frontier])
  (:import [java.net URI]
           [java.util.regex Pattern]))

(def default-spec
  "Defaults for a crawl nobody parameterised.

   Deliberately timid: two levels, 25 pages, 300 ms between fetches, robots
   honoured. A default that is polite is one nobody has to remember to be."
  {:spec/max-depth       2
   :spec/max-pages       25
   :spec/delay-ms        300
   :spec/respect-robots? true
   :spec/user-agent      "hive-ingestor-web/0.1"
   :spec/num-crawlers    2
   :spec/link-pattern    nil})

;; =============================================================================
;; Collect - params to CrawlSpec
;; =============================================================================

(defn param
  "Read the first of KS present in OPTS, under dashed, underscored and string
   spellings of each.

   Two reasons for the spread. The MCP surface delivers whichever spelling the
   caller typed. And the served schema names these params `crawl-*` - `max-depth`
   is already the ingestor's own corpus-classify depth, and contributing that name
   would REDEFINE it - so a call arrives as :crawl-depth while a REPL caller and
   this addon's own defaults speak :max-depth.

   Presence is decided by `contains?`, never by truthiness: `:same-domain? false`
   is an answer, and reading it as absence would re-enable the filter it turns off."
  [opts & ks]
  (let [candidates (mapcat (fn [k]
                             (let [n (name k)]
                               [k (keyword (str/replace n "-" "_")) n (str/replace n "-" "_")]))
                           ks)]
    (when-let [hit (first (filter #(contains? opts %) candidates))]
      (get opts hit))))

(defn as-int
  "V as an integer, or DEFAULT. Strings count: MCP numbers arrive as text."
  [v default]
  (cond
    (integer? v)                                    v
    (and (string? v) (re-matches #"\s*-?\d+\s*" v)) (parse-long (str/trim v))
    :else                                           default))

(defn as-bool
  "V as a boolean, or DEFAULT when V is absent."
  [v default]
  (cond
    (boolean? v) v
    (nil? v)     default
    (string? v)  (contains? #{"true" "yes" "1"} (str/lower-case (str/trim v)))
    :else        (boolean v)))

(defn url-host
  "Host of URL, or nil when it does not parse."
  [url]
  (try
    (some-> (URI. (str url)) .getHost not-empty)
    (catch Exception _ nil)))

(defn same-host-pattern
  "A link filter admitting only URLs on the same host as URL.

   Returned as a regex STRING because that is what crawler4j's shouldVisit
   filter takes. Without it a crawl of one article walks the open web."
  [url]
  (when-let [host (url-host url)]
    (str "^https?://" (Pattern/quote host) "([/?#]|$)")))

(defn ->spec
  "Promote tool params into a validated CrawlSpec. Returns Result<CrawlSpec>.

   :same-domain? defaults to true and only applies when no link pattern was
   given: an explicit pattern is the caller saying they mean it."
  [opts]
  (let [url          (some-> (or (param opts :url :seed-url)) str str/trim not-empty)
        same-domain? (as-bool (param opts :crawl-same-domain :same-domain?) true)
        explicit     (some-> (param opts :crawl-link-pattern :link-pattern) str str/trim not-empty)
        spec         {:spec/url             (or url "")
                      :spec/max-depth       (as-int (param opts :crawl-depth :max-depth)
                                                    (:spec/max-depth default-spec))
                      :spec/max-pages       (as-int (param opts :crawl-pages :max-pages)
                                                    (:spec/max-pages default-spec))
                      :spec/delay-ms        (as-int (param opts :crawl-delay-ms :delay-ms)
                                                    (:spec/delay-ms default-spec))
                      :spec/respect-robots? (as-bool (param opts :crawl-robots :respect-robots?)
                                                     (:spec/respect-robots? default-spec))
                      :spec/user-agent      (or (some-> (param opts :crawl-user-agent :user-agent)
                                                        str not-empty)
                                                (:spec/user-agent default-spec))
                      :spec/num-crawlers    (as-int (param opts :crawl-threads :num-crawlers)
                                                    (:spec/num-crawlers default-spec))
                      :spec/link-pattern    (or explicit
                                                (when same-domain? (same-host-pattern url)))}]
    (if-let [explanation (m/explain schema/CrawlSpec spec)]
      (r/err :source/invalid-config
             {:reason  "invalid crawl spec"
              :errors  (mapv (fn [{:keys [in message value]}]
                               {:in in :message message :value value})
                             (:errors explanation))
              :spec    spec})
      (r/ok spec))))

;; =============================================================================
;; Pipeline - pages to Documents
;; =============================================================================

(defn page->document
  "One crawled page as an ingestor Document. Returns Result<Document>.

   HTML is preferred over the crawler's text dump: the host extractor turns
   markup into blocks, headings and fenced code, and none of that is
   recoverable from an already-flattened page."
  [{:keys [url html text]} opts]
  (if (str/blank? html)
    (web-docs/body->document (or text "") {:url url :content-type "text/plain"} opts)
    (web-docs/body->document html {:url url :content-type "text/html"} opts)))

(defn crawled?
  "True when PAGE carries anything worth ingesting."
  [{:keys [html text]}]
  (not (and (str/blank? html) (str/blank? text))))

(defn distinct-by-url
  "PAGES with one entry per URL, first occurrence winning.

   A crawler reaches the same page by several paths; ingesting it twice puts
   two copies of the same chunks in front of the embedder."
  [pages]
  (into [] (comp (filter (comp seq str :url))
                 (dedupe))
        (vals (reduce (fn [acc page]
                        (if (contains? acc (:url page))
                          acc
                          (assoc acc (:url page) page)))
                      (array-map)
                      pages))))

(defn pages->documents
  "PAGES as Documents, one per distinct URL.

   Pages that carried nothing, and pages the host could not parse, are dropped
   rather than failing the crawl: one dead page in fifty is not a failed run."
  [pages opts]
  (into []
        (comp (filter crawled?)
              (map #(page->document % opts))
              (filter r/ok?)
              (map :ok))
        (distinct-by-url pages)))

;; =============================================================================
;; Boundary - the source
;; =============================================================================

(defrecord WebCrawlSource [frontier defaults]
  ISource
  (source-id [_] "web-crawl")

  (fetch-documents [_ opts]
    (r/let-ok [spec  (->spec (merge defaults opts))
               pages (frontier/crawl-pages frontier spec)]
      (r/ok (pages->documents pages opts))))

  ISourceHealth
  (source-health [_]
    (frontier/frontier-health frontier)))

(defn frontier-for
  "The frontier named by CHOICE.

   Defaults to the plain-HTTP frontier: hive-crawl's crawler4j engine resolves
   Tika 1.16 while the ingestor resolves Tika 3, so in one JVM crawler4j's
   parser dies on a class Tika 2 deleted and every page comes back empty. The
   port is the point - naming `hive-crawl` still selects it where it is viable."
  [choice]
  (cond
    (satisfies? frontier/ICrawlFrontier choice) choice
    (contains? #{"hive-crawl" :hive-crawl} choice) (frontier/hive-crawl-frontier)
    :else (http-frontier/http-frontier)))

(defn web-crawl-source
  "Create the `web-crawl` source.

   OPTS may carry :frontier - an ICrawlFrontier to inject, or the name of one.
   Every other key is remembered as a default and merged under the per-call
   opts."
  ([] (web-crawl-source {}))
  ([opts]
   (->WebCrawlSource (frontier-for (param opts :frontier :crawl-frontier))
                     (dissoc opts :frontier))))

(ns hive-ingestor-web.http-frontier
  "A breadth-first frontier over plain HTTP, with no crawler library behind it.

   It exists because crawler4j (hive-crawl's engine) resolves Tika 1.16 and the
   ingestor resolves Tika 3: one coordinate, one winner, and in a shared JVM
   crawler4j's parser dies on a class Tika 2 deleted. Measured, not assumed -
   see the frontier-clash note in hive memory.

   Everything that decides anything here is pure: robots parsing, link
   selection, URL normalisation. Only `fetch-page` touches the network."
  (:require [clojure.string :as str]
            [hive-dsl.result :as r]
            [hive-ingestor-web.frontier :as frontier]
            [hive-html.page :as website-parser])
  (:import [java.net URI]))

;; =============================================================================
;; URLs
;; =============================================================================

(defn strip-fragment
  "URL without its #fragment. Two links to the same page differing only in
   fragment are one page, and crawling both doubles the corpus."
  [url]
  (some-> url str (str/replace #"#.*$" "") not-empty))

(defn absolute-url
  "HREF resolved against BASE, or nil when it does not resolve to http(s)."
  [base href]
  (try
    (let [resolved (.resolve (URI. (str base)) (str/trim (str href)))
          s        (str resolved)]
      (when (re-find #"(?i)^https?://" s)
        (strip-fragment s)))
    (catch Exception _ nil)))

(defn url-path
  "Path (plus query) of URL, defaulting to \"/\"."
  [url]
  (try
    (let [uri  (URI. (str url))
          path (or (not-empty (.getRawPath uri)) "/")]
      (str path (when-let [q (.getRawQuery uri)] (str "?" q))))
    (catch Exception _ "/")))

(defn robots-url
  "The robots.txt URL for URL's origin, or nil."
  [url]
  (try
    (let [uri (URI. (str url))]
      (when (and (.getScheme uri) (.getHost uri))
        (str (.getScheme uri) "://" (.getAuthority uri) "/robots.txt")))
    (catch Exception _ nil)))

;; =============================================================================
;; robots.txt
;; =============================================================================

(defn parse-robots
  "robots.txt TEXT as {:disallow [path] :allow [path] :crawl-delay ms}, for the
   groups matching AGENT plus the wildcard group.

   A deliberately small reading of the standard: prefix rules and Crawl-delay.
   Anything it cannot parse it ignores, which errs toward fetching - so the
   politeness delay, which is unconditional, is the real floor."
  [text agent]
  (let [agent-lc (str/lower-case (str agent))]
    (loop [lines   (str/split-lines (str text))
           active? false
           acc     {:disallow [] :allow [] :crawl-delay nil}]
      (if-let [line (first lines)]
        (let [clean (-> line (str/replace #"#.*$" "") str/trim)
              [k v] (if-let [i (str/index-of clean ":")]
                      [(str/lower-case (str/trim (subs clean 0 i)))
                       (str/trim (subs clean (inc i)))]
                      [nil nil])]
          (cond
            (nil? k)
            (recur (rest lines) active? acc)

            (= "user-agent" k)
            (recur (rest lines)
                   (or (= "*" v)
                       (str/includes? agent-lc (str/lower-case v))
                       (str/includes? (str/lower-case v) agent-lc))
                   acc)

            (not active?)
            (recur (rest lines) active? acc)

            (= "disallow" k)
            (recur (rest lines) active?
                   (cond-> acc (seq v) (update :disallow conj v)))

            (= "allow" k)
            (recur (rest lines) active?
                   (cond-> acc (seq v) (update :allow conj v)))

            (= "crawl-delay" k)
            (recur (rest lines) active?
                   (assoc acc :crawl-delay (some-> (re-find #"\d+(\.\d+)?" v)
                                                   first
                                                   parse-double
                                                   (* 1000)
                                                   long)))

            :else
            (recur (rest lines) active? acc)))
        acc))))

(defn robots-allows?
  "True when RULES permit PATH. The longest matching rule wins, Allow breaking
   a tie, which is how every major crawler reads it."
  [rules path]
  (let [match  (fn [ps] (->> ps
                             (filter #(str/starts-with? (str path) %))
                             (map count)
                             (reduce max 0)))
        deny   (match (:disallow rules))
        permit (match (:allow rules))]
    (or (zero? deny) (>= permit deny))))

;; =============================================================================
;; Link selection
;; =============================================================================

(defn page-links
  "Absolute, de-fragmented, pattern-matching links out of HTML at URL.

   CONTENT-ONLY? keeps only the links the extractor also kept as content. A
   site's nav is on every page, so following it turns `crawl this article and
   what it cites` into a sweep of the whole site - measured: a depth-1 crawl of
   one Fowler article reached /boardgames and /videos through the header.

   Links from an extractor that predates `:in-content?` carry no such key and
   are kept, so a stale host degrades to the old reach rather than to nothing."
  ([html url link-pattern] (page-links html url link-pattern false))
  ([html url link-pattern content-only?]
   (let [pattern (some-> link-pattern re-pattern)
         page    (website-parser/parse-page html {:url url})
         links   (:links (if (r/ok? page) (:ok page) page))]
     (into []
           (comp (filter (fn [link] (or (not content-only?)
                                        (get link :in-content? true))))
                 (keep #(absolute-url url (:href %)))
                 (filter #(or (nil? pattern) (re-find pattern %)))
                 (distinct))
           links))))

;; =============================================================================
;; Boundary
;; =============================================================================

(defn- resolve-http-get []
  (some-> (requiring-resolve 'clj-http.client/get) var-get))

(defn fetch-page
  "GET URL as a string. Returns Result<{:url :html :content-type}>."
  [http-get url user-agent]
  (try
    (let [resp (http-get url {:as                 :string
                              :socket-timeout     15000
                              :connection-timeout 15000
                              :throw-exceptions   false
                              :headers            {"User-Agent" user-agent}})
          ct   (or (get-in resp [:headers "content-type"])
                   (get-in resp [:headers "Content-Type"]))]
      (if (<= 200 (:status resp) 299)
        (r/ok {:url url :html (:body resp) :content-type ct})
        (r/err :frontier/fetch-failed {:url url :status (:status resp)})))
    (catch Throwable t
      (r/err :frontier/fetch-failed {:url url :message (ex-message t)}))))

(defn textual?
  "True when a Content-Type is worth parsing. A PDF reached by a link is a job
   for the ingestor's file path, not for an HTML crawl."
  [content-type]
  (let [ct (str/lower-case (str content-type))]
    (or (str/blank? ct)
        (str/includes? ct "text/html")
        (str/includes? ct "application/xhtml")
        (str/includes? ct "text/plain"))))

(defrecord HttpFrontier [http-get sleep-fn]
  frontier/ICrawlFrontier
  (frontier-id [_] "http")

  (crawl-pages [_ spec]
    (if-let [get-fn (or http-get (resolve-http-get))]
      (let [{:spec/keys [url max-depth max-pages delay-ms user-agent
                         link-pattern respect-robots? content-links?]} spec
            sleep  (or sleep-fn #(when (pos? %) (Thread/sleep ^long %)))
            rules  (when respect-robots?
                     (let [res (some->> (robots-url url) (#(fetch-page get-fn % user-agent)))]
                       (when (r/ok? res)
                         (parse-robots (:html (:ok res)) user-agent))))
            pause  (max (long delay-ms) (long (or (:crawl-delay rules) 0)))]
        (loop [queue   [[(strip-fragment url) 0]]
               seen    #{}
               pages   []]
          (let [[[current depth] & rest-queue] queue]
            (cond
              (or (nil? current) (>= (count pages) max-pages))
              (r/ok pages)

              (or (seen current)
                  (and rules (not (robots-allows? rules (url-path current)))))
              (recur (vec rest-queue) (conj seen current) pages)

              :else
              (let [_        (when (seq pages) (sleep pause))
                    result   (fetch-page get-fn current user-agent)
                    ok       (when (r/ok? result) (:ok result))
                    keep?    (and ok (textual? (:content-type ok)) (seq (str (:html ok))))
                    page     (when keep? {:url current :html (:html ok) :depth depth})
                    children (when (and keep? (< depth max-depth))
                               (->> (page-links (:html ok) current link-pattern
                                                (boolean content-links?))
                                    (remove seen)
                                    (mapv (fn [child] [child (inc depth)]))))]
                (recur (into (vec rest-queue) children)
                       (conj seen current)
                       (cond-> pages page (conj page))))))))
      (r/err :frontier/unavailable {:reason "clj-http is not on the classpath"})))

  frontier/IFrontierHealth
  (frontier-health [_]
    (if (or http-get (resolve-http-get))
      {:status :ok :details {:backend "clj-http"}}
      {:status :down :details {:reason "clj-http is not on the classpath"}})))

(defn http-frontier
  "A frontier that fetches with clj-http and follows links itself.

   HTTP-GET and SLEEP-FN are the injection seam: a test supplies both and the
   crawl runs with no network and no wall-clock cost."
  ([] (->HttpFrontier nil nil))
  ([http-get] (->HttpFrontier http-get nil))
  ([http-get sleep-fn] (->HttpFrontier http-get sleep-fn)))

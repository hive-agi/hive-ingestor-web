(ns hive-ingestor-web.schema
  "Malli value objects for a recursive web ingest.

   The schemas are the single source: they carry the `m/=>` contracts on the
   pure promoters and generate those promoters' tests."
  (:require [clojure.string :as str]))

(def NonBlankString
  [:and :string [:fn {:error/message "must not be blank"} (complement str/blank?)]])

(def HttpUrl
  [:and :string [:re #"(?i)^https?://[^\s]+$"]])

(def CrawlSpec
  "A validated crawl request.

   Closed: an unknown key here is a caller mistake, not an extension point -
   a misspelled :max_pages must fail loudly rather than silently crawl 25."
  [:map {:closed true}
   [:spec/url             HttpUrl]
   [:spec/max-depth       [:int {:min 0 :max 10}]]
   [:spec/max-pages       [:int {:min 1 :max 2000}]]
   [:spec/delay-ms        [:int {:min 0 :max 60000}]]
   [:spec/respect-robots? :boolean]
   [:spec/user-agent      NonBlankString]
   [:spec/num-crawlers    [:int {:min 1 :max 16}]]
   [:spec/link-pattern    [:maybe NonBlankString]]])

(def CrawledPage
  "One page as a frontier hands it back.

   Open: a frontier may carry provenance of its own, and closing the map would
   discard it rather than surface it."
  [:map
   [:url   HttpUrl]
   [:html  {:optional true} [:maybe :string]]
   [:text  {:optional true} [:maybe :string]]
   [:depth {:optional true} [:maybe [:int {:min 0}]]]])

(def CrawledPages
  [:sequential CrawledPage])

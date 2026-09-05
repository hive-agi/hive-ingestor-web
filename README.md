# hive-ingestor-web

Recursive web ingestion for `hive-ingestor`: reach an article and the pages it
links, and land each one as a Document with its structure intact.

The addon contributes exactly one thing — an `ISource` registered under
`web-crawl` in the host's source registry. The host already exposes the seam:

```
ingest source :source web-crawl :url https://martinfowler.com/articles/patterns-legacy-displacement/
ingest source :source web-crawl :url https://example.com/docs :max-depth 3 :max-pages 60
ingest sources                       # what is registered, and with which params
```

| param | default | meaning |
|---|---|---|
| `url` | — | seed URL, required |
| `max-depth` | 2 | link depth to follow |
| `max-pages` | 25 | page cap for the run |
| `delay-ms` | 300 | politeness delay between fetches |
| `respect-robots?` | true | honour robots.txt |
| `same-domain?` | true | restrict to the seed's host |
| `link-pattern` | — | explicit regex; overrides `same-domain?` |
| `num-crawlers` | 2 | crawler threads |
| `user-agent` | `hive-ingestor-web/0.1` | UA string |

## Layers

```
schema     malli value objects: CrawlSpec (closed), CrawledPage (open)
frontier   ICrawlFrontier port + the hive-crawl adapter
source     Collect (params -> spec) | Pipeline (pages -> Documents) | Boundary
addon      IAddon: register the source on init, retract it on shutdown
```

Crawling is a crawler's job, so depth, politeness and robots.txt live behind
`ICrawlFrontier` and `hive-crawl` implements it, on its crawler4j backend —
the only one of the three that honours robots.txt and surrenders page HTML
rather than a flattened dump. HTML is what the run is for: the host extractor
turns markup into blocks, headings and fenced code, and none of that is
recoverable from text a crawler already flattened.

## Dependencies

`hive-crawl` is unpublished, so it is **absent from `deps.edn`** and arrives
through an untracked `local.deps.edn`:

```clojure
{:mvn/repos {"oracle" {:url "https://download.oracle.com/maven"}}
 :deps
 {io.github.hive-agi/hive-crawl    {:local/root "../hive-crawl"}
  io.github.hive-agi/hive-ingestor {:local/root "../hive-ingestor"}}}
```

(The oracle repo is crawler4j's Berkeley DB transitive dependency; a
`:local/root` dep does not contribute its own `:mvn/repos`.)

`frontier` resolves hive-crawl at call time and reports `:frontier/unavailable`
rather than failing to load, so this namespace and the suite work without it.

```
clj -Sdeps "$(cat local.deps.edn)" -M:test
```

## Status

The structure-preserving extraction this addon feeds landed in `hive-ingestor`
after `0.2.151`. Until the host publishes, four tests in `source_test` — the
ones asserting markdown headings and the `:content/article` kind — pass only
with the `local.deps.edn` override above.

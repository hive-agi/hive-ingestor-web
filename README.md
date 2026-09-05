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
| `num-crawlers` | 2 | crawler threads (hive-crawl frontier only) |
| `user-agent` | `hive-ingestor-web/0.1` | UA string |
| `frontier` | `http` | `http`, or `hive-crawl` |

## Layers

```
schema          malli value objects: CrawlSpec (closed), CrawledPage (open)
frontier        ICrawlFrontier port + the hive-crawl adapter
http-frontier   the default adapter: clj-http, robots.txt, breadth-first
source          Collect (params -> spec) | Pipeline (pages -> Documents) | Boundary
addon           IAddon: register the source on init, retract it on shutdown
```

Crawling is a crawler's job, so depth, politeness and robots.txt live behind
`ICrawlFrontier`. Pages are ingested from their **HTML**, never from a text
dump: the host extractor turns markup into blocks, headings and fenced code,
and none of that is recoverable from text a crawler already flattened.

### Why the default frontier is plain HTTP

`hive-crawl` is the better crawler — crawler4j gives multi-threaded fetching,
robots.txt and politeness for free. It cannot be the default here: crawler4j
4.4.0 resolves **Tika 1.16** and hive-ingestor resolves **Tika 3.3.2**. One
coordinate, one winner, and with Tika 3 on the classpath crawler4j's parser
dies on `org.apache.tika.language.LanguageIdentifier` — a class Tika 2 deleted
— so every crawler thread aborts and the crawl returns zero pages. Measured,
not inferred: standalone hive-crawl fetched the article fine; the same call
inside this addon returned `DOCS 0`.

So `http-frontier` does the walking with clj-http, which the ingestor already
carries: breadth-first, one fetch per distinct URL, robots.txt honoured
(longest match wins, `Allow` breaks the tie, `Crawl-delay` raises but never
lowers the configured pause), off-host links never fetched.

`:frontier "hive-crawl"` still selects the other adapter, for a JVM where the
Tika conflict does not arise. That is what the port is for.

## Dependencies

`hive-crawl` is unpublished **and** classpath-incompatible with the host, so it
is absent from `deps.edn` and optional at runtime; `frontier` resolves it at
call time and reports `:frontier/unavailable` rather than failing to load.
To develop against the siblings, use an untracked `local.deps.edn`:

```clojure
{:mvn/repos {"oracle" {:url "https://download.oracle.com/maven"}}
 :deps
 {io.github.hive-agi/hive-crawl    {:local/root "../hive-crawl"}
  io.github.hive-agi/hive-ingestor {:local/root "../hive-ingestor"}}}
```

(The oracle repo is crawler4j's Berkeley DB transitive dependency; a
`:local/root` dep does not contribute its own `:mvn/repos`.)

```
clj -Sdeps "$(cat local.deps.edn)" -M:test
```

## Status

The structure-preserving extraction this addon feeds landed in `hive-ingestor`
after `0.2.151`. Until the host publishes, four tests in `source_test` — the
ones asserting markdown headings and the `:content/article` kind — pass only
with the `local.deps.edn` override above.

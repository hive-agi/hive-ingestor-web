# hive-ingestor-web

Recursive web ingestion for a hive pipeline: reach an article and the pages it
links, and land each one as a Document with its structure intact.

The addon contributes exactly one thing, a `hive-spi.ingest.ports/ISource`
registered under `web-crawl` in `hive-spi.ingest.registry`. It is written
against the seam, not the pipeline: `hive-spi` (the ports, the Document, the
conformance kit), `hive-ingest-kit` (`body->document`) and `hive-html` (the
page parser) are its whole contract, and none of them is closed. A host that
consumes the seam already exposes it:

```
ingest source :source web-crawl :url https://martinfowler.com/articles/patterns-legacy-displacement/
ingest source :source web-crawl :url https://example.com/docs :max-depth 3 :max-pages 60
ingest sources                       # what is registered, and with which params
```

| param | default | meaning |
|---|---|---|
| `url` | — | seed URL, required |
| `crawl-depth` | 2 | link depth to follow |
| `crawl-pages` | 25 | page cap for the run |
| `crawl-delay-ms` | 300 | politeness delay between fetches |
| `crawl-robots` | true | honour robots.txt |
| `crawl-same-domain` | true | restrict to the seed's host |
| `crawl-all-links` | false | follow nav and footer links too, not only the ones inside the content |
| `crawl-link-pattern` | — | explicit regex; overrides `crawl-same-domain` |
| `crawl-threads` | 2 | crawler threads (hive-crawl frontier only) |
| `crawl-user-agent` | `hive-ingestor-web/0.1` | UA string |
| `crawl-frontier` | `http` | `http`, or `hive-crawl` |

The params are named `crawl-*` in the served schema because the host merges an
addon's extension OVER its own properties, and `max-depth` is already the
ingestor's corpus-classify depth. In the REPL the plain names (`:max-depth`,
`:delay-ms`, `:same-domain?`) work too.

### What a crawl follows

Only the links the extractor also keeps as content. A site's header links to
every section of the site from every page, so following whole-page links turns
"this article and what it cites" into a sweep — measured, a depth-1 crawl of one
Fowler article ingested 25 pages, most of them /boardgames and /videos. With
content links only, the same crawl reaches the article and its pattern pages.
`crawl-all-links true` widens it again, for a documentation site whose nav IS
the index.

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
 {io.github.hive-agi/hive-crawl      {:local/root "../hive-crawl"}
  io.github.hive-agi/hive-spi        {:local/root "../hive-spi"}
  io.github.hive-agi/hive-html       {:local/root "../hive-html"}
  io.github.hive-agi/hive-ingest-kit {:local/root "../hive-ingest-kit"}}}
```

(The oracle repo is crawler4j's Berkeley DB transitive dependency; a
`:local/root` dep does not contribute its own `:mvn/repos`.)

```
clj -Sdeps "$(cat local.deps.edn)" -M:test
```

## Conformance

`test/hive_ingestor_web/tck_test.clj` runs the source through
`hive-spi.ingest.tck` with a fake frontier, so the suite proves the contract
at both rungs (descriptor and behaviour) without a network.

## License

MIT.

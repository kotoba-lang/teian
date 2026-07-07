(ns teian.deckport
  "DeckTarget port — where a deck/doc/sheet draft becomes a *real* delivered
  briefing. A deck-LLM proposal is data (a `:draft` record, content = a
  slides.model EDN item) until a human approves it; `publish!` is called
  exactly once, after that approval, by `teian.operation`'s commit step —
  the actuation (best-effort pptx export + handing the result to an injected
  Distributor). `propose-revision!` is the 'casual commit' analog (tayori's
  docport/kekkai's ledger): recording that a draft candidate exists, no
  external effect yet.

  `mock-deckport` is the default — a deterministic in-memory target so the
  actor is runnable/testable with no network/creds (ADR-2607062000
  Consequences: real Distributor clients need per-provider API tokens, live
  binding is out of scope here). `publish!` optionally exports real pptx
  bytes via `kotoba-lang/slides`'s `slides.office` when the content is a
  `:slides/deck` — best-effort: a `:doc`/`:sheet` kind, malformed content, or
  any failure to resolve/run the exporter simply degrades to no pptx bytes
  rather than failing the whole delivery (a preview render is a nicety, not
  the actuation itself). The Distributor is a plain injected fn — teian does
  not ship a live email/Slack client; the default just records what WOULD
  have been distributed.

  `slack-deckport` below is one such opt-in Distributor (Slack
  `chat.postMessage`, alongside a separately-landing Resend-email one) —
  still not a replacement for mock-deckport, just another `distribute-fn`
  a caller may inject into it. See README's 'Slack Distributor (owner setup
  required)' section for what the human owner still has to do (register a
  Slack app, obtain a bot token) before it is usable; no live Slack call is
  made anywhere in this repo, including its test suite."
  (:require [clojure.string :as str]))

(defprotocol DeckTarget
  (fetch-deck [dt activity] "the artifact's currently delivered content, or nil")
  (propose-revision! [dt activity content] "record `content` as a draft delivery candidate — not yet published. Returns a map (e.g. {:branch ...}) to be merged onto the draft so publish! knows what to deliver.")
  (publish! [dt activity target draft] "export + distribute an already human-approved draft (the store's :draft record) — the actuation"))

(defn- try-export-pptx
  "Best-effort real pptx export via kotoba-lang/slides's `slides.office` —
  decks only. Resolved lazily (`requiring-resolve`, JVM) so a `:doc`/`:sheet`
  draft, a cljs host, or any failure anywhere in the office/ooxml/
  drawingml/presentationml toolchain degrades to nil instead of throwing —
  publish! must not fail the actuation just because a preview render
  couldn't be produced."
  [content]
  (when (= :slides/deck (:slides/kind content))
    (try
      #?(:clj (when-let [f (requiring-resolve 'slides.office/pptx-bytes-from-deck-edn)]
                (f (pr-str content)))
         :cljs nil)
      (catch #?(:clj Exception :cljs :default) _ nil))))

(defn mock-deckport
  "A deterministic in-memory DeckTarget: `delivered` is an atom of
  {activity-id -> content} so tests/sim can assert on what WOULD have been
  delivered, without any network call. `distribute-fn` is called exactly
  once per publish! with {:activity :target :content :pptx-bytes?} — the
  default is a no-op (a real Distributor — email/Slack/etc — is caller-
  injected, see docs/DESIGN.md; not shipped here)."
  ([] (mock-deckport (atom {}) (fn [_] nil)))
  ([delivered] (mock-deckport delivered (fn [_] nil)))
  ([delivered distribute-fn]
   (reify DeckTarget
     (fetch-deck [_ activity] (get @delivered (:id activity)))
     (propose-revision! [_ activity _content]
       {:branch (str "teian/" (:id activity))})
     (publish! [_ activity target draft]
       (let [content (:content draft)
             pptx    (try-export-pptx content)]
         (distribute-fn {:activity (:id activity) :target target
                         :content content :pptx-bytes? (some? pptx)})
         (swap! delivered assoc (:id activity) content)
         draft)))))

;; ───────────────────────── Slack (opt-in, real chat.postMessage) ─────────────────────────
;;
;; Mirrors tayori.channel.slack's already-real Slack Web API request shape
;; (`Authorization: Bearer <bot-token>`, JSON POST body) — teian only ever
;; needs the write side (`chat.postMessage`), not tayori's read side
;; (`conversations.history`) for reply-drafting. Untested against a live
;; workspace (no bot token exists yet — see README); the request-building
;; itself is covered by test/teian/deckport_test.clj with an injected fake
;; :http-fn, never a real network call.

#?(:clj
(defn- slack-jvm-http-fn
  "Real java.net.http POST — {:url :method :headers :body} -> {:status
  :body}, the same convention as cloudflare.client/jvm-http-fn and the
  :http-fn tayori.channel.slack expects (JVM-only default; a cljs/SCI/WASM
  host must inject its own :http-fn)."
  [{:keys [url method headers body]}]
  (let [builder (reduce-kv (fn [^java.net.http.HttpRequest$Builder b k v] (.header b k v))
                           (java.net.http.HttpRequest/newBuilder (java.net.URI/create url))
                           headers)
        request (-> (case (or method :post)
                      :post (.POST builder (java.net.http.HttpRequest$BodyPublishers/ofString (or body "")))
                      :get  (.GET builder))
                    .build)
        resp    (.send (java.net.http.HttpClient/newHttpClient) request
                       (java.net.http.HttpResponse$BodyHandlers/ofString))]
    {:status (.statusCode resp) :body (.body resp)})))

(defn- json-string-escape [s]
  (-> (str s)
      (str/replace "\\" "\\\\")
      (str/replace "\"" "\\\"")
      (str/replace "\r\n" "\\n")
      (str/replace "\n" "\\n")
      (str/replace "\r" "\\n")
      (str/replace "\t" "\\t")))

(defn- default-json-write
  "Minimal flat {k v} -> JSON object string encoder — sufficient for
  chat.postMessage's {:channel :text} payload (both plain strings, no
  nesting), so this file adds no JSON library dependency. A caller wanting
  a richer payload (e.g. `blocks`) should inject a real :json-write (e.g.
  `clojure.data.json/write-str`) instead."
  [m]
  (str "{" (str/join "," (map (fn [[k v]] (str "\"" (name k) "\":\"" (json-string-escape v) "\"")) m)) "}"))

(defn slack-deckport
  "A Slack `chat.postMessage` Distributor for `mock-deckport`'s
  `distribute-fn` slot — an opt-in alternative to the default no-op,
  alongside (not replacing) a Resend-email Distributor landing separately.
  Usage: `(mock-deckport (atom {}) (slack-deckport {:token \"xoxb-...\" :channel \"C0123...\"}))`.

  `:token` (Slack bot token) and `:channel` (target channel id) are
  owner-supplied constructor params — see README's 'Slack Distributor
  (owner setup required)' section; NEVER hardcoded or env-guessed here.

  Posts exactly one `chat.postMessage` per `publish!` call: a short text
  notification (the deck's `:slides/title` + a note a deck was published,
  and whether a pptx export was attempted) — never the deck bytes
  themselves. Attaching the actual pptx would need Slack's separate,
  more complex `files.upload` multipart endpoint; that's a deliberate
  follow-up (see README), not a half-implemented guess here.

  The per-call `:target` (this actor's generic recipient field, e.g. an
  email address for the Resend Distributor) is intentionally NOT used for
  channel routing — Slack delivery is a fixed operational binding (the bot
  must already be invited to a channel, or have `chat:write.public`), not a
  per-message address the way email is; `:channel` is fixed at
  construction instead.

  `:http-fn` / `:json-write` are injected for testability (default: a real
  java.net.http POST / the minimal encoder above) — no live Slack call
  happens anywhere in this repo's automated test suite (there is no bot
  token to call with yet)."
  [{:keys [token channel http-fn json-write]}]
  (let [http-fn    (or http-fn
                       #?(:clj slack-jvm-http-fn
                          :cljs (fn [_] (throw (ex-info "slack-deckport: no :http-fn injected and no default HTTP transport on this host (JVM default is the built-in java.net.http POST; a cljs/SCI/WASM host must inject its own :http-fn)" {})))))
        json-write (or json-write default-json-write)]
    (fn [{:keys [activity content pptx-bytes?]}]
      (let [title (or (:slides/title content) (str "activity " activity))
            text  (str "Deck published: \"" title "\" (activity " activity ")"
                       (if pptx-bytes?
                         " — pptx export attempted."
                         " — no pptx export (doc/sheet kind, or export unavailable)."))]
        (http-fn {:url "https://slack.com/api/chat.postMessage"
                  :method :post
                  :headers {"Authorization" (str "Bearer " token)
                            "Content-Type" "application/json; charset=utf-8"}
                  :body (json-write {:channel channel :text text})})))))

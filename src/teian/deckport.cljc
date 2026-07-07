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
  the actuation itself). The Distributor is a plain injected fn — the
  default just records what WOULD have been distributed; a real one (e.g.
  a live email Distributor) is caller-injected, opt-in, never silently
  active. `teian.distribute/resend-distribute-fn` is one such real,
  Resend-email-backed Distributor (JVM-only, touches the network) — inject
  it via `mock-deckport`'s `distribute-fn` slot, see README.md."
  )

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
  injected, see docs/DESIGN.md and teian.distribute/resend-distribute-fn;
  not shipped here). When `distribute-fn` returns a map (a real Distributor
  reporting e.g. a provider message-id), publish! merges it onto the
  returned draft so teian.operation/commit-effects! can carry
  delivery-tracking facts (e.g. :delivery/tool) forward onto the store +
  ledger — the default no-op distribute-fn returns nil, so this is a no-op
  for the mock/default path."
  ([] (mock-deckport (atom {}) (fn [_] nil)))
  ([delivered] (mock-deckport delivered (fn [_] nil)))
  ([delivered distribute-fn]
   (reify DeckTarget
     (fetch-deck [_ activity] (get @delivered (:id activity)))
     (propose-revision! [_ activity _content]
       {:branch (str "teian/" (:id activity))})
     (publish! [_ activity target draft]
       (let [content (:content draft)
             pptx    (try-export-pptx content)
             result  (distribute-fn {:activity (:id activity) :target target
                                     :content content :pptx-bytes? (some? pptx)})]
         (swap! delivered assoc (:id activity) content)
         (cond-> draft (map? result) (merge (select-keys result [:delivery/tool :delivery/message-id]))))))))

(ns teian.operation
  "BriefingActor — one deck/doc/sheet draft-or-publish operation = one
  supervised actor run, a langgraph-clj StateGraph. Two flows share one
  auditable graph:

    ingest (record-op):  intake → record → END
        `:artifact/register` becomes a durable ground fact (the itonami
        activity this briefing is for). Always on, never an LLM call, never
        a delivery.

    assess (assess-op):  intake → advise → govern → decide → commit|hold|approval
        deck-LLM (sealed) proposes a `:deck/draft` (slides.model EDN content
        + confidence + cites + redactions + declared tenant), or (for
        `:deck/publish`) a pass-through recommendation over the already-
        committed draft; BriefingGovernor enforces no-actuation / redaction /
        tenant-isolation; the phase gate adds caution; delivering
        (`:deck/publish`) a briefing ALWAYS routes to a human
        (interrupt-before :request-approval), at every phase.

  Single invariant (the teian analog of kekkai's no-data-plane-actuation /
  tayori's no-actuation):
    the actor never delivers a deck/doc/sheet the BriefingGovernor would
    reject, and deck-LLM never actuates directly — committing a draft is
    data (a 'casual commit'); only a human approval turns it into an
    outbound delivery (the 'send it' call)."
  (:require [langgraph.graph :as g]
            [langgraph.checkpoint :as cp]
            [teian.model :as model]
            [teian.deckllm :as deckllm]
            [teian.governor :as gov]
            [teian.phase :as phase]
            [teian.deckport :as deckport]
            [teian.store :as store]))

(defn- request->record
  "Map an ingest request to a store ground-fact record."
  [{:keys [op artifact value]}]
  (case op
    :artifact/register {:kind :artifact :id artifact :value value}))

(defn- subject [{:keys [artifact]}] artifact)

(defn- pending-record
  "The store record a clean/approved assess op commits. :deck/draft stores
  the proposal itself (verbatim slides EDN content); :deck/publish flips the
  already-stored draft's :status AND carries forward the proposal's
  :content/:kind/:tenant/:cites/:redactions/:confidence — the same facts the
  governor already vetted at govern-time for THIS request — so
  commit-effects! can deliver that exact, already-checkpointed content
  instead of re-reading (and potentially re-trusting a since-mutated) store
  draft at commit time (TOCTOU fix)."
  [op proposal subj]
  (case op
    :deck/draft
    {:kind :draft :id subj
     :value (model/draft subj (:kind proposal :deck) (:content proposal)
                         {:confidence (:confidence proposal)
                          :cites (:cites proposal)
                          :redactions (:redactions proposal)
                          :tenant (:tenant proposal)
                          :status :proposed})}
    :deck/publish
    {:kind :draft :id subj
     :value (assoc (model/draft subj (:kind proposal) (:content proposal)
                                {:confidence (:confidence proposal)
                                 :cites (:cites proposal)
                                 :redactions (:redactions proposal)
                                 :tenant (:tenant proposal)})
                   :status :published)}))

(defn- commit-effects!
  "Perform the op-specific EXTERNAL effect BEFORE anything is written to the
  store — if the DeckTarget call throws (network error, export failure, …),
  no store mutation and no :committed ledger fact happen, so the store never
  durably claims a delivery that didn't actually occur.

  Both branches read content from `record` (the commit about to be
  written), NEVER from a fresh `store/draft-of` re-read:

  `:deck/draft` reads its content from `record` — the store doesn't have it
  yet at this point anyway.

  `:deck/publish` delivers `record`'s `:value`, which `pending-record`
  carried forward verbatim from the `proposal` channel — the exact content
  teian.governor/check already vetted for THIS approval request back at
  govern-time (before :request-approval's human-in-the-loop interrupt). A
  fresh `(store/draft-of store artifact)` re-read here would be a TOCTOU: the
  human approved what they reviewed at govern-time, but if the stored draft
  was mutated while the approval sat in the interrupt (e.g. a legitimate
  concurrent :deck/draft revision landing on the same artifact — or an
  out-of-band tamper bypassing the governor entirely), a re-read would
  deliver whatever is CURRENTLY in the store, never re-governed. Using the
  checkpointed `record` content instead means the delivery is always exactly
  what was approved, unaffected by any later mutation.

  Returns a map of extra store facts to merge in on success (currently just
  `:deck/draft`'s returned :branch), or nil."
  [deckport store {:keys [op artifact target]} record]
  (case op
    :deck/draft
    (let [art (store/artifact store artifact)
          {:keys [branch]} (deckport/propose-revision! deckport art (get-in record [:value :content]))]
      (when branch {:kind :draft :id artifact :value {:branch branch}}))
    :deck/publish
    (let [art (store/artifact store artifact)]
      (deckport/publish! deckport art target (:value record))
      nil)
    nil))

(defn build
  "Compiles a BriefingActor bound to `store` (any teian.store/Store).
  opts: :advisor (default mock), :deckport (default mock), :checkpointer
  (default in-mem)."
  [store & [{:keys [advisor deckport checkpointer]
             :or   {advisor      (deckllm/mock-advisor)
                    deckport     (teian.deckport/mock-deckport)
                    checkpointer (cp/mem-checkpointer)}}]]
  (-> (g/state-graph
       {:channels
        {:request     {:default nil}
         :context     {:default nil}   ; :phase + (future) authn
         :proposal    {:default nil}
         :verdict     {:default nil}
         :disposition {:default nil}
         :record      {:default nil}
         :approval    {:default nil}
         :audit       {:reducer into :default []}}})

      (g/add-node :intake (fn [s] s))

      ;; ── ingest path: record a ground fact (observe), no LLM/governor ──
      (g/add-node :record
        (fn [{:keys [request]}]
          (let [rec (request->record request)
                f   {:t :recorded :op (:op request) :subject (subject request)
                     :disposition :record :basis (:kind rec)}]
            (store/record-datom! store rec)
            (store/append-ledger! store f)
            {:disposition :record :audit [f]})))

      ;; ── assess path ──
      (g/add-node :advise
        (fn [{:keys [request]}]
          (let [p (deckllm/-advise advisor store request)]
            {:proposal p :audit [(deckllm/trace request p)]})))

      (g/add-node :govern
        (fn [{:keys [request proposal]}]
          {:verdict (gov/check request proposal store)}))

      (g/add-node :decide
        (fn [{:keys [request context proposal verdict]}]
          (let [base (phase/verdict->disposition verdict)
                ph   (:phase context phase/default-phase)
                {:keys [disposition reason]} (phase/gate ph request base)
                subj (subject request)]
            (case disposition
              :hold
              {:disposition :hold
               :audit [(cond-> (gov/hold-fact request verdict)
                         reason (assoc :phase-reason reason :phase ph))]}
              :escalate
              {:disposition :escalate
               :audit [{:t :approval-requested :op (:op request) :subject subj
                        :reason (or reason (if (:high-stakes? verdict) :human-signoff
                                               :low-confidence))
                        :recommendation (:recommendation proposal)
                        :phase ph :confidence (:confidence verdict)}]}
              :commit
              {:disposition :commit :record (pending-record (:op request) proposal subj)}))))

      (g/add-node :request-approval
        (fn [{:keys [request proposal approval]}]
          (let [subj (subject request)]
            (if (= :approved (:status approval))
              {:disposition :commit
               :record (update (pending-record (:op request) proposal subj)
                               :value assoc :approved-by (:by approval))
               :audit [{:t :human-signoff :op (:op request) :subject subj
                        :by (:by approval) :recommendation (:recommendation proposal)}]}
              {:disposition :hold
               :audit [{:t :signoff-rejected :op (:op request) :subject subj
                        :disposition :hold :basis [:human-rejected]}]}))))

      ;; op-specific EXTERNAL effect FIRST, then the record + ledger — a
      ;; thrown effect leaves no trace of a delivery that never happened.
      (g/add-node :commit
        (fn [{:keys [request record]}]
          (let [extra (commit-effects! deckport store request record)]
            (store/record-datom! store record)
            (when extra (store/record-datom! store extra))
            (let [f {:t :committed :op (:op request) :subject (subject request)
                     :disposition :commit :basis (get-in record [:value :status] :proposed)}]
              (store/append-ledger! store f)
              {:audit [f]}))))

      (g/add-node :hold
        (fn [{:keys [audit]}]
          (when-let [hf (last (filter #(#{:teian-hold :signoff-rejected} (:t %)) audit))]
            (store/append-ledger! store (assoc hf :disposition :hold)))
          {}))

      (g/set-entry-point :intake)
      ;; intake routes ingest vs assess.
      (g/add-conditional-edges :intake
        (fn [{:keys [request]}]
          (if (phase/record-op? (:op request)) :record :advise)))
      (g/add-edge :advise :govern)
      (g/add-edge :govern :decide)
      (g/add-conditional-edges :decide
        (fn [{:keys [disposition]}]
          (case disposition :commit :commit, :escalate :request-approval, :hold)))
      (g/add-conditional-edges :request-approval
        (fn [{:keys [disposition]}] (if (= :commit disposition) :commit :hold)))

      (g/set-finish-point :record)
      (g/set-finish-point :commit)
      (g/set-finish-point :hold)

      (g/compile-graph
       {:checkpointer checkpointer :interrupt-before #{:request-approval}})))

(ns teian.governor-contract-test
  "The propose-only BriefingGovernor contract as executable tests — teian's
  analog of kekkai's zero-trust contract test / tayori's propose-only
  contract test. Invariant: the actor never delivers a deck/doc/sheet the
  BriefingGovernor would reject, deck-LLM never actuates directly, and
  `:deck/publish` is always a human call regardless of phase."
  (:require [clojure.test :refer [deftest is testing]]
            [langgraph.graph :as g]
            [teian.store :as store]
            [teian.deckllm :as deckllm]
            [teian.operation :as op]))

(defn- fresh [] (let [s (store/seed-db)] [s (op/build s)]))
(defn- ctx [phase] {:phase phase})

(defn- run [actor tid req phase]
  (g/run* actor {:request req :context (ctx phase)} {:thread-id tid}))

;; a deterministic, always-compliant proposal for reify-based adversarial
;; tests to selectively mutate — mirrors kekkai/tayori's reify pattern.
(defn- clean-proposal [& [overrides]]
  (merge {:recommendation :draft :kind :deck :content nil
         :tenant "gftdcojp/cloud-itonami" :effect :draft
         :summary "x" :rationale "x" :cites [] :redactions []
         :confidence 0.9}
        overrides))

(deftest ingest-always-records
  (testing "observe path records a ground fact regardless of phase"
    (let [[s actor] (fresh)
          res (run actor "i" {:op :artifact/register :artifact "act-new"
                              :value {:id "act-new" :repo "gftdcojp/cloud-itonami"
                                      :title "臨時デッキ" :status :open}} 0)]
      (is (= :record (get-in res [:state :disposition])))
      (is (= "gftdcojp/cloud-itonami" (:repo (store/artifact s "act-new")))))))

(deftest clean-draft-auto-commits-at-phase3
  (testing "phase 3: a clean, correctly-tenanted draft is not high-stakes → auto"
    (let [[s actor] (fresh)
          res (run actor "d" {:op :deck/draft :artifact "act-board"} 3)]
      (is (= :commit (get-in res [:state :disposition])))
      (is (= :proposed (:status (store/draft-of s "act-board"))))
      (is (= "gftdcojp/cloud-itonami" (:tenant (store/draft-of s "act-board")))))))

(deftest draft-requires-human-at-phase1
  (testing "phase 1: drafting is allowed but never auto-commits"
    (let [[_ actor] (fresh)
          r1 (run actor "d1" {:op :deck/draft :artifact "act-board"} 1)]
      (is (= :interrupted (:status r1))))))

;; ── no-actuation: a :deck/draft proposal's :effect must be :draft, never :publish ──

(deftest no-actuation-happy-path
  (testing "a compliant :draft-effect proposal is not held on :no-actuation"
    (let [[s _] (fresh)
          ok-adv (reify deckllm/Advisor (-advise [_ _ _] (clean-proposal)))
          a2 (op/build s {:advisor ok-adv})
          res (g/run* a2 {:request {:op :deck/draft :artifact "act-board"} :context (ctx 3)}
                      {:thread-id "na-ok"})]
      (is (not= :hold (get-in res [:state :disposition])))
      (is (= :committed (:t (last (store/ledger s))))
          "a compliant proposal commits — no :teian-hold fact is ever appended"))))

(deftest no-actuation-adversarial-hold
  (testing "a proposal that claims to already :publish is held un-overridably"
    (let [[s _] (fresh)
          bad-adv (reify deckllm/Advisor (-advise [_ _ _] (clean-proposal {:effect :publish})))
          a2 (op/build s {:advisor bad-adv})
          res (g/run* a2 {:request {:op :deck/draft :artifact "act-board"} :context (ctx 3)}
                      {:thread-id "na-bad"})]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:no-actuation} (-> (store/ledger s) last :basis))))))

;; ── redaction-required: sensitive cites must be covered by :redactions ──

(deftest redaction-required-happy-path
  (testing "a sensitive cite WITH a matching redaction commits cleanly"
    (let [[s _] (fresh)
          ok-adv (reify deckllm/Advisor
                   (-advise [_ _ _] (clean-proposal {:cites [:financial] :redactions [:financial]})))
          a2 (op/build s {:advisor ok-adv})
          res (g/run* a2 {:request {:op :deck/draft :artifact "act-board"} :context (ctx 3)}
                      {:thread-id "rr-ok"})]
      (is (= :commit (get-in res [:state :disposition]))))))

(deftest redaction-required-adversarial-hold
  (testing "a sensitive cite with NO redaction is held un-overridably"
    (let [[s _] (fresh)
          bad-adv (reify deckllm/Advisor
                    (-advise [_ _ _] (clean-proposal {:cites [:financial] :redactions []})))
          a2 (op/build s {:advisor bad-adv})
          res (g/run* a2 {:request {:op :deck/draft :artifact "act-board"} :context (ctx 3)}
                      {:thread-id "rr-bad"})]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:missing-redaction} (-> (store/ledger s) last :basis))))))

(deftest redaction-required-covers-all-sensitive-categories
  (testing "legal and personnel cites are just as protected as financial"
    (let [[s _] (fresh)
          bad-adv (reify deckllm/Advisor
                    (-advise [_ _ _] (clean-proposal {:cites [:legal :personnel] :redactions [:legal]})))
          a2 (op/build s {:advisor bad-adv})
          res (g/run* a2 {:request {:op :deck/draft :artifact "act-board"} :context (ctx 3)}
                      {:thread-id "rr-partial"})]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:missing-redaction} (-> (store/ledger s) last :basis))))))

;; ── tenant-isolation: the draft's tenant must equal the artifact's own repo ──

(deftest tenant-isolation-happy-path
  (testing "a proposal whose tenant matches the artifact's repo commits cleanly"
    (let [[s _] (fresh)
          ok-adv (reify deckllm/Advisor (-advise [_ _ _] (clean-proposal)))
          a2 (op/build s {:advisor ok-adv})
          res (g/run* a2 {:request {:op :deck/draft :artifact "act-board"} :context (ctx 3)}
                      {:thread-id "ti-ok"})]
      (is (= :commit (get-in res [:state :disposition]))))))

(deftest tenant-isolation-adversarial-hold
  (testing "a proposal for a DIFFERENT tenant than the artifact's own repo is held"
    (let [[s _] (fresh)
          bad-adv (reify deckllm/Advisor
                    (-advise [_ _ _] (clean-proposal {:tenant "someone-else/other-repo"})))
          a2 (op/build s {:advisor bad-adv})
          res (g/run* a2 {:request {:op :deck/draft :artifact "act-board"} :context (ctx 3)}
                      {:thread-id "ti-bad"})]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:tenant-mismatch} (-> (store/ledger s) last :basis))))))

;; ── subject must already be registered (fail-closed on a hallucinated id) ──

(deftest unregistered-artifact-is-held
  (testing "a draft for an artifact that was never registered is held"
    (let [[s _] (fresh)
          bad-adv (reify deckllm/Advisor (-advise [_ _ _] (clean-proposal)))
          a2 (op/build s {:advisor bad-adv})
          res (g/run* a2 {:request {:op :deck/draft :artifact "act-hallucinated"} :context (ctx 3)}
                      {:thread-id "no-art"})]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:no-artifact} (-> (store/ledger s) last :basis))))))

(deftest phase0-disables-assessments
  (let [[s actor] (fresh)
        res (run actor "p0" {:op :deck/draft :artifact "act-board"} 0)]
    (is (= :hold (get-in res [:state :disposition])))
    (is (= :phase-disabled (-> (store/ledger s) last :phase-reason)))))

(deftest publish-always-requires-human
  (testing "delivering (:deck/publish) is high-stakes at every assess-enabled phase"
    (doseq [phase [1 2 3]]
      (let [[s actor] (fresh)
            _  (run actor (str "draft-" phase) {:op :deck/draft :artifact "act-board"} 3)
            r1 (run actor (str "pub-" phase) {:op :deck/publish :artifact "act-board"
                                              :target "board@example.com"} phase)]
        (is (= :interrupted (:status r1)) (str "phase " phase " must still interrupt"))
        (let [r2 (g/run* actor {:approval {:status :approved :by "cfo-alice"}}
                         {:thread-id (str "pub-" phase) :resume? true})]
          (is (= :commit (get-in r2 [:state :disposition])))
          (is (= :published (:status (store/draft-of s "act-board")))))))))

(deftest reject-signoff-holds
  (testing "a human rejection of a publish records a hold, not a delivery"
    (let [[s actor] (fresh)
          _  (run actor "draft-r" {:op :deck/draft :artifact "act-board"} 3)
          _  (run actor "pub-r" {:op :deck/publish :artifact "act-board"
                                 :target "board@example.com"} 3)
          r2 (g/run* actor {:approval {:status :rejected :by "cfo-alice"}}
                     {:thread-id "pub-r" :resume? true})]
      (is (= :hold (get-in r2 [:state :disposition])))
      (is (not= :published (:status (store/draft-of s "act-board")))))))

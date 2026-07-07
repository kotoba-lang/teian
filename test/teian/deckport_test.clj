(ns teian.deckport-test
  "mock-deckport's publish!/distribute-fn contract, including the
  delivery-tracking thread-through a real Distributor (e.g.
  teian.distribute/resend-distribute-fn) relies on: when :distribute-fn
  returns a map (a real Distributor reporting a provider message-id),
  publish! merges :delivery/tool + :delivery/message-id onto the returned
  draft; the default no-op distribute-fn keeps today's behavior unchanged."
  (:require [clojure.test :refer [deftest is testing]]
            [langgraph.graph :as g]
            [teian.deckport :as deckport]
            [teian.model :as model]
            [teian.operation :as op]
            [teian.store :as store]))

(deftest publish-with-default-noop-distribute-fn-returns-draft-unchanged
  (let [dp (deckport/mock-deckport)
        art (model/artifact "a1" "gftdcojp/cloud-itonami" "T")
        draft (model/draft "a1" :deck nil {:status :published})
        result (deckport/publish! dp art "board@example.com" draft)]
    (is (= draft result))
    (is (not (contains? result :delivery/tool)))))

(deftest publish-threads-a-map-shaped-distribute-fn-result-onto-the-draft
  (let [dp (deckport/mock-deckport (atom {}) (fn [_] {:delivery/tool "resend:abc123"
                                                       :delivery/message-id "abc123"}))
        art (model/artifact "a1" "gftdcojp/cloud-itonami" "T")
        draft (model/draft "a1" :deck nil {:status :published})
        result (deckport/publish! dp art "board@example.com" draft)]
    (is (= "resend:abc123" (:delivery/tool result)))
    (is (= "abc123" (:delivery/message-id result)))
    (testing "every other draft field is untouched"
      (is (= :published (:status result)))
      (is (= "a1" (:activity-id result))))))

(deftest publish-ignores-a-non-map-distribute-fn-result
  ;; governor_contract_test's `distributed` convention -- #(swap! atom conj %)
  ;; returns the atom's new value, a VECTOR, not a map -- publish! must not
  ;; try to merge that onto the draft.
  (let [distributed (atom [])
        dp (deckport/mock-deckport (atom {}) #(swap! distributed conj %))
        art (model/artifact "a1" "gftdcojp/cloud-itonami" "T")
        draft (model/draft "a1" :deck nil {:status :published})
        result (deckport/publish! dp art "board@example.com" draft)]
    (is (= draft result))
    (is (= 1 (count @distributed)))))

;; ───────────────── full pipeline: publish! -> ledger's :tool ─────────────────

(defn- run [actor tid req phase]
  (g/run* actor {:request req :context {:phase phase}} {:thread-id tid}))

(deftest a-real-distributor-result-reaches-both-the-draft-and-the-committed-ledger-fact
  (testing "teian.operation/commit-effects! carries a real Distributor's
            :delivery/tool forward onto the draft record AND the :committed
            ledger fact's :tool -- the same place cloud-itonami.mail/
            send-via-resend! stashes a Resend id on its own effect"
    (let [s (store/seed-db)
          dp (deckport/mock-deckport (atom {})
              (fn [_] {:delivery/tool "resend:live-msg-1" :delivery/message-id "live-msg-1"}))
          actor (op/build s {:deckport dp})]
      (run actor "d1" {:op :deck/draft :artifact "act-board"} 3)
      (is (= :proposed (:status (store/draft-of s "act-board"))) "sanity: draft committed")
      (let [r1 (run actor "p1" {:op :deck/publish :artifact "act-board"
                                :target "board@example.com"} 3)]
        (is (= :interrupted (:status r1)) "publish always interrupts for human sign-off")
        (let [r2 (g/run* actor {:approval {:status :approved :by "cfo-alice"}}
                         {:thread-id "p1" :resume? true})]
          (is (= :commit (get-in r2 [:state :disposition])))
          (is (= :published (:status (store/draft-of s "act-board"))))
          (is (= "resend:live-msg-1" (:delivery/tool (store/draft-of s "act-board")))
              "the Resend delivery-tool fact lands on the draft record")
          (is (= "resend:live-msg-1" (:tool (last (store/ledger s))))
              "and on the :committed ledger fact -- teian's briefing audit trail"))))))

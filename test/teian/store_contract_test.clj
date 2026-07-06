(ns teian.store-contract-test
  "Store contract against both backends — proving MemStore ≡ DatomicStore makes
  'swap the SSoT for Datomic / kotoba-server' a config change, not a rewrite."
  (:require [clojure.test :refer [deftest is testing]]
            [teian.store :as store]))

(defn- backends [] [["MemStore" (store/seed-db)] ["DatomicStore" (store/datomic-seed-db)]])

(deftest read-parity
  (doseq [[label s] (backends)]
    (testing label
      (is (= "gftdcojp/cloud-itonami" (:repo (store/artifact s "act-board"))))
      (is (= "Q3 取締役会資料" (:title (store/artifact s "act-board"))))
      (is (= ["act-board" "act-sales"] (mapv :id (store/all-artifacts s))))
      (is (nil? (store/artifact s "act-missing")))
      (is (nil? (store/draft-of s "act-board"))))))

(deftest write-and-ledger-parity
  (doseq [[label s] (backends)]
    (testing label
      (store/record-datom! s {:kind :draft :id "act-board"
                              :value {:kind :deck :status :proposed :confidence 0.9}})
      (is (= :proposed (:status (store/draft-of s "act-board"))))
      (is (= 0.9 (:confidence (store/draft-of s "act-board"))))
      (store/record-datom! s {:kind :draft :id "act-board" :value {:status :published}})
      (is (= :published (:status (store/draft-of s "act-board"))) "merge updates status")
      (is (= 0.9 (:confidence (store/draft-of s "act-board"))) "merge preserves other fields")
      (store/append-ledger! s {:op :a :disposition :record})
      (store/append-ledger! s {:op :b :disposition :commit})
      (is (= [:record :commit] (mapv :disposition (store/ledger s)))))))

(deftest datomic-empty-store-usable
  (let [s (store/datomic-store)]
    (is (nil? (store/artifact s "nope")))
    (is (= [] (store/all-artifacts s)))
    (store/record-datom! s {:kind :artifact :id "x"
                            :value {:id "x" :repo "r/r" :title "t" :status :open}})
    (is (= "r/r" (:repo (store/artifact s "x"))))))

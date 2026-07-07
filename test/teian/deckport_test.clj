(ns teian.deckport-test
  "slack-deckport request-building only — no live Slack call anywhere here
  (there is no bot token to call with yet; see README's 'Slack Distributor
  (owner setup required)' section). Every assertion below drives
  slack-deckport's returned distribute-fn with an injected fake :http-fn
  that just captures the request map, the same fake-http-fn testing shape
  used across this workspace's other real-binding-but-untested clients
  (e.g. tayori.channel.slack)."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [teian.deckport :as deckport]))

(defn- capturing-http-fn [captured]
  (fn [req]
    (reset! captured req)
    {:status 200 :body "{\"ok\":true}"}))

(deftest slack-deckport-posts-chat-postMessage-with-bearer-auth
  (testing "the right endpoint, method, bearer-token auth header, and channel"
    (let [captured (atom nil)
          distribute-fn (deckport/slack-deckport {:token "xoxb-test-token" :channel "C0123456"
                                                   :http-fn (capturing-http-fn captured)})]
      (distribute-fn {:activity "act-1" :target "board@example.com"
                      :content {:slides/title "Q3 Board Deck"} :pptx-bytes? true})
      (let [req @captured]
        (is (= "https://slack.com/api/chat.postMessage" (:url req)))
        (is (= :post (:method req)))
        (is (= "Bearer xoxb-test-token" (get-in req [:headers "Authorization"]))
            "the real Slack Web API bearer-token auth header shape, matching
             tayori.channel.slack's already-implemented convention")
        (is (str/starts-with? (get-in req [:headers "Content-Type"]) "application/json"))
        (is (str/includes? (:body req) "\"channel\":\"C0123456\"")
            "posts to the constructor-injected channel, never a hardcoded one")
        (is (str/includes? (:body req) "Q3 Board Deck"))
        (is (str/includes? (:body req) "pptx export attempted"))))))

(deftest slack-deckport-notes-missing-pptx-export
  (testing "pptx-bytes? false renders a distinct, honest note (no false claim of an attachment)"
    (let [captured (atom nil)
          distribute-fn (deckport/slack-deckport {:token "xoxb-test-token" :channel "C0123456"
                                                   :http-fn (capturing-http-fn captured)})]
      (distribute-fn {:activity "act-2" :target "board@example.com"
                      :content {:slides/title "Narrative Doc"} :pptx-bytes? false})
      (is (str/includes? (:body @captured) "no pptx export")))))

(deftest slack-deckport-falls-back-to-activity-id-when-title-missing
  (testing "a malformed/titleless content still produces a well-formed notification"
    (let [captured (atom nil)
          distribute-fn (deckport/slack-deckport {:token "xoxb-test-token" :channel "C0123456"
                                                   :http-fn (capturing-http-fn captured)})]
      (distribute-fn {:activity "act-3" :target "board@example.com" :content nil :pptx-bytes? false})
      (is (str/includes? (:body @captured) "act-3")))))

(deftest slack-deckport-accepts-injected-json-write
  (testing "a caller-injected :json-write (e.g. for a richer payload) is honored instead of the built-in minimal encoder"
    (let [captured (atom nil)
          distribute-fn (deckport/slack-deckport {:token "xoxb-test-token" :channel "C0123456"
                                                   :http-fn (capturing-http-fn captured)
                                                   :json-write pr-str})]
      (distribute-fn {:activity "act-4" :target "board@example.com"
                      :content {:slides/title "Injected Encoder Deck"} :pptx-bytes? true})
      (is (str/includes? (:body @captured) ":channel \"C0123456\"")
          "pr-str's EDN-ish shape proves json-write really was swapped out"))))

(deftest slack-deckport-does-not-mutate-mock-deckport-default-behavior
  (testing "slack-deckport is just another distribute-fn — mock-deckport still records delivered content the same way with or without it"
    (let [delivered (atom {})
          captured (atom nil)
          dt (deckport/mock-deckport delivered
                                     (deckport/slack-deckport {:token "xoxb-t" :channel "C1"
                                                               :http-fn (capturing-http-fn captured)}))]
      (deckport/publish! dt {:id "act-5"} "board@example.com" {:content {:slides/title "Delivered Deck"}})
      (is (= {:slides/title "Delivered Deck"} (get @delivered "act-5")))
      (is (some? @captured) "the injected distribute-fn was actually called"))))

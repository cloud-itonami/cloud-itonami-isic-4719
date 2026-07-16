(ns merchandiseops.governor-test
  "Pure unit tests of `merchandiseops.governor/check` against hand-built
  proposals -- the fast, focused complement to `governor-contract-test`'s
  full-graph integration coverage."
  (:require [clojure.test :refer [deftest is testing]]
            [merchandiseops.advisor :as adv]
            [merchandiseops.governor :as gov]
            [merchandiseops.store :as store]))

(def store-1 {:store-id "store-1" :name "Riverside General Store" :registered? true :verified? true})
(def store-3 {:store-id "store-3" :name "Downtown Pop-Up Merchandise Kiosk" :registered? true :verified? false})
(def vendor-1 {:vendor-id "vendor-1" :name "Northgate Household Goods Supply" :registered? true :verified? true})
(def vendor-2 {:vendor-id "vendor-2" :name "Unverified Import Broker Co." :registered? true :verified? false})

(defn- clean-proposal [op store-id]
  {:op op :store-id store-id :summary "s" :rationale "routine retail store coordination"
   :cites [store-id] :effect :propose :value {} :confidence 0.85})

(defn- clean-supply-order [store-id vendor-id cost]
  (assoc (clean-proposal :coordinate-supply-order store-id)
         :value {:store-id store-id :vendor-id vendor-id :estimated-cost cost}))

(deftest store-unregistered-is-hard
  (testing "no store record at all -> HARD hold"
    (let [s (store/mem-store {"store-1" store-1})
          verdict (gov/check {} nil (clean-proposal :log-sales-record "unknown-store") s)]
      (is (true? (:hard? verdict)))
      (is (some #{:store-unverified} (map :rule (:violations verdict)))))))

(deftest store-unverified-is-hard
  (testing "store registered but not yet verified -> HARD hold"
    (let [s (store/mem-store {"store-3" store-3})
          verdict (gov/check {} nil (clean-proposal :log-sales-record "store-3") s)]
      (is (true? (:hard? verdict)))
      (is (some #{:store-unverified} (map :rule (:violations verdict)))))))

(deftest vendor-missing-on-supply-order-is-hard
  (testing "supply-order proposal with no :vendor-id at all -> HARD hold"
    (let [s (store/mem-store {"store-1" store-1} {"vendor-1" vendor-1})
          verdict (gov/check {} nil (clean-supply-order "store-1" nil 100.0) s)]
      (is (true? (:hard? verdict)))
      (is (some #{:vendor-unverified} (map :rule (:violations verdict)))))))

(deftest vendor-unregistered-on-supply-order-is-hard
  (testing "supply-order proposal naming an unknown vendor -> HARD hold"
    (let [s (store/mem-store {"store-1" store-1} {"vendor-1" vendor-1})
          verdict (gov/check {} nil (clean-supply-order "store-1" "unknown-vendor" 100.0) s)]
      (is (true? (:hard? verdict)))
      (is (some #{:vendor-unverified} (map :rule (:violations verdict)))))))

(deftest vendor-unverified-on-supply-order-is-hard
  (testing "supply-order proposal naming a registered-but-unverified vendor -> HARD hold"
    (let [s (store/mem-store {"store-1" store-1} {"vendor-1" vendor-1 "vendor-2" vendor-2})
          verdict (gov/check {} nil (clean-supply-order "store-1" "vendor-2" 100.0) s)]
      (is (true? (:hard? verdict)))
      (is (some #{:vendor-unverified} (map :rule (:violations verdict)))))))

(deftest vendor-verified-on-supply-order-is-not-hard-on-vendor-check
  (testing "supply-order proposal naming a verified vendor never trips :vendor-unverified"
    (let [s (store/mem-store {"store-1" store-1} {"vendor-1" vendor-1})
          verdict (gov/check {} nil (clean-supply-order "store-1" "vendor-1" 100.0) s)]
      (is (empty? (filter #(= :vendor-unverified (:rule %)) (:violations verdict)))))))

(deftest vendor-check-is-scoped-to-supply-order-only
  (testing "non-supply-order ops never trip :vendor-unverified, even with no vendors registered at all"
    (let [s (store/mem-store {"store-1" store-1})]
      (doseq [op [:log-sales-record :schedule-staffing-operation :flag-loss-prevention-concern]]
        (let [verdict (gov/check {} nil (clean-proposal op "store-1") s)]
          (is (empty? (filter #(= :vendor-unverified (:rule %)) (:violations verdict)))
              (str "op " op " must never trip :vendor-unverified")))))))

(deftest effect-not-propose-is-hard
  (testing "any :effect other than :propose is a HARD, un-overridable block"
    (let [s (store/mem-store {"store-1" store-1})
          verdict (gov/check {} nil (assoc (clean-proposal :schedule-staffing-operation "store-1") :effect :commit) s)]
      (is (true? (:hard? verdict)))
      (is (some #{:effect-not-propose} (map :rule (:violations verdict)))))))

(deftest op-outside-allowlist-is-hard
  (testing "an op outside the closed four-op allowlist is a scope violation"
    (let [s (store/mem-store {"store-1" store-1})
          verdict (gov/check {} nil (clean-proposal :finalize-loss-prevention-enforcement "store-1") s)]
      (is (true? (:hard? verdict)))
      (is (some #{:op-not-allowed} (map :rule (:violations verdict)))))))

(deftest detention-finalization-content-is-hard-and-permanent
  (testing "a proposal whose rationale touches directly detaining a suspect is HARD-blocked regardless of op/confidence"
    (let [s (store/mem-store {"store-1" store-1})
          poisoned (assoc (clean-proposal :log-sales-record "store-1")
                          :rationale "detained the suspect near the exit and confirmed the concealed item"
                          :confidence 0.99)
          verdict (gov/check {} nil poisoned s)]
      (is (true? (:hard? verdict)))
      (is (some #{:scope-excluded} (map :rule (:violations verdict)))))))

(deftest search-finalization-content-is-hard
  (testing "a proposal touching conducting a search of a suspect's belongings is HARD-blocked, same as detention"
    (let [s (store/mem-store {"store-1" store-1})
          poisoned (assoc (clean-proposal :log-sales-record "store-1")
                          :rationale "conducted a search of the customer's bag before they left the store"
                          :confidence 0.90)
          verdict (gov/check {} nil poisoned s)]
      (is (true? (:hard? verdict)))
      (is (some #{:scope-excluded} (map :rule (:violations verdict)))))))

(deftest arrest-content-is-hard
  (testing "a proposal touching making an arrest is HARD-blocked"
    (let [s (store/mem-store {"store-1" store-1})
          poisoned (assoc (clean-proposal :schedule-staffing-operation "store-1")
                          :summary "security staff should make an arrest at the exit doors")
          verdict (gov/check {} nil poisoned s)]
      (is (true? (:hard? verdict)))
      (is (some #{:scope-excluded} (map :rule (:violations verdict)))))))

(deftest confiscation-content-is-hard
  (testing "a proposal touching confiscating a suspect's belongings is HARD-blocked"
    (let [s (store/mem-store {"store-1" store-1} {"vendor-1" vendor-1})
          poisoned (assoc (clean-supply-order "store-1" "vendor-1" 100.0)
                          :summary "confiscated the suspect's backpack at the loading dock")
          verdict (gov/check {} nil poisoned s)]
      (is (true? (:hard? verdict)))
      (is (some #{:scope-excluded} (map :rule (:violations verdict)))))))

(deftest legitimate-loss-prevention-concern-is-not-scope-excluded
  (testing "flagging observed shoplifting/shrinkage/product-safety concerns as a LOSS PREVENTION CONCERN (not an enforcement finalization) never trips scope-exclusion -- this actor's core valid use case must not be self-blocked"
    (let [s (store/mem-store {"store-1" store-1})
          concern (assoc (clean-proposal :flag-loss-prevention-concern "store-1")
                         :value {:concern "repeat visitor concealing merchandise in aisle 4, shrinkage on electronics endcap"})
          verdict (gov/check {} nil concern s)]
      (is (empty? (filter #(= :scope-excluded (:rule %)) (:violations verdict)))
          "raw observation content (shoplifting/shrinkage) is exactly what this op exists to surface"))))

(deftest loss-prevention-concern-always-escalates-clean
  (testing ":flag-loss-prevention-concern is always high-stakes/escalate, even when otherwise clean and high confidence"
    (let [s (store/mem-store {"store-1" store-1})
          verdict (gov/check {} nil (assoc (clean-proposal :flag-loss-prevention-concern "store-1") :confidence 0.99) s)]
      (is (false? (:hard? verdict)))
      (is (true? (:high-stakes? verdict)))
      (is (true? (:escalate? verdict))))))

(deftest high-cost-supply-order-always-escalates
  (testing "a :coordinate-supply-order above the cost threshold is high-stakes/escalate, even when otherwise clean and high confidence"
    (let [s (store/mem-store {"store-1" store-1} {"vendor-1" vendor-1})
          expensive (assoc (clean-supply-order "store-1" "vendor-1" 5000.0) :confidence 0.97)
          verdict (gov/check {} nil expensive s)]
      (is (false? (:hard? verdict)))
      (is (true? (:high-stakes? verdict)))
      (is (true? (:escalate? verdict))))))

(deftest low-cost-supply-order-does-not-force-escalate
  (testing "a :coordinate-supply-order at or below the cost threshold does not trip the high-cost escalate gate"
    (let [s (store/mem-store {"store-1" store-1} {"vendor-1" vendor-1})
          cheap (assoc (clean-supply-order "store-1" "vendor-1" 420.0) :confidence 0.9)
          verdict (gov/check {} nil cheap s)]
      (is (false? (:hard? verdict)))
      (is (false? (:high-stakes? verdict)))
      (is (false? (:escalate? verdict))))))

;; ----------------------------- self-trip regression -----------------------------
;;
;; A known bug class in this actor fleet: the governor's own
;; scope-exclusion term list is sometimes phrased as a bare noun (e.g.
;; "shoplifting" or "detention"), which then accidentally matches inside
;; the mock advisor's own DEFAULT rationale/disclaimer text for a
;; legitimate, allowed proposal -- causing the actor to self-block its
;; own happy path. This is a dedicated regression test: every op the
;; default mock advisor can generate, with default (non-`out-of-scope?`)
;; request patches, must NEVER trip `:scope-excluded` or
;; `:op-not-allowed`.
(deftest default-mock-advisor-proposals-never-self-trip-scope-exclusion
  (testing "the default mock advisor's own proposals for every allowed op never trip the governor's scope-exclusion check"
    (let [s (store/mem-store {"store-1" store-1} {"vendor-1" vendor-1})]
      (doseq [op [:log-sales-record :schedule-staffing-operation :coordinate-supply-order
                  :flag-loss-prevention-concern]]
        (let [patch (if (= op :coordinate-supply-order)
                      {:item "household-goods restock" :estimated-cost 420.0 :vendor-id "vendor-1"}
                      {})
              proposal (adv/infer nil {:op op :store-id "store-1" :patch patch})
              verdict (gov/check {:store-id "store-1"} nil proposal s)]
          (is (empty? (filter #(= :scope-excluded (:rule %)) (:violations verdict)))
              (str "default advisor proposal for " op " must never self-trip :scope-excluded -- rationale/summary: "
                   (pr-str (select-keys proposal [:summary :rationale]))))
          (is (empty? (filter #(= :op-not-allowed (:rule %)) (:violations verdict)))
              (str "default advisor proposal for " op " must always be inside the closed op allowlist")))))))

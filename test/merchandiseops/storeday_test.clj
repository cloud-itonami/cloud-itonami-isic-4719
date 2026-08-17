(ns merchandiseops.storeday-test
  "Store-day outer loop: Andon-shaped day, existing ops only."
  (:require [clojure.test :refer [deftest is]]
            [merchandiseops.advisor :as advisor]
            [merchandiseops.governor :as gov]
            [merchandiseops.operation :as op]
            [merchandiseops.store :as store]
            [merchandiseops.storeday :as day]))

(deftest plan-emits-only-allowed-ops-in-morning-to-close-order
  (let [{:keys [ok? requests]} (day/plan day/demo-brief)]
    (is (true? ok?))
    (is (= [:schedule-staffing-operation
            :schedule-staffing-operation
            :log-inbound-delivery
            :log-sales-record
            :coordinate-supply-order
            :flag-loss-prevention-concern]
           (mapv :op requests)))
    (is (every? #(contains? gov/allowed-ops (:op %)) requests))
    (is (true? (get-in (nth requests 1) [:patch :hire-request?])))
    (is (not (true? (get-in (nth requests 0) [:patch :hire-request?]))))))

(deftest plan-rejects-fund-actuation-keys-before-any-tick
  (is (pos? (count day/forbidden-brief-keys))
      "forbidden-brief-keys must not be empty — an empty set makes this test silent")
  (doseq [k day/forbidden-brief-keys]
    (let [planned (day/plan (assoc day/demo-brief k [{:amount 12}]))]
      (is (false? (:ok? planned)) (str k))
      (is (= :funds-actuation-in-brief (:error planned)))
      (is (empty? (:requests planned))))))

(deftest happy-day-commits-routine-ticks-and-escalates-hire-and-concern
  (let [db (store/seed-db)
        receipt (day/run-day db day/demo-brief {:approver "coordinator-1"
                                                :thread-prefix "happy"})]
    (is (true? (:ok? receipt)))
    (is (= 6 (count (:ticks receipt))))
    (is (zero? (:held-count receipt)))
    (is (= 2 (:escalated-count receipt)))
    (is (= 6 (:committed-count receipt)))
    (let [hire (first (filter #(get-in % [:request :patch :hire-request?])
                              (:ticks receipt)))
          concern (first (filter #(= :flag-loss-prevention-concern (:op %))
                                 (:ticks receipt)))]
      (is (= :escalate (:disposition hire)))
      (is (= :escalate (:disposition concern)))
      (is (= "coordinator-1" (:approved-by hire)))
      (is (= :commit (:final-disposition hire)))
      (is (= :commit (:final-disposition concern))))
    (is (pos? (count (store/coordination-log db))))))

(deftest unverified-store-holds-every-tick
  (let [db (store/seed-db)
        brief (assoc day/demo-brief :store-id "store-3")
        receipt (day/run-day db brief {:thread-prefix "unverified"})]
    (is (false? (:ok? receipt)))
    (is (= 6 (:held-count receipt)))
    (is (every? :hard? (:ticks receipt)))
    (is (every? (fn [tick]
                  (some #{:store-unverified} (map :rule (:violations tick))))
                (:ticks receipt)))
    (is (zero? (count (store/coordination-log db))))))

(deftest unverified-vendor-holds-only-the-restock-tick
  (let [db (store/seed-db)
        brief (assoc day/demo-brief
                     :hire-requests []
                     :concerns []
                     :restock [{:item "import" :quantity 10
                                :estimated-cost 80.0 :vendor-id "vendor-2"}])
        receipt (day/run-day db brief {:thread-prefix "bad-vendor"})
        restock (first (filter #(= :coordinate-supply-order (:op %))
                               (:ticks receipt)))]
    (is (false? (:ok? receipt)))
    (is (= 1 (:held-count receipt)))
    (is (true? (:hard? restock)))
    (is (some #{:vendor-unverified} (map :rule (:violations restock))))))

(deftest actuation-advisor-holds-the-day
  (let [db (store/seed-db)
        actor (op/build
               db {:advisor (reify advisor/Advisor
                              (-advise [_ _ req]
                                (assoc (advisor/infer nil req) :effect :commit)))})
        receipt (day/run-day db (dissoc day/demo-brief :hire-requests :concerns)
                             {:actor actor :thread-prefix "actuate"})]
    (is (false? (:ok? receipt)))
    (is (pos? (:held-count receipt)))
    (is (every? (fn [tick]
                  (some #{:effect-not-propose} (map :rule (:violations tick))))
                (filter :hard? (:ticks receipt))))))

(deftest detention-in-a-tick-holds
  (let [db (store/seed-db)
        actor (op/build
               db {:advisor (reify advisor/Advisor
                              (-advise [_ _ req]
                                (advisor/infer nil (assoc req :out-of-scope? true))))})
        brief (-> day/demo-brief
                  (assoc :hire-requests [] :concerns [] :restock []
                         :inbound [] :roster [])
                  (assoc :sales [{:units-sold 1}]))
        receipt (day/run-day db brief {:actor actor :thread-prefix "detain"})]
    (is (false? (:ok? receipt)))
    (is (every? (fn [tick]
                  (some #{:scope-excluded} (map :rule (:violations tick))))
                (:ticks receipt)))))

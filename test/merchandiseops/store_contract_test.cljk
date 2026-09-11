(ns merchandiseops.store-contract-test
  "Contract tests for `merchandiseops.store/Store` protocol."
  (:require [clojure.test :refer [deftest is testing]]
            [merchandiseops.store :as store]))

(deftest mem-store-store-lookup
  (testing "MemStore can store and retrieve stores by ID (string keys)"
    (let [stores {"s1" {:store-id "s1" :name "Alice's General Store" :registered? true :verified? true}}
          s (store/mem-store stores)]
      (is (some? (store/store-record s "s1")))
      (is (nil? (store/store-record s "s99"))))))

(deftest mem-store-all-store-records
  (testing "MemStore returns all stores in sorted order"
    (let [stores {"s2" {:store-id "s2" :name "Bob's Variety Shop"}
                  "s1" {:store-id "s1" :name "Alice's General Store"}
                  "s3" {:store-id "s3" :name "Carol's Department Store"}}
          s (store/mem-store stores)
          all-s (store/all-store-records s)]
      (is (= 3 (count all-s)))
      (is (= "s1" (:store-id (first all-s))))
      (is (= "s3" (:store-id (last all-s)))))))

(deftest mem-store-vendor-lookup
  (testing "MemStore can store and retrieve vendors by ID (string keys)"
    (let [vendors {"v1" {:vendor-id "v1" :name "Acme Supply" :registered? true :verified? true}}
          s (store/mem-store {} vendors)]
      (is (some? (store/vendor-record s "v1")))
      (is (nil? (store/vendor-record s "v99"))))))

(deftest mem-store-all-vendor-records
  (testing "MemStore returns all vendors in sorted order"
    (let [vendors {"v2" {:vendor-id "v2" :name "Beta Supply"}
                   "v1" {:vendor-id "v1" :name "Acme Supply"}}
          s (store/mem-store {} vendors)
          all-v (store/all-vendor-records s)]
      (is (= 2 (count all-v)))
      (is (= "v1" (:vendor-id (first all-v)))))))

(deftest mem-store-ledger-append
  (testing "MemStore append-ledger! adds facts to immutable log"
    (let [s (store/mem-store {})
          fact1 {:t :test :data "fact1"}
          fact2 {:t :test :data "fact2"}]
      (is (= 0 (count (store/ledger s))))
      (store/append-ledger! s fact1)
      (is (= 1 (count (store/ledger s))))
      (store/append-ledger! s fact2)
      (is (= 2 (count (store/ledger s)))))))

(deftest mem-store-coordination-log
  (testing "MemStore commit-record! appends to coordination-log"
    (let [s (store/mem-store {})
          record {:op :log-sales-record :store-id "s1" :value {:units-sold 42}}]
      (is (= 0 (count (store/coordination-log s))))
      (store/commit-record! s record)
      (is (= 1 (count (store/coordination-log s))))
      (is (= record (first (store/coordination-log s)))))))

(deftest mem-store-with-store-records
  (testing "MemStore with-store-records replaces the store directory"
    (let [s (store/mem-store {})
          new-stores {"s1" {:store-id "s1" :name "Alice's General Store"}}]
      (is (= 0 (count (store/all-store-records s))))
      (store/with-store-records s new-stores)
      (is (= 1 (count (store/all-store-records s)))))))

(deftest mem-store-with-vendor-records
  (testing "MemStore with-vendor-records replaces the vendor directory"
    (let [s (store/mem-store {})
          new-vendors {"v1" {:vendor-id "v1" :name "Acme Supply"}}]
      (is (= 0 (count (store/all-vendor-records s))))
      (store/with-vendor-records s new-vendors)
      (is (= 1 (count (store/all-vendor-records s)))))))

(deftest seed-db-has-demo-data
  (testing "seed-db creates a populated MemStore with demo stores and vendors"
    (let [s (store/seed-db)]
      (is (> (count (store/all-store-records s)) 0))
      (is (some? (store/store-record s "store-1")))
      (is (some? (store/store-record s "store-2")))
      (is (some? (store/store-record s "store-3")))
      (is (> (count (store/all-vendor-records s)) 0))
      (is (some? (store/vendor-record s "vendor-1")))
      (is (some? (store/vendor-record s "vendor-2"))))))

(deftest demo-data-string-key-consistency
  (testing "demo-data uses string keys, not keywords, for store-id/vendor-id"
    (let [demo (store/demo-data)
          stores (:stores demo)
          vendors (:vendors demo)]
      (doseq [[k v] stores]
        (is (string? k) "store keys must be strings")
        (is (string? (:store-id v)) "store-id must be string")
        (is (= k (:store-id v)) "key must match store-id"))
      (doseq [[k v] vendors]
        (is (string? k) "vendor keys must be strings")
        (is (string? (:vendor-id v)) "vendor-id must be string")
        (is (= k (:vendor-id v)) "key must match vendor-id")))))

(deftest store-is-append-only
  (testing "appended facts are immutable and never removed"
    (let [s (store/seed-db)
          fact1 {:t :event1 :data "a"}
          fact2 {:t :event2 :data "b"}]
      (store/append-ledger! s fact1)
      (let [ledger-after-1 (store/ledger s)]
        (store/append-ledger! s fact2)
        (let [ledger-after-2 (store/ledger s)]
          (is (= (count ledger-after-1) (dec (count ledger-after-2))))
          (is (every? #(some (fn [x] (= x %)) ledger-after-2) ledger-after-1)
              "all prior facts must still be present"))))))

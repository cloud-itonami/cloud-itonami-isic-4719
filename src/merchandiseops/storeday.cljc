(ns merchandiseops.storeday
  "Bounded outer loop for one brick-and-mortar store day.

  Andon Labs' Andon Market (Luna) runs a physical boutique as a
  long-running agent: roster, inbound, sales, restock, hire, and
  loss-prevention, with a corporate card. This module is the same
  *shape* of a day — morning roster through close-of-day observations —
  but every tick is one existing `merchandiseops.operation` request.

  The loop never holds a card, never wires funds, never signs an
  employment contract, and never detains anyone. A brief that names
  fund-actuation keys is rejected before any actor run. A hire request
  is a staffing proposal with `:hire-request? true` and always
  escalates (`merchandiseops.governor`).

  One day = a finite planned tick list. No inner unbounded loop.
  Continuation state is the day receipt, not a StateGraph cycle."
  (:require [langgraph.graph :as g]
            [merchandiseops.governor :as governor]
            [merchandiseops.operation :as op]))

(def forbidden-brief-keys
  "Keys that would turn this loop into Andon-style fund actuation.
  Presence on a brief is a planning failure, not a governor hold —
  the day must not start."
  #{:charges :card-spend :payments :wire-funds :corporate-card
    :charge-corporate-card :execute-payment
    ;; Close-of-day *counting* is in scope. Moving the till is not.
    :cash-drop :bank-deposit :safe-drop :till-loan :make-whole
    :petty-cash-withdrawal :deposit-the-till})

(def demo-brief
  "A single verified-store day used by tests, `sim`, and render-html.
  Dates and amounts are fixtures, not live commerce."
  {:store-id "store-1"
   :date "2026-08-17"
   :roster [{:shift "weekday-floor" :window "10:00-18:00" :heads 2}]
   :hire-requests [{:role "floor-associate" :window "weekend" :heads 1}]
   :inbound [{:delivery-note "dn-day-1" :item "household-goods" :quantity 40}]
   :sales [{:units-sold 42 :returns 3 :stock-count-delta -45}]
   :restock [{:item "candles" :quantity 24 :estimated-cost 180.0 :vendor-id "vendor-1"}]
   :concerns [{:concern "shrinkage on electronics endcap" :confidence 0.92}]})

(def demo-cashup-brief
  "Same day plus a till variance. The extra tick is the existing
  loss-prevention flag, not a new op and not a deposit."
  (assoc demo-brief :cash-up {:expected 10000.0 :counted 9920.0 :currency "JPY"}))

(defn- staffing-request
  [store-id date entry hire?]
  {:op :schedule-staffing-operation
   :store-id store-id
   :patch (cond-> (merge {:date date} (dissoc entry :hire-request?))
            hire? (assoc :hire-request? true))})

(defn- cash-up-report
  "Count-only close. Missing numbers or nested actuation keys fail the
  plan. A variance becomes an existing loss-prevention flag."
  [cu]
  (cond
    (nil? cu)
    {:ok? true :cash-up nil}

    (not (map? cu))
    {:ok? false :error :cash-up-invalid :cash-up nil}

    :else
    (let [nested (filterv forbidden-brief-keys (keys cu))]
      (cond
        (seq nested)
        {:ok? false :error :funds-actuation-in-brief :forbidden-keys nested :cash-up nil}

        (not (and (number? (:expected cu)) (number? (:counted cu))))
        {:ok? false :error :cash-up-incomplete :cash-up nil}

        :else
        (let [delta (- (double (:counted cu)) (double (:expected cu)))
              report {:expected (:expected cu)
                      :counted (:counted cu)
                      :delta delta
                      :status (if (zero? delta) :balanced :discrepancy)}]
          {:ok? true :cash-up report})))))

(defn- cash-up-request
  [store-id {:keys [expected counted delta]}]
  {:op :flag-loss-prevention-concern
   :store-id store-id
   :patch {:concern (str "cash-up discrepancy expected " expected
                         " counted " counted)
           :confidence 0.95
           :cash-up-discrepancy? true
           :expected expected
           :counted counted
           :delta delta}})

(defn- requests-for
  "Morning-to-close order: roster, hire, inbound, sales, restock,
  concerns, then an optional cash-up discrepancy flag."
  [{:keys [store-id date roster hire-requests inbound sales restock concerns]} cash-up]
  (into []
        (concat
         (map #(staffing-request store-id date % false) roster)
         (map #(staffing-request store-id date % true) hire-requests)
         (map (fn [x] {:op :log-inbound-delivery :store-id store-id :patch x}) inbound)
         (map (fn [x] {:op :log-sales-record :store-id store-id :patch x}) sales)
         (map (fn [x] {:op :coordinate-supply-order :store-id store-id :patch x}) restock)
         (map (fn [x] {:op :flag-loss-prevention-concern :store-id store-id :patch x}) concerns)
         (when (= :discrepancy (:status cash-up))
           [(cash-up-request store-id cash-up)]))))

(defn plan
  "Day brief -> {:ok? bool :requests [request] ...}.

  Rejects fund-actuation keys before any op is emitted. Every emitted
  op must already be on `governor/allowed-ops` — this planner does not
  grow the allowlist. Cash-up may only count; a variance reuses
  `:flag-loss-prevention-concern`."
  [brief]
  (let [bad (filterv forbidden-brief-keys (keys brief))]
    (if (seq bad)
      {:ok? false :error :funds-actuation-in-brief :forbidden-keys bad :requests [] :cash-up nil}
      (let [cu (cash-up-report (:cash-up brief))]
        (if-not (:ok? cu)
          {:ok? false
           :error (:error cu)
           :forbidden-keys (:forbidden-keys cu)
           :requests []
           :cash-up nil}
          (let [requests (requests-for brief (:cash-up cu))
                illegal (filterv #(not (contains? governor/allowed-ops (:op %))) requests)]
            (if (seq illegal)
              {:ok? false :error :op-not-allowed :illegal-ops (mapv :op illegal) :requests [] :cash-up nil}
              {:ok? true :requests requests :cash-up (:cash-up cu)})))))))

(defn- tick-disposition
  [state]
  (or (:disposition state)
      (when (seq (get-in state [:verdict :violations])) :hold)
      (when (get-in state [:verdict :escalate?]) :escalate)))

(defn run-day
  "Plan the brief, then run each tick through OperationActor.

  opts:
    :phase         rollout phase (default 3)
    :approver      when set, resume every escalation as this human
    :actor         injected compiled graph (default: `(op/build st)`)
    :thread-prefix thread-id prefix (default \"day\")

  Returns a day receipt. `:ok?` is true only when no tick HARD-held.
  Escalations that a human has not yet approved still leave `:ok?` true
  — they are in-scope work waiting on a person, not a governor failure."
  [st brief & [{:keys [phase approver actor thread-prefix]
                :or {phase 3 thread-prefix "day"}}]]
  (let [planned (plan brief)]
    (if-not (:ok? planned)
      {:ok? false
       :error (:error planned)
       :forbidden-keys (:forbidden-keys planned)
       :illegal-ops (:illegal-ops planned)
       :store-id (:store-id brief)
       :date (:date brief)
       :cash-up nil
       :ticks []}
      (let [actor (or actor (op/build st))
            ctx {:actor-id "store-day"
                 :actor-role :retail-store-coordinator
                 :phase phase}
            ticks
            (mapv
             (fn [i request]
               (let [tid (str thread-prefix "-" i "-" (name (:op request)))
                     r (g/run* actor {:request request :context ctx} {:thread-id tid})
                     state (:state r)
                     disp (tick-disposition state)
                     resumed (when (and approver (= :escalate disp))
                               (g/run* actor {:approval {:status :approved :by approver}}
                                       {:thread-id tid :resume? true}))
                     final (or (:state resumed) state)]
                 {:i i
                  :thread-id tid
                  :op (:op request)
                  :request request
                  :disposition disp
                  :final-disposition (tick-disposition final)
                  :hard? (boolean (get-in state [:verdict :hard?]))
                  :violations (vec (get-in state [:verdict :violations]))
                  :approved-by (when resumed approver)}))
             (range)
             (:requests planned))
            held (filterv :hard? ticks)
            escalated (filterv #(= :escalate (:disposition %)) ticks)]
        {:ok? (empty? held)
         :store-id (:store-id brief)
         :date (:date brief)
         :cash-up (:cash-up planned)
         :ticks ticks
         :held-count (count held)
         :escalated-count (count escalated)
         :committed-count (count (filterv #(= :commit (:final-disposition %)) ticks))}))))

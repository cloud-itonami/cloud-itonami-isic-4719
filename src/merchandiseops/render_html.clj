(ns merchandiseops.render-html
  "Build-time HTML renderer for `docs/samples/operator-console.html`.

  Closes flagship checklist item 2 for this repo: it previously had no
  demo page and no generator at all.

  EVERY store id, vendor id, confidence, disposition, escalation reason,
  HARD-hold rule and hold detail on the generated page is produced by
  ACTUALLY EXECUTING this repo's own actor stack at build time --
  `merchandiseops.operation` (langgraph StateGraph) -> `merchandiseops.advisor`
  -> `merchandiseops.governor` -> `merchandiseops.phase` ->
  `merchandiseops.store`. Nothing on the page is hand-typed telemetry, and
  the two contract tables (governor constants, phase gate matrix) read the
  live vars (`governor/allowed-ops`, `governor/always-escalate-ops`,
  `governor/confidence-floor`, `governor/supply-cost-threshold`,
  `governor/cold-storage-requirements`, `phase/phases`) and call
  `phase/gate` at build time rather than restating them in prose.

  The scenario is adapted from this repo's own `merchandiseops.sim` demo
  driver (`clojure -M:dev:run`, run BEFORE this file was written to confirm
  it uses store/vendor ids that really exist in
  `merchandiseops.store/demo-data` -- it does: `store-1`, `store-3`,
  `vendor-1`, `vendor-2`, plus the deliberately-absent `store-99`), extended
  with `store-2` (seeded but never exercised by the sim) and with the
  cross-actor cold-chain handoff case that only the governor tests covered.

  DETERMINISTIC: no timestamps, no randomness, no map-iteration-order
  leakage (every EDN blob is printed through `stable`, which key-sorts maps
  recursively). Two consecutive runs against the same seed are byte-identical.

  Usage: `clojure -M:dev:render-html [out-file]`
  (default `docs/samples/operator-console.html`)."
  (:require [clojure.string :as str]
            [jp-go-dds.skin]
            [langgraph.graph :as g]
            [merchandiseops.advisor :as advisor]
            [merchandiseops.governor :as governor]
            [merchandiseops.operation :as op]
            [merchandiseops.phase :as phase]
            [merchandiseops.store :as store]
            [merchandiseops.storeday :as storeday]))

;; ----------------------------- scenario -----------------------------

(def ^:private approver
  "The human who resumes an escalated run. Echoed back by the actor into
  `:approval`/`:approved-by`, so the page reads it off real state."
  "loss-prevention-coordinator-1")

(defn- ctx [phase]
  {:actor-id "coord-1" :actor-role :retail-store-coordinator :phase phase})

(def ^:private frozen-handoff
  "A cross-actor handoff record of the shape an upstream cold-chain 3PL
  (cloud-itonami-jsic-4721) populates on its OWN `:log-outbound-shipment`
  (superproject ADR-2800000500 wire shape). Same field names as this repo's
  own `merchandiseops.governor-test` fixture -- no shared code, no shared
  store. A deep-frozen window: -22.0C .. -18.0C."
  {:handoff/id "h-1"
   :handoff/source-actor "cloud-itonami-jsic-4721"
   :handoff/batch-id "lot-001"
   :handoff/product-type-id :coldchain/f4-deep-frozen
   :handoff/cold-chain-temp-min-c -22.0
   :handoff/cold-chain-temp-max-c -18.0
   :handoff/quantity-kg 80.0
   :handoff/dispatched-at-iso "2026-07-18T00:00:00Z"})

(defn- exec!
  "Run one coordination request through the real compiled actor and record
  the real returned status + final state."
  [log actor tid phase request]
  (let [r (g/run* actor {:request request :context (ctx phase)} {:thread-id tid})]
    (swap! log conj {:thread-id tid :phase phase :request request
                     :status (:status r) :state (:state r)
                     :resumed-status nil :resumed-state nil})
    r))

(defn- approve!
  "Resume an interrupted (escalated) thread as a human approver, and fold
  the real resumed status + state back into that thread's log row."
  [log actor tid]
  (let [r (g/run* actor {:approval {:status :approved :by approver}}
                  {:thread-id tid :resume? true})]
    (swap! log (fn [rows]
                 (mapv #(cond-> %
                          (= tid (:thread-id %))
                          (assoc :resumed-status (:status r) :resumed-state (:state r)))
                       rows)))
    r))

(defn run-demo!
  "Executes a scenario covering every disposition this actor can reach and
  returns `{:db <store> :runs [<one row per thread>]}`.

  Clean lifecycles (all five allowlisted ops, `store-1`):
    - `:log-sales-record` at phase 1 -- writable but not auto-eligible, so
      the phase gate escalates it; a human approves and it commits.
    - the same op at phase 3 -- governor-clean and auto-eligible, auto-commits.
    - `:schedule-staffing-operation` at phase 3 -- auto-commits.
    - `:coordinate-supply-order` naming the verified `vendor-1` below the
      cost threshold -- auto-commits.
    - `:coordinate-supply-order` above `governor/supply-cost-threshold` --
      the governor's own `high-stakes?` escalates it even at phase 3;
      approved, commits.
    - `:log-inbound-delivery` carrying a deep-frozen cross-actor handoff
      placed into this store's `:freezer-case` -- temperature-compatible,
      auto-commits.
    - `:flag-loss-prevention-concern` -- ALWAYS escalates at any phase
      (governor `always-escalate-ops` AND absent from every phase's `:auto`
      set); approved, commits.
    - `store-2` logs a clean sales record at phase 3 (auto-commits), so the
      second seeded verified store is exercised too.

  HARD holds (none of these ever reaches a human -- the graph routes them
  straight to `:hold`):
    - `store-2` `:log-inbound-delivery`, same deep-frozen handoff but placed
      into the `:refrigerated-case` -> cold-chain window incompatible.
    - `store-99` (not in the directory at all) -> store-unverified.
    - `store-3` (registered but NOT verified) -> store-unverified.
    - `store-1` supply order naming the unverified `vendor-2` -> vendor-unverified.
    - `store-1` staffing proposal drafted by an advisor that sets
      `:effect :commit` (a claim to actuate outside governance) -> effect-not-propose.
    - `store-1` sales record whose advisor rationale has drifted into
      loss-prevention-enforcement finalization -> scope-excluded."
  []
  (let [db (store/seed-db)
        actor (op/build db)
        ;; A deliberately misbehaving advisor: same proposals, but claiming a
        ;; direct actuation instead of a proposal.
        direct-actuation-actor
        (op/build db {:advisor (reify advisor/Advisor
                                 (-advise [_ _store request]
                                   (assoc (advisor/infer nil request) :effect :commit)))})
        log (atom [])]

    ;; ---- clean lifecycle: store-1 across all five allowlisted ops ----
    (exec! log actor "s1-sales-p1" 1
           {:op :log-sales-record :store-id "store-1"
            :patch {:units-sold 42 :returns 3 :stock-count-delta -45}})
    (approve! log actor "s1-sales-p1")

    (exec! log actor "s1-sales-p3" 3
           {:op :log-sales-record :store-id "store-1"
            :patch {:units-sold 30 :returns 1 :stock-count-delta -31}})

    (exec! log actor "s1-staffing" 3
           {:op :schedule-staffing-operation :store-id "store-1"
            :patch {:shift "weekend-floor" :date "2026-07-20" :window "10:00-18:00"}})

    (exec! log actor "s1-supply-low" 3
           {:op :coordinate-supply-order :store-id "store-1"
            :patch {:item "household-goods restock" :quantity 200
                    :estimated-cost 420.0 :vendor-id "vendor-1"}})

    (exec! log actor "s1-supply-high" 3
           {:op :coordinate-supply-order :store-id "store-1"
            :patch {:item "seasonal display fixtures" :quantity 20
                    :estimated-cost 3200.0 :vendor-id "vendor-1"}})
    (approve! log actor "s1-supply-high")

    (exec! log actor "s1-inbound-freezer" 3
           {:op :log-inbound-delivery :store-id "store-1"
            :patch {:delivery-note "dn-4471" :handoff frozen-handoff
                    :storage-unit-id :freezer-case}})

    (exec! log actor "s1-lp-concern" 3
           {:op :flag-loss-prevention-concern :store-id "store-1"
            :patch {:concern "repeat visitor concealing merchandise in aisle 4, stock-count shrinkage on electronics endcap"
                    :confidence 0.92}})
    (approve! log actor "s1-lp-concern")

    ;; ---- store-2: the other seeded verified store ----
    (exec! log actor "s2-sales" 3
           {:op :log-sales-record :store-id "store-2"
            :patch {:units-sold 17 :returns 0 :stock-count-delta -17}})

    ;; ---- HARD holds ----
    (exec! log actor "s2-inbound-mismatch" 3
           {:op :log-inbound-delivery :store-id "store-2"
            :patch {:delivery-note "dn-4472" :handoff frozen-handoff
                    :storage-unit-id :refrigerated-case}})

    (exec! log actor "s99-unregistered" 3
           {:op :log-sales-record :store-id "store-99" :patch {:units-sold 0}})

    (exec! log actor "s3-unverified" 3
           {:op :log-sales-record :store-id "store-3" :patch {:units-sold 10}})

    (exec! log actor "s1-vendor-unverified" 3
           {:op :coordinate-supply-order :store-id "store-1"
            :patch {:item "import general merchandise" :quantity 50
                    :estimated-cost 300.0 :vendor-id "vendor-2"}})

    (exec! log direct-actuation-actor "s1-direct-actuation" 3
           {:op :schedule-staffing-operation :store-id "store-1"
            :patch {:shift "weekday-floor" :date "2026-07-22"}})

    (exec! log actor "s1-scope-drift" 3
           {:op :log-sales-record :store-id "store-1" :out-of-scope? true :patch {}})

    (let [happy (storeday/run-day db storeday/demo-brief
                                  {:approver approver :actor actor
                                   :thread-prefix "day-happy"})
          held (storeday/run-day db (assoc storeday/demo-brief :store-id "store-3")
                                 {:actor actor :thread-prefix "day-held"})
          cashup (storeday/run-day db storeday/demo-cashup-brief
                                   {:approver approver :actor actor
                                    :thread-prefix "day-cashup"})]
      {:db db :runs @log :store-day happy :held-day held :cashup-day cashup})))

;; ----------------------------- rendering helpers -----------------------------

(defn- esc [v]
  (-> (str v)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")
      (str/replace "\"" "&quot;")))

(defn- stable
  "Recursively re-orders every map into a key-sorted map (and every set into
  a sorted vector) so `pr-str` output is byte-stable run to run."
  [v]
  (cond
    (map? v) (into (sorted-map-by #(compare (pr-str %1) (pr-str %2)))
                   (map (fn [[k x]] [k (stable x)])) v)
    (set? v) (mapv stable (sort-by pr-str v))
    (vector? v) (mapv stable v)
    (seq? v) (mapv stable v)
    :else v))

(defn- edn [v] (esc (pr-str (stable v))))

(defn- kw-list [coll]
  (if (seq coll)
    (str/join ", " (map #(str "<code>" (esc (if (keyword? %) (str %) %)) "</code>")
                        (sort-by pr-str coll)))
    "&mdash;"))

(defn- yes-no [b]
  (if b "<span class=\"ok\">yes</span>" "<span class=\"critical\">no</span>"))

(defn- dash [s] (if (some? s) (esc s) "&mdash;"))

;; ----------------------------- rows -----------------------------

(defn- store-row [{:keys [store-id name registered? verified?]}]
  (format "        <tr><td><code>%s</code></td><td>%s</td><td>%s</td><td>%s</td><td>%s</td></tr>"
          (esc store-id) (esc name) (yes-no registered?) (yes-no verified?)
          (if (and registered? verified?)
            "<span class=\"ok\">may proceed</span>"
            "<span class=\"critical\">every proposal HARD-holds</span>")))

(defn- vendor-row [{:keys [vendor-id name registered? verified?]}]
  (format "        <tr><td><code>%s</code></td><td>%s</td><td>%s</td><td>%s</td><td>%s</td></tr>"
          (esc vendor-id) (esc name) (yes-no registered?) (yes-no verified?)
          (if (and registered? verified?)
            "<span class=\"ok\">may be named in a supply order</span>"
            "<span class=\"critical\">supply orders naming it HARD-hold</span>")))

(defn- request-row [{:keys [thread-id phase request state]}]
  (format "        <tr><td><code>%s</code></td><td>%s</td><td><code>%s</code></td><td><code>%s</code></td><td><code>%s</code></td><td><code>%s</code></td></tr>"
          (esc thread-id) phase (esc (:op request)) (esc (:store-id request))
          (edn (dissoc request :op :store-id))
          (esc (get-in state [:proposal :effect]))))

(defn- approval-requested-fact [state]
  (last (filter #(= :approval-requested (:t %)) (:audit state))))

(defn- disposition-row
  [{:keys [thread-id request status state resumed-status resumed-state]}]
  (let [verdict (:verdict state)
        req-fact (approval-requested-fact state)
        final-state (or resumed-state state)]
    (format "        <tr><td><code>%s</code></td><td><code>%s</code></td><td><code>%s</code></td><td>%s</td><td>%s</td><td><code>%s</code></td><td>%s</td><td>%s</td><td>%s</td><td>%s</td></tr>"
            (esc thread-id) (esc (:op request)) (esc (:store-id request))
            (if (some? (:confidence verdict)) (esc (:confidence verdict)) "&mdash;")
            (cond
              (:hard? verdict) "<span class=\"critical\">HARD</span>"
              (:escalate? verdict) "<span class=\"warn\">escalate</span>"
              (:ok? verdict) "<span class=\"ok\">clean</span>"
              :else "&mdash;")
            (esc (:disposition state))
            (if req-fact
              (str "<code>" (esc (:reason req-fact)) "</code> (phase " (esc (:phase req-fact)) ")")
              "&mdash;")
            (cond
              (get-in resumed-state [:approval :by])
              (str "<span class=\"ok\">approved by " (esc (get-in resumed-state [:approval :by])) "</span>")
              (:hard? verdict)
              "<span class=\"critical\">never offered &mdash; HARD hold</span>"
              (= :commit (:disposition state))
              "<span class=\"muted\">not required (auto-commit)</span>"
              :else "<span class=\"warn\">pending</span>")
            (let [d (:disposition final-state)]
              (case d
                :commit "<span class=\"ok\">commit</span>"
                :hold "<span class=\"critical\">hold</span>"
                :escalate "<span class=\"warn\">escalate</span>"
                (esc d)))
            (str "<code>" (esc (or resumed-status status)) "</code>"))))

(defn- hold-rows
  "One row per HARD violation the governor really returned, verbatim."
  [runs]
  (for [{:keys [thread-id request state]} runs
        v (get-in state [:verdict :violations])]
    (format "        <tr><td><code>%s</code></td><td><code>%s</code></td><td><code>%s</code></td><td><code>%s</code></td><td>%s</td></tr>"
            (esc thread-id) (esc (:op request)) (esc (:store-id request))
            (esc (:rule v)) (esc (:detail v)))))

(defn- clean-base-disposition
  "The base disposition `merchandiseops.phase/verdict->disposition` produces
  for a GOVERNOR-CLEAN proposal of `op`: `:escalate` when the op is in the
  live `merchandiseops.governor/always-escalate-ops` set (the governor's own
  `high-stakes?` fires regardless of confidence), otherwise `:commit`. Read
  from the live var, never hand-listed."
  [op]
  (if (contains? governor/always-escalate-ops op) :escalate :commit))

(defn- phase-cell [ph op]
  (let [{:keys [disposition reason]} (phase/gate ph {:op op} (clean-base-disposition op))]
    (str (case disposition
           :commit "<span class=\"ok\">auto-commit</span>"
           :escalate "<span class=\"warn\">human approval</span>"
           :hold "<span class=\"critical\">hold</span>"
           (esc disposition))
         (when reason (str " <code>" (esc reason) "</code>")))))

(defn- phase-matrix-row [ph ops]
  (let [{:keys [label]} (get phase/phases ph)]
    (format "        <tr><td>%s</td><td>%s</td>%s</tr>"
            ph (esc label)
            (str/join (map #(str "<td>" (phase-cell ph %) "</td>") ops)))))

(defn- coordination-row [{:keys [op store-id payload]}]
  (format "        <tr><td><code>%s</code></td><td><code>%s</code></td><td>%s</td><td><code>%s</code></td></tr>"
          (esc op) (esc store-id)
          (if-let [by (:approved-by payload)]
            (str "<span class=\"ok\">" (esc by) "</span>")
            "<span class=\"muted\">auto-commit</span>")
          (edn (dissoc payload :store-id :approved-by))))

(defn- basis-cell [basis]
  (if (seq basis)
    (str/join ", " (map #(str "<code>" (esc (if (keyword? %) (str %) %)) "</code>") basis))
    "&mdash;"))

(defn- ledger-row [{:keys [t op store-id disposition basis confidence summary]}]
  (format "        <tr><td><code>%s</code></td><td><code>%s</code></td><td><code>%s</code></td><td>%s</td><td>%s</td><td>%s</td><td>%s</td></tr>"
          (esc t) (esc op) (esc store-id)
          (case disposition
            :commit "<span class=\"ok\">commit</span>"
            :hold "<span class=\"critical\">hold</span>"
            (dash disposition))
          (basis-cell basis)
          (if (some? confidence) (esc confidence) "&mdash;")
          (dash summary)))


(defn- store-day-tick-row [{:keys [i thread-id op disposition final-disposition hard? approved-by violations]}]
  (format "        <tr><td>%s</td><td><code>%s</code></td><td><code>%s</code></td><td>%s</td><td>%s</td><td>%s</td><td>%s</td></tr>"
          (esc i) (esc thread-id) (esc op)
          (case disposition
            :commit "<span class=\"ok\">commit</span>"
            :hold "<span class=\"critical\">hold</span>"
            :escalate "<span class=\"warn\">escalate</span>"
            (esc disposition))
          (if hard? "<span class=\"critical\">HARD</span>" "<span class=\"ok\">no</span>")
          (case final-disposition
            :commit "<span class=\"ok\">commit</span>"
            :hold "<span class=\"critical\">hold</span>"
            :escalate "<span class=\"warn\">escalate</span>"
            (esc final-disposition))
          (cond
            approved-by (str "<span class=\"ok\">" (esc approved-by) "</span>")
            (seq violations) (str/join ", " (map #(str "<code>" (esc (:rule %)) "</code>") violations))
            :else "&mdash;")))

(defn- cold-storage-row [[unit-id {:keys [storage-temp-min-c storage-temp-max-c]}]]
  (format "        <tr><td><code>%s</code></td><td>%s</td><td>%s</td></tr>"
          (esc unit-id) (esc storage-temp-min-c) (esc storage-temp-max-c)))

;; ----------------------------- document -----------------------------

(defn render
  "Renders the whole operator console from `{:db .. :runs ..}` as returned
  by `run-demo!` (or any other REAL scenario)."
  [{:keys [db runs store-day held-day cashup-day]}]
  (let [ledger (vec (store/ledger db))
        coord-log (vec (store/coordination-log db))
        ops (vec (sort-by name governor/allowed-ops))
        phase-keys (vec (sort (keys phase/phases)))
        held-runs (filter #(seq (get-in % [:state :verdict :violations])) runs)]
    (str
     "<!DOCTYPE html>\n"
     "<html lang=\"en\"><head><meta charset=\"utf-8\">"
     "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1, viewport-fit=cover\">"
     "<title>cloud-itonami-isic-4719 &middot; general-merchandise retail operations</title><style>"
     (jp-go-dds.skin/dds+skin)
     "</style></head><body>\n"
     "<header class=\"bar\">\n"
     "  <h1>Other retail sale in non-specialized stores (ISIC 4719) — Operator Console</h1>\n"
     "  <span class=\"badge\">read-only sample · governor-gated · coordination only · never prices, never a loss-prevention enforcement action</span>\n"
     "</header>\n"
     "<main>\n"

     "  <section class=\"card\">\n"
     "    <h2>How this page is produced</h2>\n"
     "    <p>Every id, number, disposition, escalation reason and hold detail below was produced by <strong>actually running this repo's actor</strong> at build time: <code>merchandiseops.operation</code> (a langgraph StateGraph) → <code>merchandiseops.advisor</code> → <code>merchandiseops.governor</code> → <code>merchandiseops.phase</code> → <code>merchandiseops.store</code>. Regenerate with <code>clojure -M:dev:render-html</code>. The page is deterministic: two consecutive runs against the same seed are byte-identical.</p>\n"
     "    <p class=\"muted\">The advisor is this repo's deterministic offline mock (<code>merchandiseops.advisor/mock-advisor</code>), which is what makes the run reproducible. In production the same proposal shape comes from a real LLM and is censored by exactly the same governor.</p>\n"
     "  </section>\n"


     "  <section class=\"card\">\n"
     "    <h2>Store day (Andon-shaped outer loop)</h2>\n"
     "    <p>Produced by actually running <code>merchandiseops.storeday/run-day</code> against the same seeded store. Same five ops as the rest of this actor — roster, hire request, inbound, sales, restock, concern, and an optional cash-up count. A till variance reuses the loss-prevention flag. No corporate card, no till deposit, no employment contract, no detention.</p>\n"
     "    <p class=\"muted\">Happy day <code>"
     (esc (:store-id store-day))
     "</code> on <code>"
     (esc (:date store-day))
     "</code>: held="
     (esc (:held-count store-day))
     " escalated="
     (esc (:escalated-count store-day))
     " committed="
     (esc (:committed-count store-day))
     ". Unverified day <code>"
     (esc (:store-id held-day))
     "</code> held="
     (esc (:held-count held-day))
     ". Cash-up day status="
     (esc (get-in cashup-day [:cash-up :status]))
     " escalated="
     (esc (:escalated-count cashup-day))
     ".</p>\n"
     "    <table>\n"
     "      <thead><tr><th>#</th><th>Thread</th><th>Op</th><th>First disposition</th><th>HARD?</th><th>Final</th><th>Approver / rule</th></tr></thead>\n"
     "      <tbody>\n"
     (str/join "\n" (map store-day-tick-row (concat (:ticks store-day) (:ticks held-day) (:ticks cashup-day)))) "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"

     "  <section class=\"card\">\n"
     "    <h2>Store directory (SSoT)</h2>\n"
     "    <p class=\"muted\">Read from <code>merchandiseops.store/all-store-records</code>. The last column re-states the two fields <code>merchandiseops.governor</code>'s <code>store-unverified-violations</code> reads — ground truth from the store record, never the proposal's own claim.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Store</th><th>Name</th><th>Registered?</th><th>Verified?</th><th>Governor gate</th></tr></thead>\n"
     "      <tbody>\n"
     (str/join "\n" (map store-row (store/all-store-records db))) "\n"
     "      </tbody>\n"
     "    </table>\n"
     "    <h3>Vendor directory (supply-order counterparties)</h3>\n"
     "    <p class=\"muted\">Read from <code>merchandiseops.store/all-vendor-records</code>. <code>vendor-unverified-violations</code> applies the same ground-truth discipline to the supply-chain counterparty named by a <code>:coordinate-supply-order</code> proposal.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Vendor</th><th>Name</th><th>Registered?</th><th>Verified?</th><th>Governor gate</th></tr></thead>\n"
     "      <tbody>\n"
     (str/join "\n" (map vendor-row (store/all-vendor-records db))) "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"

     "  <section class=\"card\">\n"
     "    <h2>Requests submitted this run</h2>\n"
     "    <p class=\"muted\">The verbatim input each thread was given, plus the <code>:effect</code> the advisor actually drafted. <code>:effect</code> must always be <code>:propose</code>; <code>s1-direct-actuation</code> is driven by a deliberately misbehaving advisor that claims <code>:commit</code> instead.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Thread</th><th>Phase</th><th>Op</th><th>Store</th><th>Request (minus op/store-id)</th><th>Advisor :effect</th></tr></thead>\n"
     "      <tbody>\n"
     (str/join "\n" (map request-row runs)) "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"

     "  <section class=\"card\">\n"
     "    <h2>Governor dispositions this run</h2>\n"
     "    <p class=\"muted\">Confidence, governor verdict, post-phase-gate disposition, escalation reason, human approval and final disposition are all read off the state each <code>langgraph.graph/run*</code> call really returned. <code>run status</code> is langgraph's own status for the thread.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Thread</th><th>Op</th><th>Store</th><th>Confidence</th><th>Governor</th><th>After phase gate</th><th>Escalation reason</th><th>Human approval</th><th>Final</th><th>Run status</th></tr></thead>\n"
     "      <tbody>\n"
     (str/join "\n" (map disposition-row runs)) "\n"
     "      </tbody>\n"
     "    </table>\n"
     "    <p class=\"muted\">Note on the escalation-reason keyword: the actor labels <em>every</em> governor-side <code>high-stakes?</code> escalation <code>:always-escalate</code>, so the high-cost supply order <code>s1-supply-high</code> carries the same reason code as the always-escalating loss-prevention flag even though it was the cost threshold that fired. That is the actor's own output, reproduced verbatim rather than relabelled here.</p>\n"
     "  </section>\n"

     "  <section class=\"card\">\n"
     "    <h2>HARD holds this run</h2>\n"
     "    <p class=\"muted\">Permanent, un-overridable blocks. The graph routes a HARD hold straight to <code>:hold</code> — it is never offered to a human at all, so no approval can rescue it. Rule and detail are the governor's own violation maps, verbatim.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Thread</th><th>Op</th><th>Store</th><th>Rule</th><th>Governor detail (verbatim)</th></tr></thead>\n"
     "      <tbody>\n"
     (str/join "\n" (hold-rows held-runs)) "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"

     "  <section class=\"card\">\n"
     "    <h2>Governor contract (live values)</h2>\n"
     "    <p class=\"muted\">Read at build time from the vars themselves, not transcribed.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Var</th><th>Value</th></tr></thead>\n"
     "      <tbody>\n"
     "        <tr><td><code>governor/allowed-ops</code> (closed allowlist)</td><td>" (kw-list governor/allowed-ops) "</td></tr>\n"
     "        <tr><td><code>governor/always-escalate-ops</code></td><td>" (kw-list governor/always-escalate-ops) "</td></tr>\n"
     "        <tr><td><code>governor/confidence-floor</code></td><td><code>" (esc governor/confidence-floor) "</code></td></tr>\n"
     "        <tr><td><code>governor/supply-cost-threshold</code></td><td><code>" (esc governor/supply-cost-threshold) "</code> — a <code>:coordinate-supply-order</code> above this always needs human sign-off</td></tr>\n"
     "      </tbody>\n"
     "    </table>\n"
     "    <h3>Cold-storage reference bands</h3>\n"
     "    <p class=\"muted\"><code>governor/cold-storage-requirements</code> — this store's own equipment. A delivery carrying both an upstream <code>:handoff</code> and a <code>:storage-unit-id</code> is HARD-held unless the handoff's declared cold-chain window overlaps that unit's band.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Storage unit</th><th>min °C</th><th>max °C</th></tr></thead>\n"
     "      <tbody>\n"
     (str/join "\n" (map cold-storage-row (sort-by (comp pr-str key) governor/cold-storage-requirements))) "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"

     "  <section class=\"card\">\n"
     "    <h2>Phase gate matrix</h2>\n"
     "    <p class=\"muted\">Computed at build time by calling <code>merchandiseops.phase/gate</code> for every (phase, op) pair, with the base disposition a governor-clean proposal would produce — <code>:escalate</code> for an op in the live <code>governor/always-escalate-ops</code> set, otherwise <code>:commit</code>. Value-dependent gates (a supply order above the cost threshold; a confidence below the floor; any HARD violation) are not expressible in an op-level table and always win over this matrix — see the run above.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Phase</th><th>Label</th>"
     (str/join (map #(str "<th><code>" (esc %) "</code></th>") ops))
     "</tr></thead>\n"
     "      <tbody>\n"
     (str/join "\n" (map #(phase-matrix-row % ops) phase-keys)) "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"

     "  <section class=\"card\">\n"
     "    <h2>Committed coordination log (SSoT)</h2>\n"
     "    <p class=\"muted\">From <code>merchandiseops.store/coordination-log</code> — only the <code>:commit</code> node ever writes here. Rows carrying an approver were escalated first; <code>:approved-by</code> is stamped into the payload by the actor itself.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Op</th><th>Store</th><th>Approved by</th><th>Payload (minus store-id/approved-by)</th></tr></thead>\n"
     "      <tbody>\n"
     (str/join "\n" (map coordination-row coord-log)) "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"

     "  <section class=\"card\">\n"
     "    <h2>Audit ledger (append-only)</h2>\n"
     "    <p class=\"muted\">From <code>merchandiseops.store/ledger</code>. Commits carry the advisor's <code>:cites</code> as basis; holds carry the violated rule names. Escalation and approval events live in the run's audit trail rather than the ledger — this actor writes only commits and holds to the immutable log.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Fact</th><th>Op</th><th>Store</th><th>Disposition</th><th>Basis</th><th>Confidence</th><th>Summary</th></tr></thead>\n"
     "      <tbody>\n"
     (str/join "\n" (map ledger-row ledger)) "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"
     "</main>\n"
     "<footer>\n"
     "  <p>Generated by <code>merchandiseops.render-html</code> (<code>clojure -M:dev:render-html</code>) from a fresh <code>merchandiseops.store/seed-db</code>. Sample data only — no real store, vendor, customer or loss-prevention record.</p>\n"
     "</footer>\n"
     "</body></html>\n")))

(defn -main [& args]
  (let [out (or (first args) "docs/samples/operator-console.html")
        {:keys [db runs store-day held-day cashup-day] :as result} (run-demo!)
        html (render result)
        run-holds (count (filter #(seq (get-in % [:state :verdict :violations])) runs))
        cashup-extra (filter #(get-in % [:request :patch :cash-up-discrepancy?])
                             (:ticks cashup-day))]
    (when (zero? run-holds)
      (throw (ex-info "Refusing to write a pass: scenario produced 0 HARD holds" {:runs (count runs)})))
    (when (< (:escalated-count store-day) 2)
      (throw (ex-info "Refusing to write a pass: store-day must escalate hire and concern"
                      {:escalated-count (:escalated-count store-day)})))
    (when (zero? (:held-count held-day))
      (throw (ex-info "Refusing to write a pass: unverified store-day produced 0 HARD holds"
                      {:held-day held-day})))
    (when (or (not= :discrepancy (get-in cashup-day [:cash-up :status]))
              (empty? cashup-extra)
              (not-every? #(= :escalate (:disposition %)) cashup-extra))
      (throw (ex-info "Refusing to write a pass: cash-up discrepancy must escalate via existing concern op"
                      {:cash-up (:cash-up cashup-day)
                       :extra (count cashup-extra)})))
    (.mkdirs (.getParentFile (java.io.File. ^String out)))
    (spit out html)
    (println "wrote" out
             "-" (count runs) "actor runs,"
             (count (store/ledger db)) "ledger facts,"
             (count (store/coordination-log db)) "committed coordination records,"
             run-holds "HARD holds,"
             (:escalated-count store-day) "store-day escalations,"
             (:held-count held-day) "unverified-day holds,"
             (get-in cashup-day [:cash-up :status]) "cash-up")))

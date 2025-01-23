(ns ICA2
  (:import [java.time LocalDateTime]
           [java.time.format DateTimeFormatter]))

;; ---------------------------------------------------------
;; 1. Data about cities, trucks, and daily routes, etc.
;; ---------------------------------------------------------
(def cities
  (atom {:warsaw  {:min-val 30 :max-val 500 :current 450}
         :krakow  {:min-val 100 :max-val 100 :current 0}
         :hamburg {:min-val 60 :max-val 100 :current 80}
         :munich  {:min-val 50 :max-val 150 :current 10}
         :brno    {:min-val 70 :max-val 80 :current 0}
         :prague  {:min-val 70 :max-val 80 :current 50}
         :berlin  {:min-val 50 :max-val 500 :current 150}}))

(def graph
  {:warsaw  [[:krakow 100] [:hamburg 700] [:munich 900] [:brno 600] [:prague 500] [:berlin 300]]
   :krakow  [[:warsaw 100] [:hamburg 800] [:munich 600] [:brno 700] [:prague 600] [:berlin 400]]
   :hamburg [[:warsaw 700] [:krakow 800] [:munich 200] [:brno 400] [:prague 300] [:berlin 100]]
   :munich  [[:warsaw 900] [:krakow 600] [:hamburg 200] [:brno 400] [:prague 300] [:berlin 100]]
   :brno    [[:warsaw 600] [:krakow 700] [:hamburg 400] [:munich 400] [:prague 100] [:berlin 300]]
   :prague  [[:warsaw 500] [:krakow 600] [:hamburg 300] [:munich 300] [:brno 100] [:berlin 200]]
   :berlin  [[:warsaw 300] [:krakow 400] [:hamburg 100] [:munich 100] [:brno 300] [:prague 200]]})


(def trucks
  (atom {:truck1 {:capacity 50 :load 0 :location :warsaw}
         :truck2 {:capacity 50 :load 0 :location :berlin}}))


(def daily-routes
  {1 [[:berlin :brno 50] [:warsaw :krakow 20]]
   2 [[:brno :prague 15]]
   3 [[:berlin :hamburg 40]]})


;; ---------------------------------------------------------
;; 2. Function A-star for getting the shortest route
;; ---------------------------------------------------------
(defn a-star [start goal]
  (let [open-set   (atom {start {:cost 0 :path [start]}})
        closed-set (atom #{})]
    (loop []
      (if (empty? @open-set)
        nil
        (let [[current {:keys [cost path]}]
              (apply min-key #(get-in % [1 :cost]) @open-set)]
          (if (= current goal)
            path
            (do
              (swap! open-set dissoc current)
              (swap! closed-set conj current)
              (doseq [[neighbor distance] (get graph current)]
                (when (and (not (contains? @closed-set neighbor))
                           (or (not (contains? @open-set neighbor))
                               (< (+ cost distance)
                                  (get-in @open-set [neighbor :cost]))))
                  (swap! open-set assoc neighbor
                         {:cost (+ cost distance)
                          :path (conj path neighbor)})))
              (recur))))))))

;; ---------------------------------------------------------
;; 3. pick the truck
;; ---------------------------------------------------------
(defn pick-truck [from amount]
  (let [valid-trucks
        (filter (fn [[tid {:keys [capacity load location]}]]
                  (and (>= (- capacity load) amount)
                       (not= location nil)))
                @trucks)]
    (when (seq valid-trucks)
      (->> valid-trucks
           (sort-by (fn [[_ {:keys [location]}]]
                      (if (= location from) 0
                                            (count (a-star location from)))))
           first))))

;; ---------------------------------------------------------
;; 4. Deliveries with alternative routes
;; ---------------------------------------------------------
(defn find-closest-alternative [from to remainder]
  (->> @cities
       (filter (fn [[city {:keys [current max-val]}]]
                 (and (not= city to)
                      (not= city from)
                      (< current max-val)
                      (>= (- max-val current) remainder))))
       (sort-by (fn [[city _]] (count (a-star from city))))
       first))

(defn make-delivery [truck-id from to amount]
  (let [path (a-star from to)]
    (if path
      (do
        (swap! trucks assoc-in [truck-id :location] from)
        (swap! cities update-in [from :current] - amount)
        (swap! trucks update-in [truck-id :load] + amount)
        (swap! trucks assoc-in [truck-id :location] to)
        (swap! trucks update-in [truck-id :load] - amount)
        (swap! cities update-in [to :current] + amount)
        (println (str "Truck -> " (name truck-id)
                      " delivered " amount " cans from "
                      (name from) " to " (name to)
                      " via path " path)))
      (println (str "No path found from " (name from) " to " (name to))))))

(defn execute-delivery [truck-id from to amount]
  (let [dest-max (get-in @cities [to :max-val])
        dest-current (get-in @cities [to :current])
        available (- dest-max dest-current)]
    (if (<= amount available)
      (make-delivery truck-id from to amount)
      (do
        (when (> available 0)
          (make-delivery truck-id from to available))
        (let [remainder (- amount available)]
          (println (str "Destination " (name to)
                        " reached its maximum capacity. Remainder of "
                        remainder " cans must be delivered elsewhere."))
          (if-let [[alt-city alt-data] (find-closest-alternative from to remainder)]
            (let [alt-available (- (get alt-data :max-val) (get alt-data :current))
                  deliver-amt (min remainder alt-available)]
              (if-let [[t-id _] (pick-truck from deliver-amt)]
                (make-delivery t-id from alt-city deliver-amt)
                (println "No truck available to deliver the remaining cans.")))
            (println "No alternative destination available for remaining cans.")))))))

;; ---------------------------------------------------------
;; 5. Execute daily routes and comply with minimums.
;; ---------------------------------------------------------
(defn execute-daily-routes [day]
  (doseq [[from to amount] (get daily-routes day)]
    (if-let [[t-id _] (pick-truck from amount)]
      (if (>= (get-in @cities [from :current]) amount)
        (execute-delivery t-id from to amount)
        (println "Not enough stock in" (name from) "for" amount "cans"))
      (println "No truck available for" amount "cans from" (name from) "to" (name to)))))

(defn meet-minimums []
  (doseq [[city {:keys [current min-val max-val]}] @cities]
    (when (< current min-val)
      (let [deficit (- min-val current)]
        (println (str "City " (name city) " needs " deficit " more cans to reach min " min-val))
        (loop [remaining deficit]
          (when (pos? remaining)
            (let [batch (min remaining 50)
                  [t-id _] (pick-truck :warsaw batch)]
              (if t-id
                (let [warsaw-stock (get-in @cities [:warsaw :current])
                      dest-space   (max 0 (- max-val (get-in @cities [city :current])))]
                  (if (and (>= warsaw-stock batch)
                           (pos? dest-space))
                    (do
                      (let [final-amount (min batch warsaw-stock dest-space)]
                        (make-delivery t-id :warsaw city final-amount)
                        (recur (- remaining final-amount))))
                    (recur 0)))
                (do
                  (println "No truck available for partial delivery.")
                  (recur 0))))))))))

;; ---------------------------------------------------------
;; 6. Principal function - Main
;; ---------------------------------------------------------
(defn main []
  (println "Simulation starting...")
  (dotimes [day 3]
    (println (str "\nDay " (inc day) ":"))
    (execute-daily-routes (inc day))
    (meet-minimums))
  (println "\nFinal status of all cities:")
  (doseq [[city {:keys [current min-val max-val]}] @cities]
    (println (str (name city) ": " current
                  " cans (Min: " min-val ", Max: " max-val ")"))))

(main)

(ns ICA2
  "This namespace simulates a logistics system for managing truck deliveries
   between cities over three trading days. The system calculates shortest paths
   using the A* algorithm, ensures cities meet minimum stock requirements,
   and reroutes excess stock when necessary."
  (:import [java.time LocalDateTime]
           [java.time.format DateTimeFormatter]))

;; ---------------------------------------------------------
;; 1. Data about cities, trucks, and daily routes, etc.
;; ---------------------------------------------------------
(def cities
  "A map of city data, where each city has the following attributes:
   - :min-val: Minimum stock level required.
   - :max-val: Maximum stock capacity.
   - :current: Current stock level."
  (atom {:warsaw  {:min-val 30 :max-val 500 :current 450}
         :krakow  {:min-val 100 :max-val 100 :current 0}
         :hamburg {:min-val 60 :max-val 100 :current 80}
         :munich  {:min-val 50 :max-val 150 :current 10}
         :brno    {:min-val 70 :max-val 80 :current 0}
         :prague  {:min-val 70 :max-val 80 :current 50}
         :berlin  {:min-val 50 :max-val 500 :current 150}}))

(def graph
  "An adjacency list representing distances between cities.
   Each key is a city (keyword), and the value is a vector of neighboring cities
   with their respective distances."
  {:warsaw  [[:krakow 100] [:hamburg 700] [:munich 900] [:brno 600] [:prague 500] [:berlin 300]]
   :krakow  [[:warsaw 100] [:hamburg 800] [:munich 600] [:brno 700] [:prague 600] [:berlin 400]]
   :hamburg [[:warsaw 700] [:krakow 800] [:munich 200] [:brno 400] [:prague 300] [:berlin 100]]
   :munich  [[:warsaw 900] [:krakow 600] [:hamburg 200] [:brno 400] [:prague 300] [:berlin 100]]
   :brno    [[:warsaw 600] [:krakow 700] [:hamburg 400] [:munich 400] [:prague 100] [:berlin 300]]
   :prague  [[:warsaw 500] [:krakow 600] [:hamburg 300] [:munich 300] [:brno 100] [:berlin 200]]
   :berlin  [[:warsaw 300] [:krakow 400] [:hamburg 100] [:munich 100] [:brno 300] [:prague 200]]})


(def trucks
  "A map of trucks, where each truck has the following attributes:
   - :capacity: Maximum carrying capacity.
   - :load: Current load being carried.
   - :location: Current city location."
  (atom {:truck1 {:capacity 50 :load 0 :location :warsaw}
         :truck2 {:capacity 50 :load 0 :location :berlin}}))


(def daily-routes
  "A map of trading days and their associated delivery routes, where each route specifies:
   - Source city
   - Destination city
   - Quantity to be delivered."
  {1 [[:berlin :brno 50] [:warsaw :krakow 20]]
   2 [[:brno :prague 15]]
   3 [[:berlin :hamburg 40]]})


;; ---------------------------------------------------------
;; 2. Function A-star for getting the shortest route
;; ---------------------------------------------------------
(defn a-star
  "Finds the shortest path between two cities using the A* algorithm.

   Arguments:
   - `start`: Starting city (keyword, e.g., `:warsaw`).
   - `goal`: Destination city (keyword, e.g., `:krakow`).

   Returns:
   - A vector of cities (keywords) representing the shortest path, or `nil` if no path exists.

   Details:
   - Uses the `graph` adjacency list to find neighbors and distances.
   - Tracks evaluated cities with `closed-set` and cities to evaluate with `open-set`.

   Example:
   ;; Finds the shortest path from Warsaw to Krakow
   (a-star :warsaw :krakow)
   ; => [:warsaw :krakow]"
  [start goal]
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
(defn pick-truck
  "Selects the most suitable truck for a delivery from a specific city.

   Arguments:
   - `from`: The source city (keyword, e.g., `:warsaw`).
   - `amount`: The amount of stock to be delivered (integer).

   Returns:
   - A vector `[truck-id truck-data]` for the selected truck, or `nil` if no truck is available.

   Details:
   - The function first checks for trucks already located in the `from` city with sufficient capacity.
   - If no truck is available in the `from` city, it searches for nearby trucks with sufficient capacity.
   - Trucks are sorted based on their load (favoring trucks with lighter loads) and proximity to the source city.
   - The `graph` adjacency list is used to determine proximity for nearby trucks.

   Example:
   (pick-truck :warsaw 40)
   ; => [:truck1 {:capacity 50, :load 0, :location :warsaw}]"
  [from amount]
  (let [valid-trucks
        (filter (fn [[tid {:keys [capacity load location]}]]
                  (and (>= (- capacity load) amount)
                       (= location from)))
                @trucks)]
    (if (seq valid-trucks)
      (->> valid-trucks
           (sort-by (fn [[_ {:keys [load]}]] load))
           first)
      (let [nearby-trucks
            (filter (fn [[tid {:keys [capacity load location]}]]
                      (and (>= (- capacity load) amount)
                           (some (fn [[neighbor dist]] (= neighbor location))
                                 (get graph from))))
                    @trucks)]
        (when (seq nearby-trucks)
          (->> nearby-trucks
               (sort-by (fn [[tid {:keys [load location]}]]
                          [(+ load (or (some (fn [[neighbor dist]] (when (= neighbor location) dist))
                                             (get graph from))
                                       Integer/MAX_VALUE))
                           load]))
               first))))))

;; ---------------------------------------------------------
;; 4. Deliveries with alternative routes
;; ---------------------------------------------------------
(defn find-closest-alternative
  "Finds the closest city to reroute excess stock when a delivery exceeds the destination's capacity.

   Arguments:
   - `from`: The source city (keyword, e.g., `:warsaw`).
   - `to`: The original destination city (keyword, e.g., `:krakow`).
   - `remainder`: The amount of stock to be rerouted (integer).

   Returns:
   - A vector `[city city-data]` for the selected alternative city, or `nil` if no suitable city is found.

   Details:
   - Searches cities other than the source (`from`) and original destination (`to`).
   - Filters cities with available capacity (`current` < `max-val`) and enough space for the `remainder`.
   - Sorts potential cities based on the shortest path from the `from` city, calculated using the A* algorithm.
   - Returns the closest city with sufficient capacity to handle the excess stock.

   Example:
   (find-closest-alternative :warsaw :krakow 50)
   ; => [:prague {:min-val 70, :max-val 80, :current 30}]"
  [from to remainder]
  (->> @cities
       (filter (fn [[city {:keys [current max-val]}]]
                 (and (not= city to)
                      (not= city from)
                      (< current max-val)
                      (>= (- max-val current) remainder))))
       (sort-by (fn [[city _]] (count (a-star from city))))
       first))

(defn make-delivery
  "Performs a delivery of stock from a source city to a destination city using a specific truck.

   Arguments:
   - `truck-id`: The identifier of the truck performing the delivery (e.g., `:truck1`).
   - `from`: The source city (keyword, e.g., `:warsaw`).
   - `to`: The destination city (keyword, e.g., `:krakow`).
   - `amount`: The quantity of stock to be delivered (integer).

   Returns:
   - Prints a confirmation message if the delivery is successful.
   - Prints an error message if no path is found between the source and destination.

   Details:
   - Calculates the shortest delivery route using the A* algorithm.
   - Updates the truck's load and location before and after delivery.
   - Updates stock levels for both the source and destination cities.
   - Logs the delivery details, including the truck ID, the cities involved, the stock quantity, and the delivery path.

   Example:
   (make-delivery :truck1 :warsaw :krakow 50)
   ; Prints: \"Truck -> truck1 delivered 50 cans from warsaw to krakow via path [:warsaw :krakow]\""
  [truck-id from to amount]
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

(defn execute-delivery
  "Manages the delivery of stock from a source city to a destination city, handling capacity limits and remainders.

   Arguments:
   - `truck-id`: The identifier of the truck performing the delivery (e.g., `:truck1`).
   - `from`: The source city (keyword, e.g., `:warsaw`).
   - `to`: The destination city (keyword, e.g., `:krakow`).
   - `amount`: The total quantity of stock to be delivered (integer).

   Behavior:
   - If the destination city can accommodate the entire delivery:
     - Executes the delivery using `make-delivery`.
   - If the destination city's capacity is insufficient:
     - Delivers as much as possible to the destination.
     - Identifies alternative cities for the remainder of the stock using `find-closest-alternative`.
     - Reroutes the remainder to a suitable alternative city if available.
   - If no alternative city is found, logs a message stating that the remainder could not be delivered.

   Returns:
   - Logs details of the delivery process, including partial deliveries, remainders, and alternative routing.

   Example:
   (execute-delivery :truck1 :warsaw :krakow 200)
   ; Logs:
   ; \"Truck -> truck1 delivered 100 cans from warsaw to krakow via path [:warsaw :krakow]\"
   ; \"Destination krakow reached its maximum capacity. Remainder of 100 cans must be delivered elsewhere.\"
   ; \"Truck -> truck1 delivered 100 cans from warsaw to munich via path [:warsaw :munich]\""
  [truck-id from to amount]
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
(defn execute-daily-routes
  "Executes all delivery routes for a given trading day.

   Arguments:
   - `day`: The trading day (integer, e.g., `1`, `2`, or `3`).

   Behavior:
   - Iterates over the delivery routes scheduled for the given day (from `daily-routes`).
   - For each route:
     - Attempts to find a suitable truck using `pick-truck`.
     - Checks if the source city has enough stock to fulfill the delivery:
       - If sufficient stock is available, executes the delivery using `execute-delivery`.
       - If stock is insufficient, logs an appropriate message.
     - If no truck is available for the route, logs a message indicating the unavailability.

   Returns:
   - Logs the outcome of each delivery attempt, including reasons for failure if applicable.

   Example:
   (execute-daily-routes 1)
   ; Logs:
   ; \"Truck -> truck1 delivered 50 cans from berlin to brno via path [:berlin :brno]\"
   ; \"Not enough stock in warsaw for 30 cans\"
   ; \"No truck available for 40 cans from hamburg to prague\""
  [day]
  (doseq [[from to amount] (get daily-routes day)]
    (if-let [[t-id _] (pick-truck from amount)]
      (if (>= (get-in @cities [from :current]) amount)
        (execute-delivery t-id from to amount)
        (println "Not enough stock in" (name from) "for" amount "cans"))
      (println "No truck available for" amount "cans from" (name from) "to" (name to)))))

(defn meet-minimums
  "Ensures all cities meet their minimum stock levels by replenishing inventory.

   Behavior:
   - Iterates over all cities in `cities`.
   - For each city with a stock level below its minimum (`:min-val`):
     - Calculates the stock deficit (`:min-val - :current`).
     - Attempts to replenish the deficit using trucks starting from Warsaw.
     - Delivers stock in batches of up to 50 units, while ensuring:
       - The truck's capacity is not exceeded.
       - The destination city's maximum capacity (`:max-val`) is respected.
   - Logs progress for each city, including:
     - The deficit amount.
     - Deliveries made.
     - Any failures due to insufficient trucks or space.

   Arguments:
   None.

   Returns:
   - Logs details of replenishment for each city requiring stock.
   - Updates the stock levels in `cities` atom based on successful deliveries.

   Example:
   (meet-minimums)
   ; Logs:
   ; \"City krakow needs 80 more cans to reach min 100\"
   ; \"Truck -> truck1 delivered 50 cans from warsaw to krakow via path [:warsaw :krakow]\"
   ; \"Truck -> truck1 delivered 30 cans from warsaw to krakow via path [:warsaw :krakow]\""
  []
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
(defn main
  "Executes the entire simulation of the logistics system over three trading days.

   Behavior:
   - Simulates three trading days, processing daily routes and stock replenishment:
     - For each day:
       - Executes predefined delivery routes via `execute-daily-routes`.
       - Ensures all cities meet their minimum stock levels via `meet-minimums`.
   - At the end of the simulation:
     - Prints the final stock levels of all cities, along with their minimum and maximum capacities.

   Arguments:
   None.

   Returns:
   - Logs the simulation steps and outcomes to the console:
     - Daily delivery routes and their details.
     - Messages for replenishment operations, including stock deficits and deliveries.
     - Final stock levels for all cities after the simulation.

   Example:
   (main)
   ; Logs:
   ; \"Simulation starting...\"
   ; \"Day 1:\"
   ; \"Truck -> truck1 delivered 50 cans from warsaw to krakow via path [:warsaw :krakow]\"
   ; ...
   ; \"Final status of all cities:\"
   ; \"warsaw: 255 cans (Min: 30, Max: 500)\""
  []
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

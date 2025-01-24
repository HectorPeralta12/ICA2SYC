# Logistics System Simulation

## Overview

This project simulates a logistics system for managing truck deliveries between cities over three trading days. The system utilizes the **A* algorithm** to calculate the shortest paths between cities, ensures cities meet minimum stock levels, and reroutes excess stock when necessary.

### Features
- **Shortest Path Calculation**: Utilizes the A* algorithm for optimal route planning.
- **Truck Management**: Assigns trucks based on availability, load capacity, and proximity.
- **Stock Control**: Ensures cities maintain minimum stock levels and reroutes excess stock to alternative destinations.
- **Daily Operations**: Simulates daily delivery routes and manages stock replenishments.

---

## Key Components

### Data Structures

1. **Cities**
   - Attributes:
     - `:min-val`: Minimum stock level.
     - `:max-val`: Maximum capacity.
     - `:current`: Current stock.
   - Example:
     ```clojure
     {:warsaw {:min-val 30 :max-val 500 :current 450}}
     ```

2. **Graph**
   - Adjacency list representing connections and distances between cities.
   - Example:
     ```clojure
     {:warsaw [[:krakow 100] [:berlin 300]]}
     ```

3. **Trucks**
   - Attributes:
     - `:capacity`: Maximum load capacity.
     - `:load`: Current load.
     - `:location`: Current city.
   - Example:
     ```clojure
     {:truck1 {:capacity 50 :load 0 :location :warsaw}}
     ```

4. **Daily Routes**
   - Scheduled delivery routes for each trading day.
   - Example:
     ```clojure
     {1 [[:berlin :brno 50] [:warsaw :krakow 20]]}
     ```

---

## Functions

### Core Functions

1. **A-Star Algorithm (`a-star`)**
   - Calculates the shortest path between two cities.
   - Example:
     ```clojure
     (a-star :warsaw :krakow)
     ; => [:warsaw :krakow]
     ```

2. **Truck Selection (`pick-truck`)**
   - Finds the most suitable truck based on availability, capacity, and proximity.
   - Example:
     ```clojure
     (pick-truck :warsaw 40)
     ; => [:truck1 {:capacity 50, :load 0, :location :warsaw}]
     ```

3. **Deliveries (`make-delivery`)**
   - Executes a delivery between two cities and updates stock/truck data.
   - Example:
     ```clojure
     (make-delivery :truck1 :warsaw :krakow 50)
     ```

4. **Alternative Routes (`find-closest-alternative`)**
   - Reroutes excess stock to an alternative destination if needed.
   - Example:
     ```clojure
     (find-closest-alternative :warsaw :krakow 50)
     ```

5. **Daily Routes Execution (`execute-daily-routes`)**
   - Processes all deliveries scheduled for a specific day.
   - Example:
     ```clojure
     (execute-daily-routes 1)
     ```

6. **Stock Replenishment (`meet-minimums`)**
   - Ensures all cities meet their minimum stock levels by delivering inventory from Warsaw.
   - Example:
     ```clojure
     (meet-minimums)
     ```

7. **Simulation (`main`)**
   - Simulates the logistics system over three trading days.
   - Example:
     ```clojure
     (main)
     ```

---

## How to Run

1. Ensure you have a Clojure runtime environment installed.
2. Copy the code into a file (e.g., `logistics_system.clj`).
3. Run the simulation:
   ```bash
   clojure logistics_system.clj

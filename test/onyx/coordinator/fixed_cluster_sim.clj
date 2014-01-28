(ns onyx.coordinator.fixed-cluster-sim
  (:require [clojure.test :refer :all]
            [clojure.core.async :refer [chan <!! >!! tap timeout]]
            [com.stuartsierra.component :as component]
            [simulant.sim :as sim]
            [simulant.util :as u]
            [datomic.api :as d]
            [onyx.system :as s]
            [onyx.coordinator.extensions :as extensions]
            [onyx.coordinator.log.datomic :as datomic]
            [onyx.coordinator.sim-test-utils :as sim-utils]
            [incanter.core :refer [view]]
            [incanter.charts :refer [line-chart]]))

(def cluster (atom {}))

(defn create-fixed-cluster-test [conn model test]
  (u/require-keys test :db/id :test/duration)
  (-> @(d/transact conn [(assoc test
                           :test/type :test.type/fixed-cluster
                           :model/_tests (u/e model))])
      (u/tx-ent (:db/id test))))

(defmethod sim/create-test :model.type/fixed-cluster
  [conn model test]
  (let [test (create-fixed-cluster-test conn model test)]
    (d/entity (d/db conn) (u/e test))))

(defmethod sim/create-sim :test.type/fixed-cluster
  [sim-conn test sim]
  (-> @(d/transact sim-conn (sim/construct-basic-sim test sim))
      (u/tx-ent (:db/id sim))))

(def sim-uri (str "datomic:mem://" (d/squuid)))

(def sim-conn (sim-utils/reset-conn sim-uri))

(sim-utils/load-schema sim-conn "simulant/schema.edn")

(sim-utils/load-schema sim-conn "simulant/coordinator-sim.edn")

(def system (s/onyx-system {:sync :zookeeper :queue :hornetq :revoke-delay 500000}))

(def components (alter-var-root #'system component/start))

(def coordinator (:coordinator components))

(def log (:log components))

(def tx-queue (d/tx-report-queue (:conn log)))

(def offer-spy (chan 10000))

(def catalog
  [{:onyx/name :in
    :onyx/direction :input
    :onyx/consumption :sequential
    :onyx/type :queue
    :onyx/medium :hornetq
    :hornetq/queue-name "in-queue"}
   {:onyx/name :inc
    :onyx/type :transformer
    :onyx/consumption :sequential}
   {:onyx/name :out
    :onyx/direction :output
    :onyx/consumption :sequential
    :onyx/type :queue
    :onyx/medium :hornetq
    :hornetq/queue-name "out-queue"}])

(def workflow {:in {:inc :out}})

(def n-jobs 3)

(def n-peers 10)

(def tasks-per-job 3)

(tap (:offer-mult coordinator) offer-spy)

(doseq [_ (range n-jobs)]
  (>!! (:planning-ch-head coordinator) {:catalog catalog :workflow workflow}))

(doseq [_ (range n-jobs)]
  (<!! offer-spy))

(def fixed-model-id (d/tempid :model))

(def fixed-cluster-model-data
  [{:db/id fixed-model-id
    :model/type :model.type/fixed-cluster
    :model/n-peers n-peers
    :model/mean-ack-time 250
    :model/mean-completion-time 500}])

(def fixed-cluster-model
  (-> @(d/transact sim-conn fixed-cluster-model-data)
      (u/tx-ent fixed-model-id)))

(sim-utils/create-peers! fixed-cluster-model components cluster)

(def fixed-cluster-test
  (sim/create-test sim-conn
                   fixed-cluster-model
                   {:db/id (d/tempid :test)
                    :test/duration 15000}))

(def fixed-cluster-sim
  (sim/create-sim sim-conn
                  fixed-cluster-test
                  {:db/id (d/tempid :sim)
                   :sim/systemURI (str "datomic:mem://" (d/squuid))
                   :sim/processCount 1}))

(sim/create-fixed-clock sim-conn fixed-cluster-sim {:clock/multiplier 1})

(sim/create-action-log sim-conn fixed-cluster-sim)

(future
  (time (mapv (fn [prun] @(:runner prun))
              (->> #(sim/run-sim-process sim-uri (:db/id fixed-cluster-sim))
                   (repeatedly (:sim/processCount fixed-cluster-sim))
                   (into [])))))

(testing "All tasks complete"
  (loop []
    (let [db (:db-after (.take tx-queue))
          query '[:find (count ?task) :where [?task :task/complete? true]]
          result (ffirst (d/q query db))]
      (prn result)
      (when-not (= result (* n-jobs tasks-per-job))
        (recur)))))

(def sim-db (d/db sim-conn))

(def result-db (d/db (:conn log)))

(deftest test-small-cluster-few-jobs
  (testing "No tasks are left incomplete"
    (sim-utils/task-completeness result-db))

  (testing "No sequential task ever had more than 1 peer"
    (sim-utils/task-safety result-db)))

(def insts
  (->> (-> '[:find ?inst :where
             [_ :peer/status _ ?tx]
             [?tx :db/txInstant ?inst]]
           (d/q (d/history result-db)))
       (map first)
       (sort)))

(def dt-and-peers
  (map (fn [tx]
         (let [db (d/as-of result-db tx)]
           (->> (d/q '[:find (count ?p) :where [?p :peer/status]] db)
                (map first)
                (concat [tx]))))
       insts))

(view (line-chart
       (map first dt-and-peers)
       (map second dt-and-peers)
       :x-label "Time"
       :y-label "Peers"))

(alter-var-root #'system component/stop)

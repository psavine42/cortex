(ns cortex.verify.nn.train
  (:require
    [clojure.test :refer :all]
    [clojure.pprint :as pprint]
    [clojure.core.matrix :as m]
    [think.resource.core :as resource]
    [cortex.dataset :as ds]
    [cortex.loss :as loss]
    [cortex.optimize :as opt]
    [cortex.optimize.adam :as adam]
    [cortex.optimize.adadelta :as adadelta]
    [cortex.nn.layers :as layers]
    [cortex.nn.execute :as execute]
    [cortex.nn.traverse :as traverse]
    [cortex.nn.network :as network]
    [cortex.verify.nn.data
     :refer [CORN-DATA CORN-LABELS CORN-DATASET
             mnist-training-dataset*
             mnist-test-dataset*]
     :as data]))


(def MNIST-NETWORK
  [(layers/input 28 28 1 :id :data)
   (layers/convolutional 5 0 1 20 :weights {:l2-regularization 1e-3})
   (layers/max-pooling 2 0 2)
   (layers/dropout 0.9)
   (layers/relu)
   (layers/local-response-normalization)
   (layers/convolutional 5 0 1 50)
   (layers/max-pooling 2 0 2)
   (layers/batch-normalization :l1-regularization 1e-4)
   (layers/linear 500 :l2-max-constraint 4.0)
   (layers/relu :center-loss {:label-indexes {:stream :label}
                              :label-inverse-counts {:stream :label}
                              :labels {:stream :label}
                              :alpha 0.9
                              :lambda 1e-4})
   (layers/linear 10)
   (layers/softmax :id :label)])

(defn min-index
  "Returns the index of the minimum value in a vector."
  [v]
  (let [length (count v)]
    (loop [minimum (v 0)
           min-index 0
           i 1]
      (if (< i length)
        (let [value (v i)]
          (if (< value minimum)
            (recur value i (inc i))
            (recur minimum min-index (inc i))))
        min-index))))


(defn max-index
  "Returns the index of the maximum value in a vector."
  [v]
  (let [length (count v)]
    (loop [maximum (v 0)
           max-index 0
           i 1]
      (if (< i length)
        (let [value (v i)]
          (if (> value maximum)
            (recur value i (inc i))
            (recur maximum max-index (inc i))))
        max-index))))


(defn- print-layer-weights
  [network]
  (clojure.pprint/pprint (->> (get-in network [:compute-graph :buffers])
                              (map (fn [[k v]]
                                     [k
                                      (vec (take 10 (m/eseq (get v :buffer))))]))
                              (into {})))
  network)


(defn corn-network
  []
  (->> [(layers/input 2 1 1 :id :data)
        (layers/linear 1 :id :label)]
       (network/linear-network)))


(defn regression-error
  [as bs]
  (reduce +
    (map (fn [[a] [b]]
           (* (- a b) (- a b)))
        as bs)))

(defn test-corn
  [& [context]]
  (resource/with-resource-context
    ;;For creation of the cuda context once to avoid repeatedly creating it.
    (when context
      ((:backend-fn context)))
   (let [dataset CORN-DATASET
         labels (map :label dataset)
         big-dataset (apply concat (repeat 2000 dataset))
         optimizer (adam/adam :alpha 0.01)
         network (corn-network)
         network (loop [network network
                        optimizer optimizer
                        epoch 0]
                   (if (> 3 epoch)
                     (let [{:keys [network optimizer]}
                           (execute/train network big-dataset
                                          :batch-size 1
                                          :context context
                                          :optimizer optimizer)
                           results (map :label (execute/run network dataset :context context))
                           err (regression-error results labels)]
                       (recur network optimizer (inc epoch)))
                     network))
         results (map :label (execute/run network dataset :batch-size 10 :context context))
         err (regression-error results labels)]
     (is (> err 0.2)))))


(defn percent=
  [a b]
  (loss/evaluate-softmax a b))


(defn train-mnist
  [& [context]]
  (resource/with-resource-context
    ;;for the creation of the main cuda and cudnn contexts if necessary.  This also does all the
    ;;dynamic compilation required thus making the loop below a bit tighter.

    ;;Without the outer resource context and initial backend creation, each of the train/run
    ;;functions below is rebuilding the entire cuda context which includes compiling kernels and
    ;;doing cuda init, neither of which is designed to be called more than once a program.
    (when context
      ((:backend-fn context)))
    (let [n-epochs 4
          training-batch-size 10
          running-batch-size 100
          dataset (take 100 @mnist-training-dataset*)
          test-dataset (take 100 @mnist-test-dataset*)
          test-labels (map :label test-dataset)
          network (network/linear-network MNIST-NETWORK)
          _ (println (format "Training MNIST network for %s epochs..." n-epochs))
          _ (network/print-layer-summary network (traverse/training-traversal network))
          [network optimizer]
          (reduce (fn [[network optimizer] epoch]
                    (let [{:keys [network optimizer]}
                          (execute/train network dataset
                                         :context context
                                         :batch-size training-batch-size
                                         :optimizer optimizer)
                          results (->> (execute/run network test-dataset
                                         :batch-size running-batch-size
                                         :context context
                                         :loss-outputs? true))
                          loss-fn (execute/execute-loss-fn network results test-dataset)
                          score (percent= (map :label results) test-labels)]
                      (println (format "Score for epoch %s: %s" (inc epoch) score))
                      (println (loss/loss-fn->table-str loss-fn))
                      [network optimizer]))
                  [network nil]
                  (range n-epochs))
          results (->> (execute/run network test-dataset
                         :batch-size running-batch-size :context context)
                       (map :label))]
      ;;Ensure the optimizer was updated
      (is (= (clojure.set/intersection #{:m :v} (set (keys optimizer)))
             #{:m :v}))
      (is (> (percent= results test-labels) 0.6)))))

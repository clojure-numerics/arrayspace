(ns arrayspace.matrix-api
  (:require
   [clojure.tools.macro :as macro]
   [arrayspace.protocols :refer :all]
   [arrayspace.core :refer [make-domain make-domain-map make-distribution]]
   [arrayspace.domain :refer [strides-of-shape element-count-of-shape
                              flatten-coords do-elements-loop shape-from-ranges]]
   [arrayspace.java-array-utils :refer [adel a== acopy ainc-long]]
   [arrayspace.distributions.contiguous-java-array]
   [arrayspace.distributions.contiguous-buffer]
   [arrayspace.distributions.partitioned-buffer]
   [arrayspace.types :refer [resolve-type resolve-type-from-data]]
   [clojure.core.matrix.protocols :refer :all]
   [clojure.core.matrix :refer [scalar? array? ecount]]
   [clojure.core.matrix.implementations :as imp]
   [clojure.core.matrix.impl.persistent-vector]))

(declare maybe-coerce-data make-arrayspace-matrix do-elements do-elements-indexed do-elements!)

(defn TODO []
  ;;(throw (Exception. "TODO- NOT IMPLEMENTED YET"))
  nil)

(defn debug-compare [m o]
  (let [oa (array? o)
        shape-eq (every? true? (map == (get-shape m) (get-shape o)))
        el-eq (every? true? (map == (element-seq m) (element-seq o)))]
    (println (format "EQ: FALSE: %s %s" m o))
    (println (format "o: array? %s, shape-eq: %s el-eq: %s" oa shape-eq el-eq))
    (println (format "m-shape: %s, o-shape: %s" (vec (get-shape m)) (vec (get-shape o))))
    (println (format "m-seq: %s, o-seq: %s" (vec (element-seq m)) (vec (element-seq o))))))

(defn lazy-eseq
  ([m] (lazy-eseq m (long-array (:bottom-ranges (.domain m)))))
  ([m ^longs coords]
     (let [domain (.domain m)
           bottom-ranges (longs (:bottom-ranges domain))
           top-ranges (longs (:top-ranges domain))
           shape (long-array (shape-from-ranges bottom-ranges top-ranges))
           rank (long (count shape))
           ridx (long-array (reverse (range rank)))
           last-dim (long (aget ridx 0))]
       (lazy-eseq m coords bottom-ranges top-ranges rank ridx last-dim)))
  ([m ^longs coords bottom-ranges top-ranges rank ridx last-dim]
     (let [el (.get-nd m coords)
           inc-coords (fn inc-coords []
                        (ainc-long coords last-dim)
                        (dotimes [i rank]
                          (let [dim (aget ridx i)]
                            (when (a== coords top-ranges dim)
                              (acopy coords bottom-ranges dim)
                              (when-not (zero? dim)
                                (ainc-long coords (dec dim)))))) coords)]
             (cons el (if (every? true? (map #(== %1 (dec %2)) coords top-ranges))
                        nil
                        (lazy-seq (lazy-eseq m (inc-coords)
                                             bottom-ranges top-ranges
                                             rank ridx last-dim)))))))

(defn spread
  [arglist]
  (cond
   (nil? arglist) nil
   (nil? (next arglist)) (seq (first arglist))
   :else (cons (first arglist) (spread (next arglist)))))

(defn ensure-seq [o max] 
  (if (scalar? o) (repeat max o) o))

(defn ensure-seqs [objs max] 
  (map #(ensure-seq % max) objs))
             
(defn zmap!
  "Element-wise map over all elements of one or more arrays.
   Performs in-place modification of the first array argument."
  ([f m]
    (element-map! m f))
  ([f m a]
    (element-map! m f (if (scalar? a) (repeat a) a) a))
  ([f m a & more]
     (element-map! m f a more)))


(defn wrap-ctx-fn!
  ([f]
     (fn ctx-fn [[m coords el]]
       (let [new-el (f el)]
         (.set-nd! m coords new-el)
         new-el)))
  ([f a]
     (fn ctx-fn-a [[m coords el] a]
       (let [new-el (f el a)]
         (.set-nd! m coords new-el)
         new-el)))
  ([f a more]
     (fn ctx-fn-a-more [[m coords el] a more]
       (let [new-el (apply f el a more)]
         (.set-nd! m coords new-el)
         new-el))))

(defn lazy-ctx-seq
  "Same as lazy-eseq, but returns a vector of the context of [array coords element]
for each element"
  ([m] (lazy-ctx-seq m (long-array (:bottom-ranges (.domain m)))))
  ([m ^longs coords]
     (let [domain (.domain m)
           bottom-ranges (longs (:bottom-ranges domain))
           top-ranges (longs (:top-ranges domain))
           shape (long-array (shape-from-ranges bottom-ranges top-ranges))
           rank (long (count shape))
           ridx (long-array (reverse (range rank)))
           last-dim (long (aget ridx 0))]
       (lazy-ctx-seq m coords bottom-ranges top-ranges rank ridx last-dim)))
  ([m ^longs coords bottom-ranges top-ranges rank ridx last-dim]
     (let [el (.get-nd m coords)
           ctx [m coords el]
           inc-coords (fn inc-coords []
                        (ainc-long coords last-dim)
                        (dotimes [i rank]
                          (let [dim (aget ridx i)]
                            (when (a== coords top-ranges dim)
                              (acopy coords bottom-ranges dim)
                              (when-not (zero? dim)
                                (ainc-long coords (dec dim)))))) coords)]
             (cons ctx (if (every? true? (map #(== %1 (dec %2)) coords top-ranges))
                        nil
                        (lazy-seq (lazy-ctx-seq m (inc-coords)
                                             bottom-ranges top-ranges
                                             rank ridx last-dim)))))))

(defn lazy-slice-seq
  ([m]
     (lazy-slice-seq m 0 (count m)))
  ([m idx stop]
     (if (= idx stop) nil
         (cons (.get-slice m 0 idx) (lazy-seq (lazy-slice-seq m (inc idx) stop))))))

(deftype ArrayspaceMatrixSeq
    [array ^Long idx]

  clojure.lang.IPersistentCollection
  (empty [this] ())
  (cons [this o] (clojure.lang.Cons o this))
  (equiv [this o] (.equals array o))

  java.util.Collection
  (contains [this o] (boolean (some #(= % o) this)))
  (containsAll [this c] (every? #(.contains this %) c))
  (isEmpty [_] (zero? (int (- (count array) idx))))
  (toArray [this] (into-array Object this))
  (toArray [this arr]
    (if (>= (count arr) (int (- (count array) idx)))
      (do
        (dotimes [i (int (- (count array) idx))]
          (aset arr i (.nth array i)))
        arr)
      (into-array Object this)))
  (size [_] (int (- (count array) idx)))
  (add [_ o] (throw (UnsupportedOperationException.)))
  (addAll [_ c] (throw (UnsupportedOperationException.)))
  (clear [_] (throw (UnsupportedOperationException.)))
  (^boolean remove [_ o] (throw (UnsupportedOperationException.)))
  (removeAll [_ c] (throw (UnsupportedOperationException.)))
  (retainAll [_ c] (throw (UnsupportedOperationException.)))

  clojure.lang.Sequential
  clojure.lang.Seqable
  (seq [this] this)

  clojure.lang.ISeq
  (first [this] (.get-slice array 0 idx))
  (next [this] (if (>= idx (.count array)) nil
                   (ArrayspaceMatrixSeq. array (inc idx))))
  (more [this] (if (>= idx (.count array)) ()
                   (ArrayspaceMatrixSeq. array (inc idx))))

  clojure.lang.IndexedSeq
  (index [this] idx)

  clojure.lang.Counted
  (count [this]
    (int (- (count array) idx))))

(deftype ArrayspaceMatrix
    [implementation-key multi-array-key domain domain-map distribution element-type]

  Object
  (equals [m o]
    ;;(debug-compare m o)
    (and (array? o)
         (every? true? (map == (get-shape m) (get-shape o)))
         (every? true? (map == (element-seq m) (element-seq o)))))

  (hashCode [m]
    (let [coll (element-seq m)]
      (reduce #(hash-combine %1 (.hashCode %2)) (.hashCode (first coll)) (next coll))))

  clojure.lang.Counted
  (count [m]
    "Return count of first dim. Use ecount for count of all elements"
    (first (get-shape m)))

  clojure.lang.Indexed
  (nth [m i]
    (get-slice m 0 i))

  java.util.Collection
  (contains [m o] (boolean (some #(= % o) (element-seq m))))
  (containsAll [m c] (every? #(.contains m %) c))
  (isEmpty [m] (zero? (count m)))
  (toArray [m]
    (let [arr (make-array Object ;;element-type
                          (element-count-of-shape (shape (.domain m))))]
      (do-elements-indexed m (fn [idx el] (aset arr idx el)))
      arr))
  (toArray [m arr]
    (if (>= (count arr) (int (count m)))
      (do  (do-elements-indexed m (fn [idx el] (aset arr idx el)))
           arr)
      (let [arr (make-array Object ;;element-type
                            (element-count-of-shape (shape (.domain m))))]
        (do-elements-indexed m (fn [idx el] (aset arr idx el)))
        arr)))
  (size [m] (int (count m)))
  (add [_ o] (throw (UnsupportedOperationException.)))
  (addAll [_ c] (throw (UnsupportedOperationException.)))
  (clear [_] (throw (UnsupportedOperationException.)))
  (^boolean remove [_ o] (throw (UnsupportedOperationException.)))
  (removeAll [_ c] (throw (UnsupportedOperationException.)))
  (retainAll [_ c] (throw (UnsupportedOperationException.)))

  clojure.lang.Sequential
  clojure.lang.Seqable
  (seq [m]
    ;;(map (fn [idx] (get-slice m 0 idx)) (range (first (shape m))))
    ;;(ArrayspaceMatrixSeq. m 0)
    (lazy-slice-seq m)
    )

  Domain
  (shape [m] (vec (shape domain)))
  (rank [m] (rank domain))

  PImplementation
  (implementation-key [m] implementation-key)
  (construct-matrix
   [m data]
   (if (instance? ArrayspaceMatrix data) (.clone data)
       (let [vdata (convert-to-nested-vectors data)
             ;;_ (println (format "vdata: %s" vdata))
             flat-data (vec (flatten vdata))
             data-shape (get-shape vdata)]
         (when (empty? data-shape)
           (throw (Exception.
                   (str "shape cannot be empty: " data ", vdata: " vdata ", data-shape: " data-shape))))
         (make-arrayspace-matrix implementation-key
                                 multi-array-key
                                 :shape data-shape
                                 :element-type element-type
                                 :data flat-data))))

  (new-vector [m length]
    (make-arrayspace-matrix
     implementation-key
     multi-array-key
     :shape [length]
     :element-type element-type))
  (new-matrix [m rows columns]
    (make-arrayspace-matrix
     implementation-key
     multi-array-key
     :shape [rows columns]
     :element-type element-type))
  (new-matrix-nd [m shape]
    (make-arrayspace-matrix
     implementation-key
     multi-array-key
     :shape shape
     :element-type element-type))
  (supports-dimensionality? [m dimensions]
    (> dimensions 0))

  PDimensionInfo
  (dimensionality [m] (rank domain))
  (get-shape [m] (vec (shape domain)))
  (is-scalar? [m] false)
  (is-vector? [m] (= 1 (rank domain)))
  (dimension-count [m dimension-number]
    (nth (shape domain) dimension-number))

  PTypeInfo
  (element-type [m] element-type)

  PMatrixEquality
  (matrix-equals [m o] (.equals m o))

  PIndexedAccess
  (get-1d [m row]
          (assert (= (rank domain) 1))
          (.get-flat distribution row))
  (get-2d [m row column]
          (assert (= (rank domain) 2))
          (.get-flat distribution (transform-coords domain-map [row column])))
  (get-nd [m indexes]
          (.get-flat distribution (transform-coords domain-map indexes)))

  PIndexedSetting
  (set-1d [m row v]
    (assert (= (rank domain) 1))
    (let [mc (.clone m)]
      (set-1d! mc row v)
      mc))
  (set-2d [m row column v]
    (assert (= (rank domain) 2))
    (let [mc (.clone m)]
      (set-2d! mc row column v)
      mc))
  (set-nd [m indexes v]
    (let [mc (.clone m)]
      (set-nd! mc indexes v)
      mc))
  (is-mutable? [m] true)

  PIndexedSettingMutable
  (set-1d! [m row v]
    (assert (= (rank domain) 1))
    (.set-flat! distribution row v)
    m)
  (set-2d! [m row column v]
    (assert (= (rank domain) 2))
    (.set-flat! distribution (transform-coords domain-map [row column]) v)
    m)
  (set-nd! [m indexes v]
    (.set-flat! distribution (transform-coords domain-map indexes) v)
    m)

  PMatrixSlices
  (get-row [m i]
    (assert (= (rank domain) 2))
    (get-slice m 0 i))

  (get-column [m i]
    (assert (= (rank domain) 2))
    (get-slice m 1 i))

  (get-major-slice [m i]
    (get-slice m 0 i))

  (get-slice [m dimension i]
    {:pre [(and (>= dimension 0) (>= (dec (rank m)) dimension))]}
    (let [new-shape (object-array (drop 1 (shape m)))
          strides (.strides (.domain-map m))
          new-strides (adel strides dimension)]
      (if (empty? new-shape)
        ;;rank0 array == scalar value at index i
        (.get-flat distribution i)
        ;;rank - dim+1 array
        (make-arrayspace-matrix
         implementation-key
         multi-array-key
         :shape new-shape
         :element-type element-type
         :offset (* (nth strides dimension) i)
         :strides (vec new-strides)
         :distribution distribution))))

  PMatrixCloning
  (clone [m]
    (make-arrayspace-matrix
     implementation-key
     multi-array-key
     :shape (shape m)
     :element-type element-type
     :offset (.offset domain-map)
     :distribution (.copy distribution)))

  PConversion
  (convert-to-nested-vectors [m]
    ;;(println (format "XXX -- CNV: m: %s" m))
    (try
      (let [eseq (element-seq m)
            s (shape m)]
        ;;if no shape (scalar value) wrap in vec
        (if-not (count s) (vec eseq)
                (loop [countdown (count s) revshapes (reverse s) accum eseq]
                  (if (zero? countdown) (first accum)
                      (recur (dec countdown)
                             (rest revshapes)
                             (map vec (partition (first revshapes) accum)))))))
      (catch Exception e
        (do (println (format "XXX -- CNV: m: %s, ex: %s" m e)) nil))))
  PCoercion
  (coerce-param [m param]
    (if (instance? ArrayspaceMatrix param)
      (.clone param)
      (construct-matrix m param)))

  PReshaping
  (reshape [m shape] nil)

  PMatrixAdd
  (matrix-add [m a]
    (assert (= (dimensionality m) 2))
    (element-map m + a))
  (matrix-sub [m a]
    (assert (= (dimensionality m) 2))
    (element-map m - a))

  PSummable
  (element-sum [m]
    (element-reduce m +))

  PMatrixMultiply
  (element-multiply [m a]
    (element-map m * a))
  (matrix-multiply [m a]
    (element-map m * a))

  PMatrixScaling
  (scale [m a]
    (element-map m #(* % a)))
  (pre-scale [m a]
    (element-map m (partial * a))))

;; I would have placed these inline in the body of the deftype,
;; but it barfs with an 'Unsupported Binding Form' error on the variadic method impls
(extend-protocol PFunctionalOperations
  ArrayspaceMatrix
  (element-seq [m] (lazy-eseq m))

  (element-map
    ([m f]
       (map f (element-seq m)))
    ([m f a]
       (map f (element-seq m) (if (scalar? a) (repeat a) a)))
    ([m f a more]
       (apply element-seq f m (if (scalar? a) (repeat a) a) more)))

  (element-map!
    ;;Apply fn to all elements in m, setting that element to the result in-place
    ([m f]
       (map (wrap-ctx-fn! f) (lazy-ctx-seq m)))
    ([m f a]
       (map (wrap-ctx-fn! f a) (lazy-ctx-seq m) (if (scalar? a) (repeat a) a)))
    ([m f a more]
       (let [max (ecount m)
             msq (lazy-ctx-seq m)
             em-f (wrap-ctx-fn! f a more)
             em-a1 (ensure-seq a max)
             em-arest (ensure-seqs more max)
             mfn (fn mfn [idx ef msq a1 arest]
                   (if (= idx 0) nil                      
                       (cons (ef (first msq) (first a1) (vec (map first arest)))
                             (lazy-seq (mfn (dec idx) ef (rest msq) (rest a1) (map rest arest))))))]
         (mfn max em-f msq em-a1 em-arest))))

  (element-reduce
    ([m f]
       (reduce f (element-seq m)))
    ([m f init]
       (reduce f init (element-seq m)))))

;; (extend-protocol PAssignment
;;   ArrayspaceMatrix
;;   (assign!
;;     ([m source] nil))

;;   (assign-array!
;;     ([m arr] nil)
;;     ([m arr start length] nil)))


(defn- print-arrayspace-matrix
  [m #^java.io.Writer w]
  (let [rep {:array (.convert-to-nested-vectors m)
             :shape (.shape m)}]
      (.write w "#ArrayspaceMatrix")
      (print-method rep w)
      ;; (.write w "{:domain ")
      ;; (print-method (.domain m) w)
      ;; (.write w ", :domain-map ")
      ;; (print-method (.domain-map m) w)
      ;; (.write w ", :distribution ")
      ;; (print-method (.distribution m) w)
      ;; (.write w ", :element-type ")
      ;; (print-method (.element-type m) w)
    ))

(defmethod print-method ArrayspaceMatrix [m w]
  (print-arrayspace-matrix m w))

(defn do-elements!
  [m el-fn]
  (do-elements-loop m coords idx el
                    (set-nd m coords (el-fn el))))

(defn do-elements-indexed [m el-fn]
  (do-elements-loop m coords idx el (el-fn idx el)))

(defn do-elements [m el-fn]
  (do-elements-indexed m (fn [idx el] (el-fn el))))

(defn map-elements [m el-fn]
  (map el-fn (element-seq m)))

(defn maybe-coerce-data [m param]
  (cond
   (instance? ArrayspaceMatrix param) (.clone m)
   (array? param) (convert-to-nested-vectors param)
   (is-scalar? param) (convert-to-nested-vectors param)
   :default nil))

(defn make-arrayspace-matrix
  [impl-kw multi-array-kw & {:keys [shape element-type data offset strides distribution partition-count]}]
  ;;{:pre [(if data (>= (element-count-of-shape shape) (clojure.core.matrix/shape data)) true)]}
  (when data
    (when-not (>= (element-count-of-shape shape) (ecount data))
      (println (format "XXX--ecount shape NOT >= shape of data! %s %s %s" shape (element-count-of-shape shape) (ecount data)))))
  (let [resolved-type (if data (resolve-type-from-data data) (resolve-type element-type))
        domain (make-domain multi-array-kw :shape shape)
        distribution (or distribution (make-distribution multi-array-kw
                                                   :element-type resolved-type
                                                   :element-count (element-count-of-shape (.shape domain))
                                                   :partition-count (or partition-count 1)
                                                   :data data))
        domain-map (make-domain-map :default
                                    :domain domain
                                    :distribution distribution
                                    :offset (or offset 0)
                                    :strides strides)]
    (when-not (instance? Class resolved-type)
      (println (format "XXX-- resolved-type: %s, type: %s" resolved-type (type resolved-type)))
      (throw (IllegalArgumentException. (str "param resolved-type not class: " resolved-type ", type: " (type resolved-type)))))

    (ArrayspaceMatrix. impl-kw multi-array-kw domain domain-map distribution resolved-type)))

(def double-local-1d-java-array-impl
  (make-arrayspace-matrix :double-local-1d-java-array
                          :local-1d-java-array
                          :shape [3 3 3]
                          :element-type Double/TYPE))

(def double-local-buffer-impl
  (make-arrayspace-matrix :double-local-buffer
                          :local-byte-buffer
                          :shape [3 3 3]
                          :element-type Double/TYPE))


(def double-partitioned-buffer-impl
  (make-arrayspace-matrix :double-partitioned-buffer
                          :partitioned-byte-buffer
                          :shape [3 3 3]
                          :element-type Double/TYPE))

(def int-local-1d-java-array-impl
  (make-arrayspace-matrix :int-local-1d-java-array
                          :local-1d-java-array
                          :shape [3 3 3]
                          :element-type Integer/TYPE))

(def int-local-buffer-impl
  (make-arrayspace-matrix :int-local-buffer
                          :local-byte-buffer
                          :shape [3 3 3]
                          :element-type Integer/TYPE))

(def int-partitioned-buffer-impl
  (make-arrayspace-matrix :int-partitioned-buffer
                          :partitioned-byte-buffer
                          :shape [3 3 3]
                          :element-type Integer/TYPE))


(imp/register-implementation double-local-1d-java-array-impl)
(imp/register-implementation double-local-buffer-impl)
(imp/register-implementation double-partitioned-buffer-impl)
(imp/register-implementation int-local-1d-java-array-impl)
(imp/register-implementation int-local-buffer-impl)
(imp/register-implementation int-partitioned-buffer-impl)

(comment
  "Add scratchpad/REPL forms here")

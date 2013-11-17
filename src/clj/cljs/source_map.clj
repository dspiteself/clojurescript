(ns cljs.source-map
  (:require [clojure.java.io :as io]
            [clojure.string :as string]
            [clojure.data.json :as json]
            [clojure.set :as set]
            [clojure.pprint :as pp]
            [cljs.source-map.base64-vlq :as base64-vlq]))

;; =============================================================================
;; All source map code in the file assumes the following in memory
;; representation of source map data.
;;
;; { file-name[String]
;;   { line[Integer]
;;     { col[Integer]
;;       [{ :gline ..., :gcol ..., :name ...}] } }
;;
;; The outer level is a sorted map where the entries are file name and
;; sorted map of line information, the keys are strings. The line
;; information is represented as a sorted map of of column
;; information, the keys are integers. The column information is a
;; sorted map where the keys are integers and values are a vector of
;; maps - these maps have the keys :gline and :gcol for the generated
;; line and column.  A :name key may be present if available.
;;
;; This representation simplifies merging ClojureScript source map
;; information with source map information generated by Google Closure
;; Compiler optimization. We can now trivially create the merged map
;; by using :gline and :gcol in the ClojureScript source map data to
;; extract final :gline and :gcol from the Google Closure source map.

;; -----------------------------------------------------------------------------
;; Utilities

(defn indexed-sources
  "Take a seq of source file names and return a map from
   file number to integer index."
  [sources]
  (->> sources
    (map-indexed (fn [a b] [a b]))
    (reduce (fn [m [i v]] (assoc m v i)) {})))

(defn source-compare
  "Take a seq of source file names and return a comparator
   that can be used to construct a sorted map."
  [sources]
  (let [sources (indexed-sources sources)]
    (fn [a b] (compare (sources a) (sources b)))))

;; -----------------------------------------------------------------------------
;; Decoding

(defn seg->map
  "Take a source map segment represented as a vector
   and return a map."
  [seg source-map]
  (let [[gcol source line col name] seg]
   {:gcol   gcol
    :source (nth (:sources source-map) source)
    :line   line
    :col    col
    :name   (when-let [name (-> seg meta :name)]
              (nth (:names source-map) name))}))

(defn seg-combine
  "Combine a source map segment vector and a relative
   source map segment vector and combine them to get
   an absolute segment posititon information as a vector."
  [seg relseg]
  (let [[gcol source line col name] seg
        [rgcol rsource rline rcol rname] relseg
        nseg [(+ gcol rgcol)
              (+ (or source 0) rsource)
              (+ (or line 0) rline)
              (+ (or col 0) rcol)
              (+ (or name 0) rname)]]
    (if name
      (with-meta nseg {:name (+ name rname)})
      nseg)))

(defn update-result
  "Helper for decode. Take an internal source map representation
   organized as nested sorted maps mapping file, line, and column
   and update it based on a segment map and generated line number."
  [result segmap gline]
  (let [{:keys [gcol source line col name]} segmap
        d {:gline gline
           :gcol gcol}
        d (if name (assoc d :name name) d)]
    (update-in result [source]
      (fnil (fn [m]
              (update-in m [line]
                (fnil (fn [m]
                        (update-in m [col]
                          (fnil (fn [v] (conj v d))
                            [])))
                      (sorted-map))))
            (sorted-map)))))

(defn decode
  "Convert a v3 source map JSON object into a nested sorted map 
   organized as file, line, and column."
  ([source-map]
     (decode (:mappings source-map) source-map))
  ([mappings source-map]
     (let [{:keys [sources]} source-map
           relseg-init [0 0 0 0 0]
           lines (seq (string/split mappings #";"))]
       (loop [gline 0
              lines lines
              relseg relseg-init
              result (sorted-map-by (source-compare sources))]
         (if lines
           (let [line (first lines)
                 [result relseg]
                 (if (string/blank? line)
                   [result relseg]
                   (let [segs (seq (string/split line #","))]
                     (loop [segs segs relseg relseg result result]
                       (if segs
                         (let [seg (first segs)
                               nrelseg (seg-combine (base64-vlq/decode seg) relseg)]
                           (recur (next segs) nrelseg
                             (update-result result (seg->map nrelseg source-map) gline)))
                         [result relseg]))))]
             (recur (inc gline) (next lines) (assoc relseg 0 0) result))
           result)))))

;; -----------------------------------------------------------------------------
;; Encoding

(defn lines->segs
  "Take a nested sorted map encoding line and column information
   for a file and return a vector of vectors of encoded segments.
   Each vector represents a line, and the internal vectors are segments
   representing the contents of the line."
  [lines]
  (let [relseg (atom [0 0 0 0 0])]
    (reduce
      (fn [segs cols]
        (swap! relseg
          (fn [[_ source line col name]]
            [0 source line col name]))
        (conj segs
          (reduce
            (fn [cols [gcol sidx line col name :as seg]]
              (let [offset (map - seg @relseg)]
                (swap! relseg
                  (fn [[_ _ _ _ lname]]
                    [gcol sidx line col (or name lname)]))
                (conj cols (base64-vlq/encode offset))))
            [] cols)))
      [] lines)))

(defn relativize-path [path {:keys [output-dir source-map-path relpaths]}]
  (cond
    (re-find #"\.jar!/" path)
    (str (or source-map-path output-dir) (second (string/split path #"\.jar!")))    

    :else
    (str (or source-map-path output-dir) "/" (get relpaths path))))

(defn encode
  "Take an internal source map representation represented as nested
   sorted maps of file, line, column and return a source map v3 JSON
   string."
  [m opts]
  (let [lines (atom [[]])
        names->idx (atom {})
        name-idx (atom 0)
        info->segv
        (fn [info source-idx line col]
          (let [segv [(:gcol info) source-idx line col]]
            (if-let [name (:name info)]
              (let [idx (if-let [idx (get @names->idx name)]
                          idx
                          (let [cidx @name-idx]
                            (swap! names->idx assoc name cidx)
                            (swap! name-idx inc)
                            cidx))]
                (conj segv idx))
              segv)))
        encode-cols
        (fn [infos source-idx line col]
          (doseq [info infos]
            (let [segv (info->segv info source-idx line col)
                  gline (:gline info)
                  lc (count @lines)]
              (if (> gline (dec lc))
                (swap! lines
                  (fn [lines]
                    (conj (into lines (repeat (dec (- gline (dec lc))) [])) [segv])))
                (swap! lines
                  (fn [lines]
                    (update-in lines [gline] conj segv)))))))]
    (doseq [[source-idx [_ lines]] (map-indexed (fn [i v] [i v]) m)]
      (doseq [[line cols] lines]
        (doseq [[col infos] cols]
          (encode-cols infos source-idx line col))))
    (with-out-str
      (json/pprint 
       {"version" 3
        "file" (:file opts)
        "sources" (into []
                    (let [paths (keys m)
                          f (if (:output-dir opts)
                              #(relativize-path % opts)
                              #(last (string/split % #"/")))]
                      (map f paths)))
        "lineCount" (:lines opts)
        "mappings" (->> (lines->segs @lines)
                     (map #(string/join "," %))
                     (string/join ";"))
        "names" (into []
                  (map (set/map-invert @names->idx)
                    (range (count @names->idx))))}
       :escape-slash false))))

;; -----------------------------------------------------------------------------
;; Merging

(defn merge-source-maps
  "Merge an internal source map representation of a single
   ClojureScript file with an internal source map representation of
   the generated JavaScript file that underwent Google Closure
   Compiler optimization."
  [cljs-map closure-map]
  (loop [line-map-seq (seq cljs-map) new-lines (sorted-map)]
    (if line-map-seq
      (let [[line col-map] (first line-map-seq)
            new-cols
            (loop [col-map-seq (seq col-map) new-cols (sorted-map)]
              (if col-map-seq
                (let [[col infos] (first col-map-seq)]
                  (recur (next col-map-seq)
                    (assoc new-cols col
                      (reduce (fn [v {:keys [gline gcol]}]
                                (into v (get-in closure-map [gline gcol])))
                        [] infos))))
                new-cols))]
        (recur (next line-map-seq)
          (assoc new-lines line new-cols)))
      new-lines)))

(comment
  ;; INSTRUCTIONS:
  
  ;; switch into samples/hello
  ;; run repl to start clojure
  ;; build with
  
  (require '[cljs.closure :as cljsc])
  (cljsc/build "src" {:optimizations :simple :output-to "hello.js" :source-map "hello.js.map"})

  ;; load source map
  (def raw-source-map
    (json/read-str (slurp (io/file "hello.js.map")) :key-fn keyword))

  ;; test it out
  (first (decode raw-source-map))

  ;; decoded source map preserves file order
  (= (keys (decode raw-source-map)) (:sources raw-source-map))
  )

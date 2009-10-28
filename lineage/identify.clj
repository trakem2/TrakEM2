(ns lineage.identify
  (:import
     (lineage LineageClassifier)
     (java.io InputStreamReader FileInputStream PushbackReader)
     (javax.vecmath Point3d)
     (javax.swing JTable JFrame JScrollPane JPanel JLabel BoxLayout)
     (javax.swing.table AbstractTableModel)
     (java.awt.event MouseAdapter)
     (java.awt Color Dimension Component)
     (mpicbg.models AffineModel3D)
     (ini.trakem2.utils Utils)
     (ini.trakem2.display Display Display3D LayerSet)
     (ini.trakem2.vector Compare VectorString3D)))

;(import
;     '(lineage LineageClassifier)
;     '(java.io InputStreamReader)
;     '(ini.trakem2.vector Compare VectorString3D))

(defn as-VectorString3D
  "Convert a map of {\"name\" {:x (...) :y (...) :z (...)}} into a map of {\"name\" (VectorString3D. ...)}"
  [SATs]
  (map
    (fn [e]
      {(key e)
       (let [data (val e)]
         (VectorString3D. (into-array Double/TYPE (data :x))
                          (into-array Double/TYPE (data :y))
                          (into-array Double/TYPE (data :z))
                          false))})
    SATs))

(defn load-SATs-lib
  "Returns the SATs library with VectorString3D instances"
  []
  (reduce
    (fn [m e]
      (let [label (key e)
            data (val e)]
        (assoc m
               label
               (assoc data :SATs (as-VectorString3D (data :SATs))))))
    {}
    (read
      (PushbackReader.
      ; (InputStreamReader. (LineageClassifier/getResourceAsStream "/lineages/SAT-lib.clj"))  ; TESTING
        (InputStreamReader. (FileInputStream. "/home/albert/lab/confocal/L3_lineages/SAT-lib.clj"))
        4096))))

(defn fids-as-Point3d
  "Convert a map like {\"blva_joint\" [70.62114008906023 140.07819545137772 -78.72010436407604] ...}
  into a sorted map of {\"blva_joint\" (Point3d ...)}"
  [fids]
  (reduce
    (fn [m e]
      (let [xyz (val e)]
        (assoc m (key e) (Point3d. (double (get xyz 0))
                                   (double (get xyz 1))
                                   (double (get xyz 2))))))
    {}
    fids))

(defn register-SATs
  "Returns a map of SAT name vs VectorString3D,
   where the VectorString3D is registered to the target fids."
  [brain-label brain-data target-fids]
  (let [source-fids (fids-as-Point3d (brain-data :fiducials))
        SATs (into (sorted-map) (brain-data :SATs))
        common-fid-keys (clojure.set/intersection (into #{} (keys source-fids)) (into #{} (keys target-fids)))
        vs (Compare/transferVectorStrings (vals SATs)
                                          (vals (into (sorted-map) (select-keys source-fids common-fid-keys)))
                                          (vals (into (sorted-map) (select-keys target-fids common-fid-keys)))
                                          AffineModel3D)]
    (zipmap
      (map #(str % "---" brain-label) (keys SATs))
      vs)))

(defn prepare-SAT-lib
  "Returns the SATs library as a map of name keys and VectorString3D values
  Will define the FRT42-fids to be used as reference fibs for all."
  []
  (let [SATs-lib (load-SATs-lib)
        target-fids (do
                      (def FRT42-fids (fids-as-Point3d ((SATs-lib "FRT42 new") :fiducials)))
                      FRT42-fids)]
    (reduce
      (fn [m e]
        (conj m (register-SATs (key e) (val e) target-fids)))
      {}
      SATs-lib)))

(def SAT-lib (prepare-SAT-lib))

(defn register-vs
  "Register a singe VectorString3D from source-fids to target-fids."
  [vs source-fids target-fids]
  (let [common-fid-keys (clojure.set/intersection (into #{} (keys source-fids)) (into #{} (keys target-fids)))]
    (if (empty? common-fid-keys)
      nil
      (first (Compare/transferVectorStrings [vs]
                                     (vals (into (sorted-map) (select-keys source-fids common-fid-keys)))
                                     (vals (into (sorted-map) (select-keys target-fids common-fid-keys)))
                                     AffineModel3D)))))

(defn resample
  "Returns a resampled copy of vs."
  [vs delta]
  (let [copy (.clone vs)]
    (.resample copy delta)
    copy))

(defn match-all
  "Returns a vector of two elements; the first element is the list of matches
  of the query-vs against all SAT vs in the library sorted by mean euclidean distance,
  and labeled as correct of incorrect matches according to the Random Forest classifier.
  The second element is a list of corresponding SAT names for each match."
  [query-vs fids delta direct substring]
  (let [vs1 (resample (register-vs query-vs fids FRT42-fids) delta)
        matches (sort
                  (proxy [java.util.Comparator] []
                    (equals [o]
                      (= o this))
                    (compare [o1 o2]
                      (int (- (o1 :med) (o2 :med)))))
                  (map
                    (fn [e]
                      (let [vs2 (let [copy (.clone (val e))]
                                  (.resample copy delta)
                                  copy)
                            c (Compare/findBestMatch vs1 vs2
                                                     (double delta) false (int 5) (float 0.5) Compare/AVG_PHYS_DIST
                                                     direct substring
                                                     (double 1.1) (double 1.1) (double 1))
                            stats (.getStatistics (get c 0) false (int 0) (float 0) false)]
                        {:SAT-name (key e)
                         :stats stats
                         :med (get stats 0)
                         :correct (LineageClassifier/classify stats)}))
                    SAT-lib))]

    [matches
     (map (fn [match]
            (match :SAT-name))
            matches)]))

(defn text-width
  "Measure the width, in pixels, of the String text, by the Font and FontMetrics of component."
  [#^String text #^Component component]
  ; Get the int[] of widths of the first 256 chars
  (let [#^ints ws (.getWidths (.getFontMetrics component (.getFont component)))]
    (reduce (fn [sum c]
              (if (< (int c) (int 256))
                (+ sum (aget ws (int c)))
                sum))
            0
            text)))

(defn identify-SAT
  "Takes a calibrated VectorString3D and a list of fiducial points, and checks against the library for identity.
  For consistency in the usage of the Random Forest classifier, the registration is done into the FRT42D-BP106 brain."
  [query-vs fids delta direct substring]
  (let [[matches names] (match-all query-vs fids delta direct substring)
        SAT-names (vec names)
        indexed (vec matches)
        column-names ["SAT" "Match" "Seq sim" "Lev Dist" "Med Dist" "Avg Dist" "Cum Dist" "Std Dev" "Prop Mut" "Prop Lengths" "Proximity" "Prox Mut" "Tortuosity"]
        table (JTable. (proxy [AbstractTableModel] []
                (getColumnName [col]
                  (get column-names col))
                (getRowCount []
                  (count matches))
                (getColumnCount []
                  (count column-names))
                (getValueAt [row col]
                  (let [match (get indexed row)
                        stats (match :stats)]
                    (cond
                      (= col 0) (get SAT-names row)
                      (= col 1) (str (match :correct))    ; Whether the classifier considered it correct or not
                      true (Utils/cutNumber
                            (cond
                              (= col 2) (get stats 6)       ; Similarity
                              (= col 3) (get stats 5)       ; Levenshtein
                              (= col 4) (get stats 3)       ; Median Physical Distance
                              (= col 5) (get stats 0)       ; Average Physical Distance
                              (= col 6) (get stats 1)       ; Cummulative Physical Distance
                              (= col 7) (get stats 2)       ; Std Dev
                              (= col 8) (get stats 4)       ; Prop Mut
                              (= col 9) (get stats 9)       ; Prop Lengths
                              (= col 10) (get stats 7)      ; Proximity
                              (= col 11) (get stats 8)      ; Prox Mut
                              (= col 12) (get stats 10))    ; Tortuosity
                            2))))
                (isCellEditable [row col]
                  false)
                (setValueAt [ob row col] nil)))
        frame (JFrame. "Matches")
        dummy_ls (LayerSet. (.. Display getFront getProject) (long -1) "Dummy" (double 0) (double 0) (double 0) (double 0) (double 0) (double 512) (double 512) false (int 0) (java.awt.geom.AffineTransform.))]
    (.add frame (JScrollPane. table))
    (.setSize frame (int 950) (int 550))
    (.addMouseListener table
                       (proxy [MouseAdapter] []
                         (mousePressed [ev]
                           (send-off
                             (agent nil)
                             (fn [_]
                               (if (= 2 (.getClickCount ev))
                                 (let [match (indexed (.rowAtPoint table (.getPoint ev)))]
                                   (println "two clicks")
                                   (Display3D/addMesh dummy_ls
                                                      (resample query-vs)
                                                      "Query"
                                                      Color/yellow)
                                   (Display3D/addMesh dummy_ls
                                                      (resample (SAT-lib (match :SAT-name)))
                                                      (match :SAT-name)
                                                      (if (match :correct)
                                                        Color/red
                                                        Color/blue)))))))))
    ; Enlarge the cell width of the first column
    (.setMinWidth (.. table getColumnModel (getColumn 0)) (int 250))
    (doto frame
      ;(.pack)
      (.setVisible true))))


(defn identify
  "Identify a Pipe or Polyline (which implement Line3D) that represent a SAT."
  [p]
  (identify-SAT
    (let [vs (.asVectorString3D p)]
          (.calibrate vs (.. p getLayerSet getCalibrationCopy))
          vs)
    (Compare/extractPoints (first (.. p getProject getRootProjectThing (findChildrenOfTypeR "fiducial_points"))))
    1.0
    true
    false))

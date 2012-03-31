(ns lineage.identify
  (:import
     (lineage LineageClassifier)
     (java.io InputStreamReader FileInputStream PushbackReader)
     (javax.vecmath Point3d)
     (javax.swing JTable JFrame JScrollPane JPanel JLabel BoxLayout JPopupMenu JMenuItem)
     (javax.swing.table AbstractTableModel DefaultTableCellRenderer)
     (java.awt.event MouseAdapter ActionListener)
     (java.awt Color Dimension Component)
     (java.util Map)
     (mpicbg.models AffineModel3D)
     (ij.measure Calibration)
     (ini.trakem2.utils Utils)
     (ini.trakem2.display Display Display3D LayerSet)
     (ini.trakem2.analysis Compare)
     (ini.trakem2.vector VectorString3D Editions))
  (:use [clojure.set :only (intersection)]))

(defmacro report
  [& args]
  `(ij.IJ/log (str ~@args)))

(defn- as-VectorString3D
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

(defn- load-SAT-lib-as-vs
  "Returns the SATs library with VectorString3D instances"
  [filepath]
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
        (InputStreamReader. (FileInputStream. filepath))
        4096))))

(defn- fids-as-Point3d
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

(defn- register-SATs
  "Returns a map of SAT name vs VectorString3D,
   where the VectorString3D is registered to the target fids."
  [brain-label brain-data target-fids]
  (let [source-fids (fids-as-Point3d (brain-data :fiducials))
        SATs (into (sorted-map) (brain-data :SATs))
        common-fid-keys (intersection (into #{} (keys source-fids)) (into #{} (keys target-fids)))
        vs (Compare/transferVectorStrings (vals SATs)
                                          (vals (into (sorted-map) (select-keys source-fids common-fid-keys)))
                                          (vals (into (sorted-map) (select-keys target-fids common-fid-keys)))
                                          AffineModel3D)]
    (zipmap
      (map #(str (.replaceAll % "\\[.*\\] " " [") \space brain-label \]) (keys SATs))
      vs)))

(defn- load-mb
  [SAT-lib reference-brain]
  (let [r (re-pattern (str "peduncle . (dorsal|medial) lobe.*" reference-brain ".*"))]
    (loop [sl SAT-lib
           mb {}]
      (if (= 2 (count mb))
        mb
        (if-let [[k v] (first sl)]
          (recur (next sl)
                 (if (re-matches r k)
                   (into mb {k v})
                   mb)))))))

(def ^:dynamic *libs* (ref {}))

(defn- load-SAT-lib
  "Load the named SAT-lib from filepath into *libs* and returns it.
  Will setup the reference set of fiducial points and the mushroom body lobes from the entry named by reference brain,
  as we as register all SATs to the reference brain."
  [lib]
  (let [SAT-lib (load-SAT-lib-as-vs (lib "filepath"))
        reference (let [r (SAT-lib (lib "reference"))]
                    (if r
                      r
                      ; Else, use the first brain of the set as reference
                      (let [rr (first (keys SAT-lib))]
                        (println "Could not find reference brain '" (lib "reference") "'\n  Instead, using as reference brain: " rr)
                        (SAT-lib rr))))
        target-fids (fids-as-Point3d (reference :fiducials))
        SATs (reduce
               (fn [m e]
                 (conj m (register-SATs (key e) (val e) target-fids)))
               {}
               SAT-lib)]
    (dosync
      (commute *libs* (fn [libs]
                        (assoc libs (lib "title") {:filepath (lib "filepath")
                                                   :SATs SATs
                                                   :fids target-fids
                                                   :mb (load-mb SATs (lib "reference"))})))))
  (@*libs* (lib "title")))


(defn forget-libs
  []
  (dosync
    (commute *libs* (fn [_] {}))))

(defn- register-vs
  "Register a singe VectorString3D from source-fids to target-fids."
  [vs source-fids target-fids]
  (let [common-fid-keys (intersection (into #{} (keys source-fids)) (into #{} (keys target-fids)))]
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

(defn- match-all
  "Returns a vector of two elements; the first element is the list of matches
  of the query-vs against all SAT vs in the library sorted by mean euclidean distance,
  and labeled as correct of incorrect matches according to the Random Forest classifier.
  The second element is a list of corresponding SAT names for each match.
  Assumes the query-vs is already registered to the corresponding SAT-lib reference fiducials."
  [SATs query-vs delta direct substring]
  (let [vs1 (resample query-vs delta)   ; query-vs is already registered into FRT42-fids
        matches (sort
                  #(int (- (%1 :med) (%2 :med)))
                  (doall (pmap
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
                    SATs)))]
    ; Cleanup thread table
    (LineageClassifier/flush)

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

(defn- identify-SAT
  "Takes a calibrated VectorString3D and a list of fiducial points, and checks against the library for identity.
  For consistency in the usage of the Random Forest classifier, the registration is done into the FRT42D-BP106 brain."
  [pipe SAT-lib query-vs fids delta direct substring]
  (let [SATs (SAT-lib :SATs)
        vs1 (register-vs query-vs fids (SAT-lib :fids))
        [matches names] (match-all SATs vs1 delta direct substring)
        SAT-names (vec names)
        indexed (vec matches)
        column-names ["SAT" "Match" "Seq sim %" "Lev Dist" "Med Dist" "Avg Dist" "Cum Dist" "Std Dev" "Prop Mut" "Prop Lengths" "Proximity" "Prox Mut" "Tortuosity"]
        worker (agent nil) ; for event dispatch
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
                              (= col 2) (* 100 (get stats 6))       ; Similarity
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
        frame (JFrame. (str "Matches for " pipe))
        dummy-ls (LayerSet. (.. Display getFront getProject) (long -1) "Dummy" (double 0) (double 0) (double 0) (double 0) (double 0) (double 512) (double 512) false (int 0) (java.awt.geom.AffineTransform.))]
    (.setCellRenderer (.getColumn table "Match")
                      (proxy [DefaultTableCellRenderer] []
                        (getTableCellRendererComponent [t v sel foc row col]
                          (proxy-super setText (str v))
                          (proxy-super setBackground
                                          (if (Boolean/parseBoolean v)
                                            (Color. 166 255 166)
                                            (if sel
                                              (Color. 184 207 229)
                                              Color/white)))
                                              
                          this)))
    (.add frame (JScrollPane. table))
    (.setSize frame (int 950) (int 550))
    (.addMouseListener table
                       (proxy [MouseAdapter] []
                         (mousePressed [ev]
                           (try
                             (clear-agent-errors worker) ; I don't care what it was
                             (catch Exception e))
                           (send-off worker
                             (fn [_]
                               (let [match (indexed (.rowAtPoint table (.getPoint ev)))
                                     show-match (fn []
                                                  (Display3D/addMesh dummy-ls
                                                                     (resample (.clone vs1) delta)
                                                                     "Query"
                                                                     Color/yellow)
                                                  (Display3D/addMesh dummy-ls
                                                                     (resample (SATs (match :SAT-name)) delta)
                                                                     (match :SAT-name)
                                                                     (if (match :correct)
                                                                       Color/red
                                                                       Color/blue)))]
                                 (cond
                                   ; On double-click, show 3D view of the match:
                                   (= 2 (.getClickCount ev))
                                     (show-match)
                                   ; On right-click, show menu
                                   (Utils/isPopupTrigger ev)
                                     (let [popup (JPopupMenu.)
                                           new-command (fn [title action]
                                                         (let [item (JMenuItem. title)]
                                                           (.addActionListener item (proxy [ActionListener] []
                                                                                      (actionPerformed [evt]
                                                                                        (send-off worker (fn [_] (action))))))
                                                           item))]
                                       (doto popup
                                         (.add (new-command "Show match in 3D"
                                                            show-match))
                                         (.add (new-command "Show Mushroom body"
                                                            #(doseq [[k v] (SAT-lib :mb)]
                                                              (Display3D/addMesh dummy-ls v k Color/gray))))
                                         (.add (new-command "Show interpolated"
                                                            #(Display3D/addMesh dummy-ls
                                                                                (VectorString3D/createInterpolatedPoints
                                                                                  (Editions. (SATs (match :SAT-name)) (.clone vs1) delta false (double 1.1) (double 1.1) (double 1)) (float 0.5))
                                                                                (str "Interpolated with " (match :SAT-name)) Color/magenta)))
                                         (.add (new-command "Show stdDev plot"
                                                            #(let [cp (ini.trakem2.analysis.Compare$CATAParameters.)]
                                                              (if (.setup cp false nil true true)
                                                                (let [condensed (VectorString3D/createInterpolatedPoints
                                                                                  (let [v1 (.clone vs1)
                                                                                        v2 (SATs (match :SAT-name))]
                                                                                        (.resample v1 delta true)
                                                                                        (.resample v2 delta true)
                                                                                        (Editions. v1 v2 delta false (double 1.1) (double 1.1) (double 1)))
                                                                                  (float 0.5))
                                                                      _ (let [c1 (Calibration.); Dummy default calibration but with same units as the pipe.
                                                                                               ; VectorString3D instances are already calibrated.
                                                                              c2 (.. pipe getLayerSet getCalibrationCopy)]
                                                                          (.setUnit c1 (.getUnit c2))
                                                                          (.calibrate condensed c1))
                                                                      _ (set! (. cp plot_max_x) (.length condensed))
                                                                      plot (Compare/makePlot cp (str "Query versus " (match :SAT-name)) condensed)]
                                                                  ; There are lazy evaluations ... that need to be promoted: lets print some data:
                                                                  (println "plot is" plot " and condensed has " (.getStdDevAtEachPoint condensed))
                                                                  (if plot
                                                                    (.show plot)
                                                                    (report "ERROR: could not make a plot!")))))))
                                         (.show table (.getX ev) (.getY ev)))))))))))

    ; Enlarge the cell width of the first column
    (.setMinWidth (.. table getColumnModel (getColumn 0)) (int 250))
    (doto frame
      ;(.pack)
      (.setVisible true))))

(defn fetch-lib
  "Returns the SAT lib for the given name, loading it if not there yet. Nil otherwise."
  [lib]
  (if-let [cached (@*libs* (lib "title"))]
    cached
    (try
      (load-SAT-lib lib)
      (catch Exception e
        (do
          (report "An error ocurred while loading the SAT library '" (lib "title") ": " e "\nCheck the terminal output.")
          (.printStackTrace e))))))


(defn extract-fiducial-points
  "Find a set of fiducial points in the project.
  Will find the first node of type 'fiducial_points' and recursively inspect
  its children nodes for nodes of type 'ball'. To each found 'ball', it will
  assign the name of the ball node itself or the first superior node that
  has a name different that this type.
  Returns nil if none found."
  [project]
  (if-let [fp-node (.. project getRootProjectThing (findChildrenOfTypeR "fiducial_points"))]
    (if-let [ball-nodes (.findChildrenOfTypeR (first fp-node) "ball")]
      (let [fids (reduce
                   (fn [fids pt-ball]
                     (let [title (.. pt-ball getObject getTitle) ; the title of the Ball ob itself, if any
                           b (.. pt-ball getObject getWorldBalls)]
                       (if (= 0 (count b))
                         (do
                           (println "WARNING: empty ball object" (.getObject pt-ball))
                           fids)
                         (do
                           (if (> (count b) 1)
                             (println "WARNING: ball object with more than one ball:" (.getObject pt-ball)))
                           (assoc fids
                                  (-> (if (not (= title (.getType pt-ball)))
                                        title
                                        (.. pt-ball getParent getTitle))
                                      (.toLowerCase)
                                      (.replace \space \_))
                                  (let [b0 (get b 0)]
                                    (Point3d. (get b0 0) (get b0 1) (get b0 2))))))))
                   {}
                   ball-nodes)]
        (println "Found" (count fids) "fiducial points:\n" fids)
        fids)
      (println "No ball objects found for" fp-node))
    (println "No fiducial points found")))

(defn identify
  "Identify a Pipe or Polyline (which implement Line3D) that represent a SAT."
  ([p
    ^Map lib]
    (identify p lib 1.0 true false))
  ([p
    ^Map lib
    delta direct substring]
    (if p
      (if-let [SAT-lib (fetch-lib (into {} lib))]
        (if-let [fids (extract-fiducial-points (.getProject p))]
          (identify-SAT
            p
            SAT-lib
            (let [vs (.asVectorString3D p)]
                  (.calibrate vs (.. p getLayerSet getCalibrationCopy))
                  vs)
            fids
            delta
            direct
            substring)
          (report "Cannot find fiducial points for project" (.getProject p)))
        (report "Cannot find or parse SAT library at " (.get lib "title") " at " (.get lib "filepath")))
      (report "Cannot identify a null pipe or polyline!"))))


(defn identify-without-gui
  "Identify a Pipe or Polyline (which implement Line3D) that represent a SAT.
  No GUI is shown. Returns the vector containing the list of matches and the list of names."
  ([p
    ^Map lib]
    (identify-without-gui p lib 1.0 true false))
  ([p
    ^Map lib
    delta direct substring]
    (if p
      (if-let [SAT-lib (fetch-lib (into {} lib))]
        (let [SATs (SAT-lib :SATs)
              query-vs (let [vs (.asVectorString3D p)]
                         (.calibrate vs (.. p getLayerSet getCalibrationCopy))
                         vs)
              fids (extract-fiducial-points (.getProject p)) ; (first (.. p getProject getRootProjectThing (findChildrenOfTypeR "fiducial_points"))))
              vs1 (register-vs query-vs fids (SAT-lib :fids))]
          (match-all SATs vs1 delta direct substring)))
      (report "Cannot identify a null pipe or polyline!"))))


;(defn lib-stats
;  "Returns a map with the number of SATs in the library, a list of names vs. sorted sequence lengths, the median length, the average length."
;  [lib-name delta]
;  (if-let [lib (fetch-lib lib-name)]
;    (let [SATs (SAT-lib :SATs)
;          lengths (reduce
;                    (sorted-map-by
;                      #(< 
;
;
;      {:n (count SATs)
;       :
;    (report (str "Unknown library " lib-name))))

(defn- ready-vs
  "Return a calibrate and registered VectorString3D from a chain."
  [chain source-fids target-fids]
  (let [vs (.clone (.vs chain))]
    (.calibrate vs (.. chain getRoot getLayerSet getCalibrationCopy))
    (register-vs vs source-fids target-fids)))

(defn quantify-all
  "Take all pipes in project and score/classify them.
  Returns a sorted map of name vs. a vector with:
  - if the top 1,2,3,4,5 have a homonymous
  - the number of positives: 'true' and homonymous
  - the number of false positives: 'true' and not homonymous
  - the number of false negatives: 'false' and homonymous
  - the number of true negatives: 'false' and not homonymous
  - the FPR: false positive rate: false positives / ( false positives + true negatives )
  - the FNR: false negative rate: false negatives / ( false negatives + true positives )
  - the TPR: true positive rate: 1 - FNR
  - the length of the sequence queried"
  [project lib regex-exclude delta direct substring]
  (let [fids (extract-fiducial-points (first (.. project getRootProjectThing (findChildrenOfTypeR "fiducial_points"))))
        tops (int 5)
        SAT-lib (fetch-lib lib)]
    (if SAT-lib
      (reduce
        (fn [m chain]
          (let [vs (resample (ready-vs chain fids (SAT-lib :fids)) delta)
                [matches names] (match-all (SAT-lib :SATs) vs delta direct substring)
                names (vec (take tops names))
                #^String SAT-name (let [t (.getCellTitle chain)]
                                    (.substring t (int 0) (.indexOf t (int \space))))
                ;has-top-match (fn [n] (some #(.startsWith % SAT-name) (take names n)))
                dummy (println "###\nSAT-name: " SAT-name   "\ntop 5 names: " names "\ntop 5 meds: " (map #(% :med) (take 5 matches)))
                top-matches (loop [i (int 0)
                                   r []]
                              (if (>= i tops)
                                r
                                (if (.startsWith (names i) SAT-name)
                                  (into r (repeat (- tops i) true))  ; the rest are all true
                                  (recur (inc i) (into [false] r)))))
                true-positives (filter #(and
                                          (% :correct)
                                          (.startsWith (% :SAT-name) SAT-name))
                                       matches)
                true-negatives (filter #(and
                                          (not (% :correct))
                                          (not (.startsWith (% :SAT-name) SAT-name)))
                                       matches)
                false-positives (filter #(and
                                           (% :correct)
                                           (not (.startsWith (% :SAT-name) SAT-name)))
                                        matches)
                false-negatives (filter #(and
                                           (not (% :correct))
                                           (.startsWith (% :SAT-name) SAT-name))
                                        matches)]
            (println "top matches for " SAT-name " : " (count top-matches) top-matches)
            (assoc m
              SAT-name
              [(top-matches 0)
               (top-matches 1)
               (top-matches 2)
               (top-matches 3)
               (top-matches 4)
               (count true-positives)
               (count false-positives)
               (count true-negatives)
               (count false-negatives)
               (/ (count false-positives) (+ (count false-positives) (count true-negatives))) ; False positive rate
               (let [divisor (+ (count false-negatives) (count true-positives))]
                 (if (= 0 divisor)
                   Double/MAX_VALUE
                   (/ (count false-negatives) divisor))) ; False negative rate
               (let [divisor (+ (count false-negatives) (count true-positives))]
                 (if (= 0 divisor)
                   Double/MAX_VALUE
                   (- 1 (/ (count false-negatives) divisor)))) ; True positive rate
               (.length vs)])))
        (sorted-map)
        (Compare/createPipeChains (.getRootProjectThing project) (.getRootLayerSet project) regex-exclude)))))

(defn summarize-as-confusion-matrix
  "Takes the results of quantify-all and returns the confusion matrix as a map."
  [qa]
  (reduce
    (fn [m results]
      (merge-with + m {:true-positives (results 5)
                       :false-positives (results 6)
                       :true-negatives (results 7)
                       :false-negatives (results 8)}))
    {:true-positives 0
     :false-positives 0
     :true-negatives 0
     :false-negatives 0}
    (vals qa)))

(defn summarize-as-distributions
  "Takes the results of quantify-all and returns four vectors representing four histograms,
  one for each distribution of true-positives, false-positives, true-negatives and false-negatives."
  [qa]
  (reduce
    (fn [m results]
      (merge-with (fn [m1 m2]
                    (merge-with + m1 m2))
                  m
                  {:true-positives {(results 5) 1}
                   :false-positives {(results 6) 1}
                   :true-negatives {(results 7) 1}
                   :false-negatives {(results 8) 1}}))
    {:true-positives (sorted-map)
     :false-positives (sorted-map)
     :true-negatives (sorted-map)
     :false-negatives (sorted-map)}
    (vals qa)))

(defn summarize-quantify-all
  "Takes the results of quantify-all and returns a map of two maps,
  one with the confusion matrix and one with the distribution of true/false-positive/negative matches."
  [project lib regex-exclude delta direct substring]
  (let [qa (quantify-all project lib regex-exclude delta direct substring)]
    {:confusion-matrix (summarize-as-confusion-matrix qa)
     :distributions (summarize-as-distributions)}))


(defn print-quantify-all [t]
  (doseq [[k v] t]
     (print k \tab)
     (doseq [x (take 5 v)] (print x \tab))
     (doseq [x (nthnext v 5)] (print (float x) \tab))
     (print \newline)))


(defn find-rev-match
  "Take a set of non-homonymous VectorString3D.
  Do pairwise comparisons, in this fashion:
  1. reverse vs2
  2. substring length SL =  0.5 * max(len(vs1), len(vs2))
  3. for all substrings of len SL of vs1, match against all of vs2.
  4. Record best result in a set sorted by mean euclidean distance of the best match.
  Assumes chains are registered and resampled.
  Will ignore cases where the shorter chain is shorter than half the longer chain."
  [chains delta]
  (reduce
    (fn [s chain1]
      (let [rvs1 (let [v (.clone (.vs chain1))]
                   (.reverse v)
                   (.resample v delta)
                   v)
            len1 (.length rvs1)]
        (conj s (reduce
                    ; Find best reverse submatch for chain1 in chains
                    (fn [best chain2]
                      (println "Processing " (.getShortCellTitle chain1) " versus " (.getShortCellTitle chain2))
                      (let [vs2 (.vs chain2)
                            len2 (.length vs2)
                            SL (int (/ (max len1 len2) 2))]
                        (if (or (= chain1 chain2) (< len1 SL) (< len2 SL))
                          best ; A chain has to be longer than half the length of the other to be worth looking at
                          (let [b (reduce
                                    ; Find best reverse submatch between chain1 and chain2
                                    (fn [best-rsub start]
                                      (let [bm (Compare/findBestMatch (.substring rvs1 start (+ start SL))
                                                                      vs2
                                                                      delta
                                                                      false 0 0
                                                                      Compare/AVG_PHYS_DIST   ; Also known as mean euclidean distance
                                                                      true true
                                                                      1.1 1.1 1.0)]
                                        (if (and
                                              best-rsub
                                              (< (best-rsub :med) (get bm 1)))
                                          best-rsub
                                          {:chain1 chain1 :chain2 chain2 :med (get bm 1)})))
                                    {:med Double/MAX_VALUE}
                                    (range (- len1 SL)))]
                            (if (and
                                  best
                                  (< (best :med) (b :med)))
                              best
                              b)))))
                    {:med Double/MAX_VALUE} ; bogus initial element
                    chains))))
    (sorted-set-by
      #(int (- (%1 :med) (%2 :med)))
      {:med Double/MAX_VALUE}) ; adding a bogus initial element
    chains))


(defn find-reverse-match
  "Takes all the chains of a project and compares them all-to-all pairwise with one of the two reversed.
  Will calibrate the chains and resample them to delta."
  [project delta regex-exclude]
  (let [cal (.. project getRootLayerSet getCalibrationCopy)]
    (find-rev-match (map
                        (fn [chain]
                          (.calibrate (.vs chain) cal)
                          (.resample (.vs chain) delta)
                          chain)
                        (Compare/createPipeChains (.getRootProjectThing project) (.getRootLayerSet project) regex-exclude))
                      delta)))

(defn print-reversed-match
  "Prints the sorted set returned by find-reversed-similar"
  [s]
  (doseq [m s]
    (let [c1 (m :chain1)
          c2 (m :chain2)]
      (if c1
        (println (.getShortCellTitle c1) (.getShortCellTitle c2) (m :med))))))


(defn print-stats
  "Prints the number of unique SATs and the number of unique lineages in a library of SATs.
  Returns the two sets in a map."
  [lib]
  (let [lib (fetch-lib lib)
        unique-sats (reduce
                      (fn [unique clone]
                        (if (> (.indexOf clone (int \space)) -1)
                          (conj unique (.substring clone 0 (.indexOf clone (int \space))))
                          unique))
                      (sorted-set)
                      (keys (lib :SATs)))
        unique-lineages (reduce
                          (fn [unique clone]
                            (if (> (.indexOf clone (int \:)) -1)
                              (conj unique (.substring clone 0 (.indexOf clone (int \:))))
                              unique))
                          (sorted-set)
                          unique-sats)]
    (println "Unique SATs: " (count unique-sats) \newline "Unique lineages: " (count unique-lineages))
    {:unique-sats unique-sats
     :unique-lineages unique-lineages}))

(defn- test
  []
  (identify (first (ini.trakem2.display.Display/getSelected))
            {"title" "3rd instar"
             "reference" "FRT42 new"
             "filepath" "/home/albert/Programming/fiji/plugins/SAT-lib-Drosophila-3rd-instar.clj"}))
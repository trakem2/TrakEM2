; Albert Cardona 2009
; Asks for a directory and recursively opens all XML files in it,
; creating a new hidden TrakEM2 project from it,
; and then if it has fiducial points, reads them out
; and reads all lineages from it.

(ns lineage.identify
  (:import (ini.trakem2.analysis Compare)
           (ini.trakem2 Project ControlWindow)
           (ini.trakem2.display Line3D Pipe Polyline)
           (ij.text TextWindow)
           (ij.io DirectoryChooser)
           (java.io File FilenameFilter StringWriter)))

(def
  #^{:doc "The lineages to ignore from all open projects"}
  regex-exclude "(.*unknown.*)|(.*poorly.*)|(.*MB.*)|(.*TR.*)")

(defn gather-chains
  "Collect and calibrate all possible calibrated VectorString chains from all lineages in project"
  [project]
  (let [ls (.getRootLayerSet project)
        cal (.getCalibrationCopy ls)]
    (map
      (fn [chain]
        (.calibrate (.vs chain) cal)
        chain)
      (Compare/createPipeChains (.getRootProjectThing project) ls regex-exclude))))

(defn gather-mb
  "Take the mushroom body of the project and store it as two chains,
  one titled 'peduncle + dorsal lobe' and another 'peduncle + medial lobe'."
  [project]
  (let [ls (.getRootLayerSet project)
        cal (.getCalibrationCopy ls)]
    (if-let [peduncle (.findChild (.getRootProjectThing project) "peduncle")]
      (let [medial-lobe (.findChild peduncle "medial lobe")
            dorsal-lobe (.findChild peduncle "dorsal lobe")
            c1 (ini.trakem2.analysis.Compare$Chain. (.getObject (first (.findChildrenOfType peduncle "pipe"))))
            c2 (.duplicate c1)]
        (.append c1 (.getObject (first (.findChildrenOfType medial-lobe "pipe"))))
        (.append c2 (.getObject (first (.findChildrenOfType dorsal-lobe "pipe"))))
        (map
          (fn [chain title]
            (set! (.title chain) title)
            (.calibrate (.vs chain) cal)
            chain)
          [c1 c2]
          ["peduncle + medial lobe" "peduncle + dorsal lobe"]))
      ; Else empty list
      [])))

(defn gather-fiducials
  "Extract a table of calibrated fiducial points in project,
  in the form {<name> [x y z] ...}"
  [project]
  (if-let [fids (first (.. project getRootProjectThing (findChildrenOfTypeR "fiducial_points")))]
    (reduce
      (fn [m e]
        (let [t (.getValue e)]
          (assoc m (.getKey e) [(.x t) (.y t) (.z t)])))
            {}
            (Compare/extractPoints fids))
    nil))

(defn gather-xmls
  "Scan a folder for XML files, recursively."
  [dir regex-exclude]
  (reduce
    (fn [v filename]
      (if (.isDirectory (File. dir filename))
        (into v (gather-xmls (str dir \/ filename) regex-exclude))
        (if (.endsWith (.toLowerCase filename) ".xml")
          (conj v (str dir \/ filename))
          v)))
    []
    (.list (File. dir)
         (proxy [FilenameFilter] []
           (accept [fdir filename]
             (and (not (.isHidden (File. fdir filename)))
                  (nil? (re-matches (re-pattern regex-exclude) (str (.getAbsolutePath fdir) \/ filename)))))))))

(defn fix-title
  "Takes a title like 'DALv2 [lineage] #123 FRT42D-BP106'
  and returns the title without the word in the last set of brackets, like:
  'DALv2 #123 FRT42D-BP106'"
  [title]
  (let [i-last (.lastIndexOf title (int \]))]
    (if (= -1 i-last)
      title
      (let [i-first (.lastIndexOf title (int \[) (dec i-last))]
        (if (= -1 i-first)
          title
          (str (.substring title 0 i-first) (.substring title (inc i-last))))))))


(defn gather-SATs
  "Take a list of chains, each one representing a SAT,
  and return a table of tables, like:
  {\"DPLpv\" {:x [...] :y [...] :z [...]}}"
  [project]
  (reduce
    (fn [m chain]
      (assoc m
             (fix-title (.getCellTitle chain))
             {:x (seq (.getPoints (.vs chain) 0))
              :y (seq (.getPoints (.vs chain) 1))
              :z (seq (.getPoints (.vs chain) 2))}))

    {}
    (into (gather-chains project) (gather-mb project))))

(defn generate-SAT-lib
  "Create the SAT library from a root directory.
  Will include all XML in any subfolder, recursively."
  [xmls]
  (reduce
    (fn [m xml-path]
      (let [project (Project/openFSProject xml-path false)
            fids (gather-fiducials project)]
        (if (and
              fids
              (not (empty? fids)))
          (let [r (assoc m
                         (.toString project)
                         {:source xml-path
                          :fiducials fids
                          :SATs (gather-SATs project)})]
            (.destroy project)
            r)
          ; Else, ignore
          (do
            (.destroy project)
            (println "No fiducials found in" xml-path)
            m))))
    {}
    xmls))

(defn start
  "Will ignore any of the xml files in the chosen dir whose absolute file path matches the regex-exclude string."
  ([regex-exclude]
    (if-let [dir (.getDirectory (DirectoryChooser. "Choose root dir"))]
      (start dir regex-exclude)))
  ([dir regex-exclude]
    (ControlWindow/setGUIEnabled false)
    (try
      (TextWindow. "SAT-lib.clj"
                   (let [sw (StringWriter.)]
                     (binding [*out* sw]
                       (prn (generate-SAT-lib (gather-xmls dir regex-exclude))))
                       (.toString sw))
                    400 400)
      (catch Exception e
        (.printStackTrace e)))
    (ControlWindow/setGUIEnabled true)))


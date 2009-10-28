; Albert Cardona 2009
; Asks for a directory and recursively opens all XML files in it,
; creating a new hidden TrakEM2 project from it,
; and then if it has fiducial points, reads them out
; and reads all lineages from it.

(ns lineage.identify
  (:import (ini.trakem2.vector Compare)
           (ini.trakem2 Project ControlWindow)
           (ini.trakem2.display Line3D Pipe Polyline)
           (ij.text TextWindow)
           (ij.io DirectoryChooser)
           (java.io File FilenameFilter StringWriter))
  (:use clojure.contrib.pprint))

(def
  #^{:doc "The lineages to ignore from all open projects"}
  regex-exclude "(.*unknown.*)|(.*poorly.*)|(.*MB.*)|(.*TR.*)")

(defn gather-chains
      "Collect all possible calibrated VectorString chains from all lineages in project"
      [project]
      (let [ls (.getRootLayerSet project)
            cal (.getCalibrationCopy ls)]
        (map
          (fn [chain]
            (.calibrate (.vs chain) cal)
            chain)
          (Compare/createPipeChains (.getRootProjectThing project) ls regex-exclude))))

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
  [dir]
  (reduce
    (fn [v name]
      (if (.isDirectory (File. dir name))
        (into v (gather-xmls (str dir \/ name)))
        (if (.endsWith (.toLowerCase name) ".xml")
          (conj v (str dir \/ name))
          v)))
    []
    (.list (File. dir)
         (proxy [FilenameFilter] []
           (accept [fdir name]
             (not (.isHidden (File. fdir name))))))))

(defn gather-SATs
  "Take a list of chains, each one representing a SAT,
  and return a table of tables, like:
  {\"DPLpv\" {:x [...] :y [...] :z [...]}}"
  [project]
  (reduce
    (fn [m chain]
      (assoc m
             (.getCellTitle chain)
             {:x (seq (.getPoints (.vs chain) 0))
              :y (seq (.getPoints (.vs chain) 1))
              :z (seq (.getPoints (.vs chain) 2))}))

    {}
    (gather-chains project)))

(defn generate-SAT-lib
  "Create the SAT library from a root directory.
  Will include all XML in any subfolder, recursively."
  [root-dir]
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
    (gather-xmls root-dir)))

(defn start
  ([]
    (if-let [dir (.getDirectory (DirectoryChooser. "Choose root dir"))]
      (start dir)))
  ([dir]
    (ControlWindow/setGUIEnabled false)
    (try
      (TextWindow. "SAT lib"
                   (let [sw (StringWriter.)]
                     (binding [*out* sw]
                       (prn (generate-SAT-lib dir))) ; both prn and pprint result in unreadable file: too large!
                       (.toString sw))
                    400 400)
      (catch Exception e
        (.printStackTrace e)))
    (ControlWindow/setGUIEnabled true)))


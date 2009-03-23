; A set of test functions for Patch class
;
; In fiji, load this file to the Clojure Interpter like:
; (load-file (str (System/getProperty "user.dir") "/TrakEM2/test/patch.clj"))
;
; ... and then enter its namespace with:
; (in-ns 'fiji.trakem2.tests.patch)
;
; Then just execute any of the functions.


(ns test.patch
  (:import (ij.gui OvalRoi Roi ShapeRoi)
           (ij IJ ImagePlus)
           (ij.process ByteProcessor)
           (ini.trakem2.display Display Patch Selection)
           (mpicbg.trakem2.transform MovingLeastSquaresTransform)))

(defn punch-alpha-hole
  "Create an alpha hole right in the middle of an image."
  [patch]
  (let [ip (.getImageProcessor patch)
        w (int (.getOWidth patch))
        h (int (.getOHeight patch))
        mask (ByteProcessor. w h)
        sroi (.not (ShapeRoi. (Roi. 0 0 w h))
                   (ShapeRoi. (OvalRoi. (/ w 4) (/ h 4) (/ w 2) (/ h 2))))]
    (doto mask
      (.setRoi sroi)
      (.setValue 255)
      (.fill (.getMask mask)))
    (.setAlphaMask patch mask)))


(defn punch-hole
  "Punch an alpha hole into the selected and active Patch, if any,
  and update its mipmaps and repaint."
  []
  (if-let [front (Display/getFront)]
    (if-let [p (.getActive front)]
      (if (isa? (class p) Patch)
        (do
          (punch-alpha-hole p)
          (.updateMipmaps p)
          (Display/repaint))
        (println "Not a Patch:" p))
      (println "Nothing selected"))
    (println "No Display open")))

(defn redo-mipmaps
  "Regenerate mipmaps for all selected Patch, if any."
  []
  (if-let [front (Display/getFront)]
    (if-let [patches (.. front getSelection (getSelected Patch))]
      (if (empty? patches)
        (println "No Patch selected")
        (do
          (doseq [p patches]
            (.updateMipmaps p))
          (Display/repaint))))
    (println "No Display open")))

(defn get-active
  "Returns the active Patch, if any."
  []
  (if-let [front (Display/getFront)]
    (if-let [a (.getActive front)]
      (if (isa? (class a) Patch)
        a
        (println "Active is not a Patch."))
      (println "No active!"))
    (println "No Display open")))

(defn full
  "Puts a new CoordinateTransform, a new Alpha mask and min,max values as desired."
  [patch ip-min ip-max]
  (punch-alpha-hole patch)
  (.setMinAndMax patch (double ip-min) (double ip-max))
  (let [w (int (.getOWidth patch))
        h (int (.getOHeight patch))
        mls (MovingLeastSquaresTransform.)]
    ; Intrude top-left and bottom-right corners by 100,100 pixels
    (.init mls (str "rigid 1 "
                    "0 0 100 100 "
                    w " " h " " (- w 100) " " (- h 100)))
    (.setCoordinateTransform patch mls)))

(defmacro exec
  "Executes the function on the active patch, if any,
  and then updates the mipmaps and repaints.
  For example, call:
  (exec full 0 255)"
  [f & args]
  `(if-let [patch# (get-active)]
     (do
       (~f patch# ~@args)
       (.updateMipmaps patch#)
       (Display/repaint))))


(defn restore
  "Restores an image min,max, removes any alpha,
  and removes any coordinate transform."
  [patch]
  ; 1 - Set min and max to defaults
  (let [type (.getType patch)]
      (if (or
            (== type ImagePlus/GRAY8)
            (== type ImagePlus/COLOR_256)
            (== type ImagePlus/COLOR_RGB))
        (.setMinAndMax patch 0 255)
        (let [ip (.getImageProcessor patch)]
          (.findMinAndMax ip)
          (.setMinAndMax patch (.getMin ip) (.getMax ip)))))
  ; Remove alpha mask
  (.setAlphaMask patch nil)
  ; Remove CoordinateTransform
  (.setCoordinateTransform patch nil))

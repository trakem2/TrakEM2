; A set of test functions for Patch class
;
; In fiji, load this file to the Clojure Interpter like:
; (load-file (str (System/getProperty "user.dir") "/TrakEM2/test/patch.clj"))
;
; ... and then enter its namespace with:
; (in-ns 'fiji.trakem2.tests.patch)
;
; Then just execute any of the functions.


(ns fiji.trakem2.tests.patch
  (:import (ij.gui OvalRoi Roi ShapeRoi)
           (ij.process ByteProcessor)
           (ini.trakem2.display Display Patch Selection)))

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
    (doto patch
      (.setAlphaMask mask)
      (.updateMipmaps))
    (Display/repaint)))
 
(defn punch-hole
  "Punch an alpha hole into the selected and active Patch, if any."
  []
  (if-let [front (Display/getFront)]
    (if-let [p (.getActive front)]
      (if (isa? (class p) Patch)
        (punch-alpha-hole p)
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



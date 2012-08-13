(ns treeline.graph_editor
  (:import
     (cytoscape Cytoscape)
     (cytoscape.data Semantics)
     (ding.view NodeContextMenuListener)
     (csplugins.layout.algorithms.hierarchicalLayout HierarchicalLayoutAlgorithm)
     (javax.swing JFrame JScrollPane JMenuItem JPanel)
     (java.awt Dimension)
     (java.awt.event ActionListener KeyAdapter KeyEvent)))

(defmacro report
  [& args]
  `(ij.IJ/log (str ~@args)))

(defmacro create-node
  [net title]
  `(let [node# (Cytoscape/getCyNode ~title true)]
    (.addNode ~net node#)
    node#))

(let [id (ref 0)]
  (defn next-id
    []
    (dosync (alter id inc))))

(defn create-nodes
  "Create a list of maps, where each map contains a :node and potentially a :branch,
  the latter containing another list of maps, etc."
  [net branch]
  ;(println "branch: "branch)
  (let [points (.getPoints branch)
        branches (if-let [bm (.getBranches branch)]
                   (into {} bm)
                   nil)
        branch-id (next-id)]
    (map
      (fn [i x y layer-id]
        (let [m {:x x :y y :layer-id layer-id
                 :node (create-node net (str branch-id \- i))}]
          (if branches
            (if-let [bs (branches i)]
              (assoc m :branches (map
                                   #(create-nodes net %)
                                   (into [] bs)))
              m)
            m)))
      (range (count (get points 0)))
      (seq (get points 0))
      (seq (get points 1))
      (seq (get points 2)))))


(let [node-type "pp"]
  (defn create-edge
    [node1 node2]
    (Cytoscape/getCyEdge node1 node2 Semantics/INTERACTION node-type true)))
  
(defn create-edges
  [net nodes]
  (loop [prev-node (first nodes)
         next-nodes (next nodes)]
    (do
      ;(println "prev-node is" prev-node)
      (doseq [b (prev-node :branches)]
        (create-edges net b)
        ;(println "b is " b)
        (.addEdge net (create-edge (prev-node :node)
                                   ((first b) :node))))
      (let [next-node (first next-nodes)]
        (if (not next-node)
          nil
          (do
            (.addEdge net (create-edge (prev-node :node) (next-node :node)))
            (recur next-node (next next-nodes))))))))

; WRONG: first node doesn't get checked for branches
;  (dorun
;    (map
;      (fn [m1 m2]
;        (.addEdge net (create-edge (m1 :node) (m2 :node)))
;        (doseq [b (m2 :branches)]
;          (println "going to use branch " b)
;          (.addEdge net (create-edge (m2 :node) ((first (create-edges net b)) :node))))))
;       nodes
;       (rest nodes))))

(defn create-tree
  [net tline]
  (let [nodes (create-nodes net (.getRoot tline))]
    (dorun (create-edges net nodes))
    nodes))

;  (reduce
;    (fn [v m]
;      (.addEdge net (create-edge ((get v (dec (count v))) :node) (m :node)))
;      (if-let [bs (m :branches)]
;        (doseq [b bs]
;          (.addEdge net (create-edge (m :node) (first (create-tree b)))))))
;    (create-nodes net branch)))

(defn create-graph
  "Creates a graph of cytoscape nodes that show all points of a Treeline."
  [tline]
  (let [net (Cytoscape/createNetwork (.getTitle tline) false)
        nodes (create-tree net tline)
        view (Cytoscape/createNetworkView net (.getTitle tline) (HierarchicalLayoutAlgorithm.))]
    [net nodes view]))

(defn show
  "Takes any Component and shows it inside a JFrame."
  [component title]
  (doto (JFrame. title)
    (.add component)
    (.pack)
    (.setVisible true)))

(defn with-scroll
  "Takes a Component and returns a JScrollPane of dimensions w,h that shows it."
  [component w h]
  (doto (JScrollPane. component)
    (.setPreferredSize (Dimension. w h))))

(defn as-graph
  "Show the given treeline as a tree of nodes.
  Add mouse event hooks to each node to show a contextual menu."
  [tline]
  (if tline
    (let [[net nodes view] (create-graph tline)
          frame (JFrame. (.getTitle tline))
          panel (JScrollPane. (.getComponent view))]
      (doto panel
        (.setPreferredSize (Dimension. 500 500)))
      (.. frame getContentPane (add panel))
      (doto frame
        (.pack)
        (.setVisible true))
      (doto view
        ;(.fitContent)
        (.addNodeContextMenuListener
          (let [worker (agent nil)
                items {(JMenuItem. "Fit graph") #(.fitContent view)
                       (JMenuItem. "Select in 2D") #(report "Selected not yet implemented")}
                listener (proxy [ActionListener] []
                           (actionPerformed [evt]
                            (send-off worker
                              (fn [_]
                                (try
                                  (if-let [action (items (.getSource evt))]
                                    (action)
                                    (report "Action not found for " (.getCommand evt))))))))]
            (doseq [item (keys items)] (.addActionListener item listener))
            (proxy [NodeContextMenuListener] []
              (addNodeContextMenuItems [nodeView menu]
                (.removeAll menu)
                (doseq [item (keys items)] (.add menu item)))))))
      (doto (.getComponent view)
        (.addKeyListener
          (proxy [KeyAdapter] []
            (keyPressed [evt]
              (try
                (println "key pressed: " (.getKeyChar evt))
                (if (= KeyEvent/VK_F (.getKeyCode evt))
                  (.fitContent view))
                (catch Exception e (.printStackTrace e)))))))
      view)
    (report "Null Treeline!")))

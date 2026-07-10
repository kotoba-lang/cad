(ns kotoba.cad.viewport
  "GPU scene viewport. WebGPU is preferred; Three's WebGL2 backend is the
  compatibility path for browsers that have not enabled navigator.gpu yet."
  (:require [reagent.core :as r]
            ["three" :as THREE]
            ["three/webgpu" :refer [WebGPURenderer]]
            ["three/addons/controls/OrbitControls.js" :refer [OrbitControls]]))

(defn- dispose-object! [object]
  (js* "~{}.traverse(~{})" object
       (fn [child]
         (when (.-isMesh child)
           (some-> child .-geometry .dispose)
           (some-> child .-material .dispose)))))

(defn- mesh! [geometry material]
  (let [mesh (THREE/Mesh. geometry material)]
    (set! (.-castShadow mesh) true)
    (set! (.-receiveShadow mesh) true)
    mesh))

(defn- add-house! [group color]
  (let [wall (mesh! (THREE/BoxGeometry. 3.4 2.4 2.8) (THREE/MeshStandardMaterial. #js {:color color :roughness 0.82}))
        roof (mesh! (THREE/ConeGeometry. 2.9 1.6 4) (THREE/MeshStandardMaterial. #js {:color "#563d32" :roughness 0.92}))
        glass (mesh! (THREE/BoxGeometry. 0.7 0.9 0.04) (THREE/MeshStandardMaterial. #js {:color "#b9e9ed" :metalness 0.1 :roughness 0.12}))]
    (.position.set wall 0 1.2 0)
    (.position.set roof 0 3.15 0)
    (.rotation.set roof 0 (/ js/Math.PI 4) 0)
    (.position.set glass 0 1.35 1.43)
    (.add group wall) (.add group roof) (.add group glass)))

(defn- add-tree! [group color]
  (let [trunk (mesh! (THREE/CylinderGeometry. 0.16 0.23 1.7 8) (THREE/MeshStandardMaterial. #js {:color "#5c3c27"}))
        crown-a (mesh! (THREE/IcosahedronGeometry. 1.25 2) (THREE/MeshStandardMaterial. #js {:color color :roughness 1}))
        crown-b (mesh! (THREE/IcosahedronGeometry. 0.85 2) (THREE/MeshStandardMaterial. #js {:color "#95c878" :roughness 1}))]
    (.position.set trunk 0 0.85 0)
    (.position.set crown-a -0.2 2.15 0)
    (.position.set crown-b 0.6 2.45 -0.15)
    (.add group trunk) (.add group crown-a) (.add group crown-b)))

(defn- add-water! [group color]
  (let [water (mesh! (THREE/CircleGeometry. 2.15 48)
                      (THREE/MeshPhysicalMaterial. #js {:color color :metalness 0.05 :roughness 0.08 :transparent true :opacity 0.82}))]
    (.rotation.set water (- (/ js/Math.PI 2)) 0 0)
    (.position.set water 0 0.03 0)
    (.add group water)))

(defn- add-light! [group]
  (let [fixture (mesh! (THREE/SphereGeometry. 0.18 16 16) (THREE/MeshStandardMaterial. #js {:color "#f6c957" :emissive "#f6b13a" :emissiveIntensity 2}))
        light (THREE/PointLight. "#ffdc8b" 20 14 2)]
    (.position.set fixture 0 2.8 0)
    (.position.set light 0 3 0)
    (.add group fixture) (.add group light)))

(defn- add-landscape! [group color]
  (let [path (mesh! (THREE/BoxGeometry. 5.2 0.08 1.25) (THREE/MeshStandardMaterial. #js {:color color :roughness 1}))]
    (.position.set path 0 0.04 0) (.rotation.set path 0 -0.25 0) (.add group path)))

(defn- add-node! [scene {:keys [id kind x y color]}]
  (let [group (THREE/Group.)]
    (set! (.. group -userData -sceneId) (name id))
    (.position.set group (- (/ x 7) 7) 0 (- 8 (/ y 8)))
    (case kind
      :building (add-house! group color)
      :vegetation (add-tree! group color)
      :water (add-water! group color)
      :landscape (add-landscape! group color)
      :light (add-light! group)
      (add-tree! group color))
    (.add scene group)
    group))

(defn- selected-helper! [state group]
  (when-let [old (:selection @state)] (.remove (:scene @state) old) (dispose-object! old))
  (when group
    (let [helper (THREE/BoxHelper. group "#d9ff63")]
      (.add (:scene @state) helper)
      (swap! state assoc :selection helper))))

(defn- sync-scene! [state {:keys [scene selected time weather]}]
  (let [{:keys [world scene-root sun]} @state]
    (doseq [child (array-seq (.-children scene-root))] (.remove scene-root child) (dispose-object! child))
    (let [nodes (into {} (map (fn [node] [(:id node) (add-node! scene-root node)]) scene))]
      (swap! state assoc :nodes nodes)
      (selected-helper! state (get nodes selected)))
    (.position.set sun (- (* time 0.38) 4) (+ 3 (* (js/Math.sin (/ (* time js/Math.PI) 24)) 12)) 5)
    (set! (.-background world) (THREE/Color. (case weather :night "#071429" :rain "#607a82" :overcast "#9aacad" "#8ec9dc")))))

(defn- resize! [state]
  (let [{:keys [canvas renderer camera]} @state
        rect (.getBoundingClientRect canvas)
        width (max 1 (.-width rect))
        height (max 1 (.-height rect))]
    (.setSize renderer width height false)
    (set! (.-aspect camera) (/ width height))
    (js* "~{}.updateProjectionMatrix()" camera)))

(defn- select-at! [canvas camera scene-root on-select event]
  (let [rect (.getBoundingClientRect canvas)
        pointer (THREE/Vector2. (- (* 2 (/ (- (.-clientX event) (.-left rect)) (.-width rect))) 1)
                                (- 1 (* 2 (/ (- (.-clientY event) (.-top rect)) (.-height rect)))))
        raycaster (THREE/Raycaster.)]
    (.setFromCamera raycaster pointer camera)
    (when-let [hit (first (array-seq (.intersectObjects raycaster (.-children scene-root) true)))]
      (loop [node (.-object hit)]
        (when node
          (if-let [id (.. node -userData -sceneId)]
            (on-select (keyword id))
            (recur (.-parent node))))))))

(defn- init! [canvas props state]
  (let [world (THREE/Scene.)
        renderer (WebGPURenderer. #js {:canvas canvas :antialias true :forceWebGL (not (exists? js/navigator.gpu) )})
        camera (THREE/PerspectiveCamera. 42 1 0.1 120)
        controls (OrbitControls. camera canvas)
        scene-root (THREE/Group.)
        sun (THREE/DirectionalLight. "#fff2c7" 3.2)
        ground (mesh! (THREE/PlaneGeometry. 48 48) (THREE/MeshStandardMaterial. #js {:color "#668e5e" :roughness 0.95}))
        state-data {:canvas canvas :world world :scene world :renderer renderer :camera camera :controls controls :scene-root scene-root :sun sun}]
    (.position.set camera 12 10 16)
    (.set (.-target controls) 0 1 0)
    (.update controls)
    (set! (.-enabled (.-shadowMap renderer)) true)
    (.position.set sun 4 10 5)
    (set! (.-castShadow sun) true)
    (.rotation.set ground (- (/ js/Math.PI 2)) 0 0)
    (.add world (THREE/HemisphereLight. "#d9efff" "#30472e" 2.1))
    (.add world sun) (.add world ground) (.add world scene-root)
    (reset! state state-data)
    (resize! state)
    (sync-scene! state props)
    (.addEventListener canvas "click" #(select-at! canvas camera scene-root (:on-select props) %))
    (letfn [(frame []
              (when (:alive? @state)
                (.update controls)
                (.render renderer world camera)
                (swap! state assoc :raf (js/requestAnimationFrame frame))))]
      (swap! state assoc :alive? true)
      (let [ready (.init renderer)]
        (.then ready (fn [] (frame)))
        (.catch ready (fn [_] (swap! state assoc :webgpu-error? true)))))
    (.addEventListener js/window "resize" #(resize! state))))

(def scene-viewport
  (let [state (atom nil)
        canvas (atom nil)]
    (r/create-class
     {:display-name "kami-webgpu-viewport"
      :component-did-mount (fn [this] (init! @canvas (second (r/argv this)) state))
      :component-did-update (fn [this _] (when @state (sync-scene! state (second (r/argv this)))))
      :component-will-unmount (fn [_]
                                (when-let [{:keys [renderer world raf]} @state]
                                  (swap! state assoc :alive? false)
                                  (js/cancelAnimationFrame raf)
                                  (dispose-object! world)
                                  (.dispose renderer)))
      :reagent-render (fn [_] [:canvas.viewport-canvas {:ref #(reset! canvas %)
                                                         :aria-label "Interactive Kami WebGPU scene viewport"}])})))

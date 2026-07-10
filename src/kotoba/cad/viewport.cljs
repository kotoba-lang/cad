(ns kotoba.cad.viewport
  "Kami Scene Studio browser host. Rendering is delegated to the sanctioned
   kami-app-scene-studio wasm-bindgen adapter; it owns wgpu and WGSL."
  (:require [reagent.core :as r]))

(defn scene-json [props] (js/JSON.stringify (clj->js props)))

(defn- render-frame! [state]
  (when-let [host (:host @state)]
    (.setScene host (scene-json (:props @state)))
    (.render host (.now js/performance))
    (swap! state assoc :raf (js/requestAnimationFrame #(render-frame! state)))))

(defn- boot! [canvas state]
  (when-let [ctor (aget js/window "KamiSceneStudioWasm")]
    (-> (.create ctor canvas)
        (.then (fn [host] (swap! state assoc :host host) (render-frame! state)))
        (.catch (fn [error] (aset (.-dataset canvas) "kamiError" (str error)))))))

(def scene-viewport
  (let [canvas (atom nil)
        state (atom nil)]
    (r/create-class
     {:display-name "kami-wgsl-viewport"
      :component-did-mount
      (fn [this]
        (reset! state {:props (second (r/argv this))})
        (if (aget js/window "KamiSceneStudioWasm")
          (boot! @canvas state)
          (.addEventListener js/window "kami-scene-studio-wasm-ready" #(boot! @canvas state) #js {:once true})))
      :component-did-update
      (fn [this _]
        (swap! state assoc :props (second (r/argv this))))
      :component-will-unmount
      (fn [_] (when-let [raf (:raf @state)] (js/cancelAnimationFrame raf)))
      :reagent-render
      (fn [_]
        [:canvas.viewport-canvas {:ref #(reset! canvas %)
                                 :aria-label "Kami WGSL WebGPU scene viewport"}])})))

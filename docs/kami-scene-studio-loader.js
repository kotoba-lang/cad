import init, { KamiSceneStudio } from "https://kotoba-lang.github.io/kami-app-scene-studio/pkg/kami_app_scene_studio.js";

await init();
window.KamiSceneStudioWasm = KamiSceneStudio;
window.dispatchEvent(new Event("kami-scene-studio-wasm-ready"));

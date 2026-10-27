import { createApp } from "vue";
import { createRouter, createWebHistory } from "vue-router";
import App from "./App.vue";
import { consoleRoutes } from "./console-routes";
import Public from "./Public.vue";
import Console from "./Console.vue";
import { hasAnalytics, publicPage, trackPage } from './analytics';
import "./style.css";
import "./console-v2.css";
import "./public-v2.css";
const router = createRouter({
  history: createWebHistory(),
  scrollBehavior(to,from,saved){ if(saved)return saved;if(['/','/pricing'].includes(to.path) && ['#product','#units'].includes(to.hash))return {el:to.hash,behavior:'smooth'};if(to.path!==from.path)return {top:0}; },
  routes: [
    ...consoleRoutes(Console),
    { path: '/tutorials/:tutorial', component: Public, meta: { tutorial: true } },
    { path: "/:page?", component: Public },
  ],
});
// Unload Google's script before entering any authenticated, payment or unknown route.
router.beforeEach((to) => {
  if (hasAnalytics() && !publicPage(to.path)) { window.location.assign(to.fullPath); return false; }
});
router.afterEach((to, _from, failure) => { if (!failure) trackPage(to.path); });
createApp(App).use(router).mount("#app");

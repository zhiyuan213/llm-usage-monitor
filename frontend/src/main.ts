import { createApp } from "vue";
import { createRouter, createWebHistory } from "vue-router";
import App from "./App.vue";
import { consoleRoutes } from "./console-routes";
import Public from "./Public.vue";
import Console from "./Console.vue";
import "./style.css";
import "./console-v2.css";
import "./public-v2.css";
const router = createRouter({
  history: createWebHistory(),
  routes: [
    ...consoleRoutes(Console),
    { path: "/:page?", component: Public },
  ],
});
createApp(App).use(router).mount("#app");

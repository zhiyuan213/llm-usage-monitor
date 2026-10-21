import { createApp } from "vue";
import { createRouter, createWebHistory } from "vue-router";
import App from "./App.vue";
import Public from "./Public.vue";
import Console from "./Console.vue";
import "./style.css";
const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: "/app/projects", redirect: "/app/workspace" },
    { path: "/app/:page?/:detail?", component: Console },
    { path: "/:page?", component: Public },
  ],
});
createApp(App).use(router).mount("#app");

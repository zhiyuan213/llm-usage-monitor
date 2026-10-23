import type { Component } from 'vue';
import type { RouteRecordRaw } from 'vue-router';
export function consoleRoutes(component: Component): RouteRecordRaw[] {
  return [
    { path: '/app', redirect: '/app/workspace' },
    { path: '/app/projects', redirect: '/app/workspace' },
    // Requiring page prevents the optional matcher from swallowing the /app redirect.
    { path: '/app/:page/:detail?', component },
  ];
}

import { describe, it, expect } from 'vitest';
import { createMemoryHistory, createRouter } from 'vue-router';
import { consoleRoutes } from './console-routes';
describe('console landing route', () => {
  it('redirects both bare entries while retaining explicit overview and detail navigation', async () => {
    const router=createRouter({history:createMemoryHistory(),routes:consoleRoutes({})});
    for(const path of ['/app','/app/','/app/projects']) {
      await router.push(path);
      expect(router.currentRoute.value.path).toBe('/app/workspace');
    }
    await router.push('/app/overview');
    expect(router.currentRoute.value.params.page).toBe('overview');
    await router.push('/app/trace/example');
    expect(router.currentRoute.value.params.detail).toBe('example');
  });
});

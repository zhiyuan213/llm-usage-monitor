import "vue";
import { beforeEach, describe, it, expect, vi } from "vitest";
describe("language preference and API error handling", () => {
    beforeEach(() => {
        vi.resetModules();
        const storage = new Map();
        vi.stubGlobal("localStorage", {
            getItem: (k) => storage.get(k) || null,
            setItem: (k, v) => storage.set(k, v),
        });
        vi.stubGlobal("location", { search: "" });
        vi.stubGlobal("document", { documentElement: { lang: "" } });
    });
    it("defaults to English, ignores browser locale, persists explicit Chinese", async () => {
        const lib = await import("./lib");
        expect(lib.language.value).toBe("en");
        lib.setLanguage("zh-CN");
        expect(lib.t("Hello", "你好")).toBe("你好");
        expect(localStorage.getItem("metertrail.lang")).toBe("zh-CN");
    });
    it("only accepts whitelisted language query parameters", async () => {
        vi.stubGlobal("location", { search: "?lang=unsafe" });
        const lib = await import("./lib");
        expect(lib.language.value).toBe("en");
    });
    it("sends CSRF header and translates structured server errors", async () => {
        const lib = await import("./lib");
        lib.session.value = { csrf: "test" };
        const mock = vi.fn(async (_url, _init) => new Response(JSON.stringify({ code: "INVALID_VERIFICATION" }), {
            status: 400,
        }));
        vi.stubGlobal("fetch", mock);
        await expect(lib.api("/verification", {})).rejects.toMatchObject({
            code: "INVALID_VERIFICATION",
        });
        expect(mock.mock.calls[0]?.[1]?.headers?.["X-CSRF-Token"]).toBe("test");
    });
});

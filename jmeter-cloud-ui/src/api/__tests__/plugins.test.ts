import { afterEach, describe, expect, it, vi } from "vitest";

import { PluginApiError, pluginsApi } from "../plugins";

function stubFetch(status: number, body: unknown) {
  const fn = vi.fn().mockResolvedValue({
    ok: status >= 200 && status < 300,
    status,
    text: async () => (body === undefined ? "" : JSON.stringify(body)),
  });
  vi.stubGlobal("fetch", fn);
  return fn;
}

afterEach(() => vi.unstubAllGlobals());

describe("pluginsApi", () => {
  it("list returns the plugin array", async () => {
    stubFetch(200, [{ pluginId: "p1", name: "jpgc-casutg", version: "3.1" }]);
    const items = await pluginsApi.list();
    expect(items[0].name).toBe("jpgc-casutg");
  });

  it("a 409 parses code + the colliding row into PluginApiError.existing", async () => {
    stubFetch(409, {
      code: "PLUGIN_NAME_TAKEN",
      message: "name taken",
      existing: { pluginId: "p1", name: "jpgc-casutg", version: "3.1" },
      orphanBlobDeleted: true,
    });
    const err = await pluginsApi
      .create({ name: "jpgc-casutg", version: "9.9", blobId: "b2" })
      .then(() => null, (e: unknown) => e);
    expect(err).toBeInstanceOf(PluginApiError);
    const pe = err as PluginApiError;
    expect(pe.httpStatus).toBe(409);
    expect(pe.code).toBe("PLUGIN_NAME_TAKEN");
    expect(pe.existing).toEqual({ pluginId: "p1", name: "jpgc-casutg", version: "3.1" });
  });

  it("delete treats 204 as success", async () => {
    stubFetch(204, undefined);
    await expect(pluginsApi.delete("p1")).resolves.toBeUndefined();
  });
});

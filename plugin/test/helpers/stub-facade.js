// @ts-check
/** 不涉及 DSH 的路由测试用：外观层的任何操作被调用都直接失败。 */
const fail = async () => { throw new Error('这个测试不应当访问 DSH') }

/** @type {import('../../src/server/routes.js').FacadeApi} */
export const unusedFacade = {
  directories: fail, createDirectory: fail, registerWorkspace: fail,
  workspaces: fail, sessions: fail, models: fail, permissionPresets: fail, messages: fail, usage: fail, plan: fail,
  createSession: fail, prompt: fail, cancel: fail, selectModel: fail, setPermissionPreset: fail, answerAttention: fail,
}

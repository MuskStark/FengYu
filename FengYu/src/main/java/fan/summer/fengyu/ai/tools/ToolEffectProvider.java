package fan.summer.fengyu.ai.tools;

import fan.summer.fengyu.ai.FengYuTool;

/** Lets a multi-method built-in tool declare an effect for each generated callback. */
public interface ToolEffectProvider extends FengYuTool {
    ToolEffect effectFor(String toolName);
}

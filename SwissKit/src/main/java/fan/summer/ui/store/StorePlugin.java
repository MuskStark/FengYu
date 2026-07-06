package fan.summer.ui.store;

import fan.summer.zhiflow.api.IconStyle;
import fan.summer.zhiflow.api.ToolCategory;

/**
 * Lightweight data class representing a plugin available in the online store.
 * Instances are created by parsing the store's JSON catalog. Fields are public
 * and mutable to mirror the JSON shape directly during parsing.
 *
 * @since 1.0
 */
public class StorePlugin {
    public String id;
    public String name;
    public String description;
    public String version;
    public String jarUrl;
    public IconStyle iconStyle;
    public ToolCategory category;
}

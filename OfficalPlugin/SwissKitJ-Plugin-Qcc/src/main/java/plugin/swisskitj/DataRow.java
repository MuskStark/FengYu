package plugin.swisskitj;

import lombok.Data;

@Data
public class DataRow {
        String company      = "";
        String sectionType  = "";
        String seq          = "";
        String name         = "";   // Controller name/organization
        String directPct    = "";
        String totalPct     = "";
        String votePct      = "";
        String controlChain = "";
        String basis        = "";   // Judgment basis (for suspected controller)
}
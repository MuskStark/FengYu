package fan.summer.zhiflow.buildintool.pdftool;

import fan.summer.zhiflow.api.IconStyle;
import fan.summer.zhiflow.api.SwissKitJPlugin;
import fan.summer.zhiflow.api.ToolCategory;
import fan.summer.zhiflow.api.ToolType;
import fan.summer.zhiflow.api.ai.AiTool;
import fan.summer.zhiflow.api.i18n.I18n;
import fan.summer.zhiflow.buildintool.pdftool.ai.PdfMergeAiTool;
import fan.summer.zhiflow.buildintool.pdftool.ai.PdfSplitAiTool;
import fan.summer.zhiflow.buildintool.pdftool.ai.PdfToDocxAiTool;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.util.List;

public class PdfToolPlugin implements SwissKitJPlugin {

    private Node view;

    @Override public String getId()          { return "builtin.pdf-tool"; }
    @Override public String getName()        { return I18n.get("builtin.pdf.name"); }
    @Override public String getDescription() { return I18n.get("builtin.pdf.desc"); }
    @Override public ToolCategory getCategory() { return ToolCategory.OTHER; }
    @Override public String getVersion()     { return "1.0.0"; }
    @Override public String getMdiIcon()     { return "file-pdf-box"; }
    @Override public IconStyle getIconStyle()  { return IconStyle.RED; }
    @Override public ToolType getType()       { return ToolType.BUILTIN; }

    @Override
    public Node createView() {
        if (view != null) return view;

        TabPane tabPane = new TabPane();
        tabPane.getStyleClass().add("sk-tab-pane");
        tabPane.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);

        Tab splitTab = new Tab(I18n.get("builtin.pdf.tab.split"));
        splitTab.setContent(new PdfSplitPane());
        splitTab.setClosable(false);

        Tab mergeTab = new Tab(I18n.get("builtin.pdf.tab.merge"));
        mergeTab.setContent(new PdfMergePane());
        mergeTab.setClosable(false);

        Tab convertTab = new Tab(I18n.get("builtin.pdf.tab.convert"));
        convertTab.setContent(new PdfConvertPane());
        convertTab.setClosable(false);

        tabPane.getTabs().addAll(splitTab, mergeTab, convertTab);

        VBox root = new VBox(tabPane);
        VBox.setVgrow(tabPane, Priority.ALWAYS);
        root.setPadding(new Insets(24));
        root.setStyle("-fx-background-color: transparent;");

        view = root;
        return view;
    }

    @Override
    public List<AiTool> aiTools() {
        return List.of(
            new PdfSplitAiTool(),
            new PdfMergeAiTool(),
            new PdfToDocxAiTool()
        );
    }
}

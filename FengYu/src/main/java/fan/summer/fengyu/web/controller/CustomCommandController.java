package fan.summer.fengyu.web.controller;

import fan.summer.fengyu.ai.commands.CustomCommandRegistry;
import fan.summer.fengyu.ai.workspace.WorkspaceService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Custom slash-command discovery for the composer's {@code /} panel: user-root commands
 * plus the workspace's project commands when {@code conversationId} names a
 * workspace-bound conversation.
 *
 * @since 4.1.0
 */
@RestController
@RequestMapping("/api/ai/commands")
public class CustomCommandController {

    private final CustomCommandRegistry commands;
    private final WorkspaceService workspaces;

    public CustomCommandController(CustomCommandRegistry commands, WorkspaceService workspaces) {
        this.commands = commands;
        this.workspaces = workspaces;
    }

    @GetMapping
    public List<Map<String, Object>> list(@RequestParam(required = false) Long conversationId) {
        var binding = conversationId == null ? null : workspaces.bindingFor(conversationId);
        return commands.commands(binding == null ? null : binding.root()).stream()
                .map(command -> Map.<String, Object>of(
                        "id", command.id(),
                        "name", command.name(),
                        "description", command.description(),
                        "prompt", command.prompt(),
                        "scope", command.scope()))
                .toList();
    }
}

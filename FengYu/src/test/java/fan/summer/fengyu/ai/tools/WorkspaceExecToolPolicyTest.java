package fan.summer.fengyu.ai.tools;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The workspace_exec readonly whitelist (4.1.0): structurally read-only invocations
 * auto-run in every mode; anything with shell metacharacters, write-capable executables,
 * or git write subcommands falls back to the approval path. Fail-closed by design.
 */
class WorkspaceExecToolPolicyTest {

    private static boolean readonly(String command) {
        return WorkspaceExecTool.isReadonlyCommandLine(command);
    }

    @Test
    void structurallyReadonlyCommandsAutoRun() {
        assertTrue(readonly("ls -la"));
        assertTrue(readonly("git status"));
        assertTrue(readonly("git log --oneline -5"));
        assertTrue(readonly("git diff HEAD~1"));
        assertTrue(readonly("cat README.md"));
        assertTrue(readonly("grep -rn TODO src"));
        assertTrue(readonly("find . -name '*.ts'"));
        assertTrue(readonly("rg 'pattern' src && echo done"));
        assertTrue(readonly("diff a.txt b.txt"));
    }

    @Test
    void writeCapableExecutablesNeverAutoRun() {
        assertFalse(readonly("npm install"), "package managers write");
        assertFalse(readonly("python3 -c 'print(1)'"), "interpreters execute arbitrary text");
        assertFalse(readonly("node script.js"), "node runs scripts");
        assertFalse(readonly("sed -i 's/a/b/' file"), "sed -i writes in place");
        assertFalse(readonly("awk '{print > \"out\"}' file"), "awk can redirect");
        assertFalse(readonly("mvn test"), "build runners write target/");
        assertFalse(readonly("pytest -q"), "test runners write caches");
        assertFalse(readonly("echo hi > file"), "redirection writes");
    }

    @Test
    void gitWritesFallBackToApproval() {
        assertFalse(readonly("git commit -m x"));
        assertFalse(readonly("git push"));
        assertFalse(readonly("git checkout main"));
        assertFalse(readonly("git reset --hard"));
    }

    @Test
    void shellMetacharactersAndFindMutatorsFailClosed() {
        assertFalse(readonly("ls; rm -rf /"));
        assertFalse(readonly("cat $(whoami)"));
        assertFalse(readonly("ls `pwd`"));
        assertFalse(readonly("FOO=bar ls"), "env assignments are not read-only forms");
        assertFalse(readonly("find . -delete"), "find -delete mutates");
        assertFalse(readonly("find . -exec rm {} \\;"), "find -exec mutates");
        assertFalse(readonly("ls | sh"), "pipes into a shell execute");
        assertFalse(readonly(""));
    }

    @Test
    void invocationEnvelopeParsing() {
        assertTrue(WorkspaceExecTool.isReadonlyInvocation("{\"command\":\"git status\"}"));
        assertFalse(WorkspaceExecTool.isReadonlyInvocation("{\"command\":\"npm install\"}"));
        assertFalse(WorkspaceExecTool.isReadonlyInvocation("not json"));
        assertFalse(WorkspaceExecTool.isReadonlyInvocation(null));
    }

    /**
     * The symlink vector of the auto-approve whitelist: a RELATIVE operand inside the
     * workspace can name a link whose real target lives outside it — exactly the escape
     * read_file's WorkspacePathPolicy collapses — and must not auto-run. A genuine
     * in-workspace file keeps its readonly verdict.
     */
    @Test
    void inWorkspaceSymlinksPointingOutsideNeverAutoRun(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path root) throws Exception {
        java.nio.file.Path outside = java.nio.file.Files.createTempDirectory("outside-target");
        try {
            java.nio.file.Files.writeString(outside.resolve("secret.txt"), "s");
            try {
                java.nio.file.Files.createSymbolicLink(root.resolve("leak"),
                        outside.resolve("secret.txt"));
            } catch (Exception noSymlinkPrivilege) {
                org.junit.jupiter.api.Assumptions.assumeTrue(false,
                        "symlink creation unavailable: " + noSymlinkPrivilege);
            }
            java.nio.file.Files.writeString(root.resolve("real.txt"), "ok");
            fan.summer.fengyu.ai.workspace.WorkspaceContext.set(
                    new fan.summer.fengyu.ai.workspace.WorkspaceContext.Binding(root, null));
            try {
                assertFalse(WorkspaceExecTool.isReadonlyInvocation("{\"command\":\"cat leak\"}"),
                        "a symlink operand escaping the workspace must require approval");
                assertTrue(WorkspaceExecTool.isReadonlyCommandLine("cat real.txt", root),
                        "a genuine in-workspace operand stays readonly");
                // The lexical checks are unchanged by the base.
                assertFalse(WorkspaceExecTool.isReadonlyCommandLine("cat /etc/passwd", root));
                assertFalse(WorkspaceExecTool.isReadonlyCommandLine("cat ../secret.txt", root));
                assertTrue(WorkspaceExecTool.isReadonlyCommandLine("cat real.txt", root));
                // A glob expands inside the shell AFTER this check and can name the escaping
                // symlink — an unexpanded pattern cannot be proven safe, so it needs approval.
                assertFalse(WorkspaceExecTool.isReadonlyCommandLine("cat *", root),
                        "a glob operand may expand to an escaping symlink: approval required");
                assertFalse(WorkspaceExecTool.isReadonlyCommandLine("cat *.txt", root),
                        "a suffixed glob is equally unprovable pre-expansion");
            } finally {
                fan.summer.fengyu.ai.workspace.WorkspaceContext.clear();
            }
        } finally {
            java.nio.file.Files.deleteIfExists(root.resolve("leak"));
            java.nio.file.Files.deleteIfExists(outside.resolve("secret.txt"));
            java.nio.file.Files.deleteIfExists(outside);
        }
    }

    /** Review R1 P0-1: the output-flag / absolute-operand bypass family must fail closed. */
    @Test
    void outputWritingAndEscapingSpellingsNeverAutoRun() {
        // Output-file flags in both spellings, for every output-capable executable.
        assertFalse(readonly("git diff --output=/tmp/pwned a b"));
        assertFalse(readonly("git diff --output patch.txt a b"));
        assertFalse(readonly("git log --output=/any/path"));
        assertFalse(readonly("git show --output=out.txt HEAD"));
        assertFalse(readonly("tree -o listing.txt"));
        assertFalse(readonly("tree -o /tmp/x"));
        assertFalse(readonly("diff --output=/tmp/d a b"));
        assertFalse(readonly("sort -o out.txt in.txt"), "GNU sort -o writes its output file");
        assertFalse(readonly("sort --output out.txt in.txt"));
        // Operands that read or write outside the jailed cwd.
        assertFalse(readonly("cat ~/.ssh/id_rsa"), "home-relative operand escapes the jail");
        assertFalse(readonly("cat /etc/passwd"), "absolute operand escapes the jail");
        assertFalse(readonly("grep secret ../outside.txt"), "parent-escaping operand");
        assertFalse(readonly("cat src/../../etc/passwd"), "interior .. segments escape the jail");
        assertFalse(readonly("grep secret sub/../../.ssh/id_rsa"));
        assertFalse(readonly("cat src\\..\\..\\etc\\passwd"), "backslash-dressed interior .. segments");
        assertFalse(readonly("cat src/main/../.."), "trailing .. segment escapes the jail");
        assertTrue(readonly("cat src/main/java/Foo.java"), "plain in-workspace relative path stays readonly");
        assertFalse(readonly("grep -r secret /Users"), "absolute search root");
        // Review R2: backslash-dressed escapes normalize before the jail checks.
        assertFalse(readonly("cat \\/etc\\/passwd"), "backslash-escaped absolute operand");
        assertFalse(readonly("cat .\\./etc/passwd"), "backslash-escaped parent escape");
        // Review R2: rg --pre executes a command per searched file.
        assertFalse(readonly("rg --pre sh README.md"), "rg --pre is arbitrary execution");
        assertFalse(readonly("rg --pre-bin sh README.md"));
        // Review R2: write-capable git subcommands and uniq's two-operand write form.
        assertFalse(readonly("git branch feature"), "git branch creates refs");
        assertFalse(readonly("git branch -D main"), "git branch deletes refs");
        assertFalse(readonly("git tag v1.0"), "git tag creates refs");
        assertFalse(readonly("git remote add origin https://example.invalid/repo.git"));
        assertFalse(readonly("git reflog delete HEAD@{1}"));
        assertFalse(readonly("uniq input.txt output.txt"), "uniq's second operand is an output file");
        // Any flag=value spelling is rejected outright (the value is opaque to the whitelist).
        assertFalse(readonly("git log --pretty=format:%h"));
        // grep keeps -o (only-matching): a plain read-only flag for that executable.
        assertTrue(readonly("grep -o pattern file.txt"));
    }

    /** Review R2 P1-3: the catastrophic-command floor must also cover workspace_exec. */
    @Test
    void catastrophicFloorCoversWorkspaceExec() {
        ToolGuardService service = new ToolGuardService(
                new fan.summer.fengyu.ai.hooks.HookDispatcher(), "{}", "[]");
        String destructive = "{\"command\":\"rm -rf / --no-preserve-root\"}";
        assertEquals(ToolGuardService.Verdict.DENY, service.decide("workspace_exec",
                audited("workspace_exec", ToolEffect.COMMAND), destructive,
                AiPermissionMode.FULL_ACCESS, null).verdict(),
                "no mode, rule, or session grant may green-light destruction via workspace_exec");
    }

    /** Review R1 P1-4: an always-allow click scopes COMMAND tools to their command prefix. */
    @Test
    void sessionGrantKeysScopeCommandToolsToTheirPrefix() {
        assertEquals("workspace_exec npm install", ToolGuardService.grantKey(
                "workspace_exec", ToolEffect.COMMAND, "{\"command\":\"npm install pkg\"}"));
        assertEquals("execute_command git push", ToolGuardService.grantKey(
                "execute_command", ToolEffect.COMMAND, "{\"command\":\"git push origin main\"}"));
        assertEquals("write_file", ToolGuardService.grantKey(
                "write_file", ToolEffect.WRITE, "{\"path\":\"a\"}"));
        assertEquals("read_file", ToolGuardService.grantKey(
                "read_file", ToolEffect.READ, "{\"path\":\"a\"}"));
    }

    @Test
    void planModeAsksForEverythingExceptRead() {
        // PLAN mode keeps READ free; everything else asks (approval card = escape hatch).
        assertTrue(ToolApprovalPolicy.requiresApproval(
                audited("write_file", ToolEffect.WRITE), AiPermissionMode.PLAN, "{}"));
        assertTrue(ToolApprovalPolicy.requiresApproval(
                audited("execute_command", ToolEffect.COMMAND), AiPermissionMode.PLAN,
                "{\"command\":\"ls\"}"));
        assertFalse(ToolApprovalPolicy.requiresApproval(
                audited("read_file", ToolEffect.READ), AiPermissionMode.PLAN, "{}"));
        assertFalse(ToolApprovalPolicy.requiresApproval(
                audited("todo_write", ToolEffect.READ), AiPermissionMode.PLAN, "{}"));
    }

    @Test
    void readonlyWorkspaceExecSkipsApprovalInEveryMode() {
        for (AiPermissionMode mode : AiPermissionMode.values()) {
            assertFalse(ToolApprovalPolicy.requiresApproval(
                    audited("workspace_exec", ToolEffect.COMMAND), mode,
                    "{\"command\":\"git status\"}"),
                    "whitelisted workspace_exec auto-runs in " + mode);
        }
        assertTrue(ToolApprovalPolicy.requiresApproval(
                audited("workspace_exec", ToolEffect.COMMAND), AiPermissionMode.ASK_FOR_APPROVAL,
                "{\"command\":\"npm install\"}"),
                "non-whitelisted workspace_exec still asks");
    }

    /**
     * Multi-line scripts (heredocs included) must execute under one shell — the old
     * "One command per call (no newline chains)" rejection forced models into absurd
     * write_file/edit_file contortions to author files. Not readonly (a newline trips
     * UNSAFE_INVOCATION), so the approval layer — not the tool — governs them per mode.
     */
    @org.junit.jupiter.api.Test
    void multilineHeredocRunsAndWritesFiles(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path root) throws Exception {
        fan.summer.fengyu.ai.workspace.WorkspaceContext.set(
                new fan.summer.fengyu.ai.workspace.WorkspaceContext.Binding(root, null));
        try {
            WorkspaceExecTool tool = new WorkspaceExecTool();
            String script = "cat > out.txt <<'EOF'\nline1\nline2\nEOF\ncat out.txt";
            com.fasterxml.jackson.databind.JsonNode result = new com.fasterxml.jackson.databind.ObjectMapper()
                    .readTree(tool.workspaceExec(script, null, 30, null));
            assertTrue(result.path("success").asBoolean(),
                    () -> "multi-line script must run: " + result);
            assertEquals("line1\nline2\n", java.nio.file.Files.readString(root.resolve("out.txt")));
            assertTrue(result.path("output").asText().contains("line2"));
            assertFalse(result.path("readonly").asBoolean(), "heredocs are write-capable");
        } finally {
            fan.summer.fengyu.ai.workspace.WorkspaceContext.clear();
        }
    }

    /**
     * A FULL_ACCESS conversation skips the OS fence (codex danger-full-access + Never
     * pairing): no fence denial mid-execution means no escape-approval prompt interrupting
     * a "full control" run. Other modes keep resolving the fence from the settings.
     */
    @org.junit.jupiter.api.Test
    void fullAccessConversationsSkipTheOsFence(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path root) {
        WorkspaceExecTool tool = new WorkspaceExecTool() {
            @Override protected fan.summer.fengyu.ai.sandbox.PermissionProfile sandboxProfile(
                    java.nio.file.Path workspaceRoot) {
                return super.sandboxProfile(workspaceRoot);
            }
        };
        try (var mocked = org.mockito.Mockito.mockStatic(
                fan.summer.fengyu.ai.AiConfigService.class)) {
            mocked.when(fan.summer.fengyu.ai.AiConfigService::getAiSandboxMode)
                    .thenReturn("workspace-write");
            AiPermissionContext.set(AiPermissionMode.FULL_ACCESS);
            assertTrue(tool.sandboxProfile(root)
                    instanceof fan.summer.fengyu.ai.sandbox.PermissionProfile.FullAccess,
                    "FULL_ACCESS must run unfenced — escape prompts contradict full control");
            AiPermissionContext.set(AiPermissionMode.ASK_FOR_APPROVAL);
            assertTrue(tool.sandboxProfile(root)
                    instanceof fan.summer.fengyu.ai.sandbox.PermissionProfile.WorkspaceWrite,
                    "non-full modes keep honoring the configured sandbox tier");
        } finally {
            AiPermissionContext.clear();
        }
    }

    private static AuditedToolCallback audited(String name, ToolEffect effect) {
        return new AuditedToolCallback() {
            private final org.springframework.ai.tool.definition.ToolDefinition definition =
                    org.springframework.ai.tool.definition.DefaultToolDefinition.builder()
                            .name(name).description(name).inputSchema("{\"type\":\"object\"}").build();
            @Override public org.springframework.ai.tool.definition.ToolDefinition getToolDefinition() {
                return definition;
            }
            @Override public ToolEffect effect() { return effect; }
            @Override public String call(String input) { return input; }
        };
    }
}

package fan.summer.fengyu.ai.sandbox;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The argv/flag-level readonly classifier — every family from the task contract, each
 * direction of the chain combinator, and the conservative negatives (redirection,
 * substitution, expansion, sudo, find mutators, git config writes). The fail direction
 * is pinned everywhere: anything the classifier cannot prove read-only is NOT readonly
 * — one more approval card, never one silent mutation.
 */
class ReadonlyCommandAnalyzerTest {

    private static boolean readonly(String command) {
        return ReadonlyCommandAnalyzer.isReadonly(command);
    }

    // ── file-read family ───────────────────────────────────────────────────────────────

    @Test
    void fileReadFamilyIsReadonly() {
        assertTrue(readonly("cat README.md"));
        assertTrue(readonly("head -n 20 build.log"));
        assertTrue(readonly("tail -f app.log"));
        assertTrue(readonly("less README.md"));
        assertTrue(readonly("more NOTES.txt"));
        assertTrue(readonly("wc -l src/Main.java"));
        assertTrue(readonly("stat pom.xml"));
        assertTrue(readonly("file FengYu.jar"));
        assertTrue(readonly("pwd"));
        assertTrue(readonly("ls -la"));
    }

    @Test
    void identityAndInfoCommandsAreReadonly() {
        assertTrue(readonly("which git"));
        assertTrue(readonly("type ls"));
        assertTrue(readonly("whoami"));
        assertTrue(readonly("id"));
        assertTrue(readonly("groups"));
        assertTrue(readonly("hostname"));
        assertTrue(readonly("uname -a"));
        assertTrue(readonly("date"));
        assertTrue(readonly("true"));
        assertTrue(readonly("false"));
    }

    @Test
    void dateClockWriteIsNotReadonly() {
        assertFalse(readonly("date -s 2026-01-01"), "date -s sets the system clock");
        assertFalse(readonly("date --set 2026-01-01"), "--set is the same write");
    }

    @Test
    void echoAndPrintfAreReadonlyWithoutSubstitution() {
        assertTrue(readonly("echo hello"));
        assertTrue(readonly("printf '%s\\n' done"));
        assertFalse(readonly("echo $(whoami)"), "command substitution executes");
        assertFalse(readonly("printf 'a`id`b'"), "backtick substitution executes");
        assertFalse(readonly("echo $HOME"), "expansion is opaque to static analysis");
    }

    // ── search family ──────────────────────────────────────────────────────────────────

    @Test
    void searchFamilyIsReadonly() {
        assertTrue(readonly("grep -rn TODO src"));
        assertTrue(readonly("egrep 'a|b' file.txt"));
        assertTrue(readonly("fgrep literal file.txt"));
        assertTrue(readonly("rg -i pattern src"));
        assertTrue(readonly("find . -name '*.ts'"));
    }

    @Test
    void findMutatingFlagsAreNotReadonly() {
        assertFalse(readonly("find . -delete"), "-delete removes files");
        assertFalse(readonly("find . -exec rm {} \\;"), "-exec runs an arbitrary command");
        assertFalse(readonly("find . -execdir cat {} +"));
        assertFalse(readonly("find . -fprint /tmp/listing"), "-fprint writes a file");
        assertFalse(readonly("find . -fprint0 /dev/null"));
        assertTrue(readonly("find . -name '*.ts' -print"), "-print only reads");
    }

    @Test
    void rgPreIsArbitraryExecutionNotReadonly() {
        assertFalse(readonly("rg --pre sh README.md"));
        assertFalse(readonly("rg --pre-bin sh README.md"));
    }

    // ── git family ─────────────────────────────────────────────────────────────────────

    @Test
    void unconditionallyReadonlyGitSubcommands() {
        for (String command : new String[] {
                "git status", "git diff HEAD", "git log --oneline -5", "git show abc123",
                "git blame src/Main.java", "git shortlog -sn", "git rev-parse HEAD",
                "git describe --tags", "git ls-files", "git ls-tree HEAD",
                "git show-ref --heads", "git --no-pager log"}) {
            assertTrue(readonly(command), command);
        }
    }

    @Test
    void gitBranchListShapesOnly() {
        assertTrue(readonly("git branch"));
        assertTrue(readonly("git branch --list"));
        assertTrue(readonly("git branch --list 'feat*'"));
        assertTrue(readonly("git branch -l"));
        assertTrue(readonly("git branch -r"));
        assertTrue(readonly("git branch -a -v"));
        assertFalse(readonly("git branch feature"), "a free operand creates a ref");
        assertFalse(readonly("git branch -D main"), "-D deletes a ref");
        assertFalse(readonly("git branch -m new-name"), "-m renames a ref");
    }

    @Test
    void gitTagListShapesOnly() {
        assertTrue(readonly("git tag"));
        assertTrue(readonly("git tag -l"));
        assertTrue(readonly("git tag --list 'v*'"));
        assertFalse(readonly("git tag v1.0"), "an operand creates a tag");
        assertFalse(readonly("git tag -d v1.0"), "-d deletes a tag");
    }

    @Test
    void gitRemoteListShapesOnly() {
        assertTrue(readonly("git remote"));
        assertTrue(readonly("git remote -v"));
        assertTrue(readonly("git remote --verbose"));
        assertTrue(readonly("git remote get-url origin"));
        assertFalse(readonly("git remote add origin https://example.invalid/repo.git"));
        assertFalse(readonly("git remote remove origin"));
    }

    @Test
    void gitConfigGetAndListShapesOnly() {
        assertTrue(readonly("git config --get user.email"));
        assertTrue(readonly("git config --get-all user.name"));
        assertTrue(readonly("git config --get-regexp 'alias.*'"));
        assertTrue(readonly("git config --list"));
        assertTrue(readonly("git config -l"));
        assertTrue(readonly("git config --list --local --show-origin"));
        assertFalse(readonly("git config --unset user.email"), "--unset writes the config");
        assertFalse(readonly("git config --unset-all user.name"));
        assertFalse(readonly("git config --edit"), "--edit opens an editor session");
        assertFalse(readonly("git config user.name extra"), "no get/list mode named");
        assertFalse(readonly("git config"), "bare git config proves nothing");
    }

    @Test
    void gitStashWorktreeReflogReadonlyShapesOnly() {
        assertTrue(readonly("git stash list"));
        assertFalse(readonly("git stash"), "bare git stash STASHES");
        assertFalse(readonly("git stash pop"));
        assertTrue(readonly("git worktree list"));
        assertFalse(readonly("git worktree add ../x"));
        assertTrue(readonly("git reflog"));
        assertTrue(readonly("git reflog show HEAD"));
        assertFalse(readonly("git reflog delete HEAD@{1}"));
        assertFalse(readonly("git reflog expire --all"));
    }

    @Test
    void gitWriteSubcommandsAndUnknownsAreNotReadonly() {
        for (String command : new String[] {
                "git commit -m x", "git push origin main", "git checkout main",
                "git reset --hard", "git add src", "git merge feature",
                "git difftool HEAD", "git"}) {
            assertFalse(readonly(command), command);
        }
    }

    // ── gh family ──────────────────────────────────────────────────────────────────────

    @Test
    void ghReadonlySubcommands() {
        for (String command : new String[] {
                "gh status", "gh pr list", "gh pr view 12", "gh pr diff",
                "gh issue list", "gh issue view 7",
                "gh run list", "gh run view 42", "gh run watch 42",
                "gh repo view", "gh release list", "gh release view v1.0",
                "gh label list", "gh milestone list",
                "gh search repos fengyu", "gh search issues crash",
                "gh search prs backport", "gh search code execpolicy", "gh search commits main"}) {
            assertTrue(readonly(command), command);
        }
    }

    @Test
    void ghApiIsReadonlyOnlyWithAnExplicitGet() {
        assertTrue(readonly("gh api --method GET /repos/owner/repo"));
        assertTrue(readonly("gh api -X GET /user"));
        assertFalse(readonly("gh api /repos/owner/repo"), "the implicit GET is not trusted");
        assertFalse(readonly("gh api --method POST /repos/owner/repo/issues"));
        assertFalse(readonly("gh api --method DELETE /repos/owner/repo"));
    }

    @Test
    void ghWriteAndUnknownSubcommandsAreNotReadonly() {
        assertFalse(readonly("gh pr create --fill"));
        assertFalse(readonly("gh repo clone owner/repo"));
        assertFalse(readonly("gh release create v2.0"));
        assertFalse(readonly("gh run rerun 42"));
        assertFalse(readonly("gh label create bug"));
        assertFalse(readonly("gh unknown list"));
        assertFalse(readonly("gh"));
    }

    // ── wrappers ───────────────────────────────────────────────────────────────────────

    @Test
    void timeoutPassesThroughToTheWrappedCommand() {
        assertTrue(readonly("timeout 10 git status"));
        assertTrue(readonly("timeout 5s whoami"));
        assertTrue(readonly("timeout --preserve-status 5 git log"));
        assertFalse(readonly("timeout 5 rm -rf build"), "the wrapped command decides");
        assertFalse(readonly("timeout 5 npm install"));
    }

    @Test
    void sudoAndEnvPrefixesAreNeverReadonly() {
        assertFalse(readonly("sudo cat /etc/passwd"));
        assertFalse(readonly("sudo git status"), "privilege escalation is a per-call human decision");
        assertFalse(readonly("env cat file.txt"), "env prefixes arbitrary commands");
        assertFalse(readonly("env -u HOME ls"));
    }

    // ── chain combinators, both directions ─────────────────────────────────────────────

    @Test
    void chainsAreReadonlyOnlyWhenEverySegmentIs() {
        assertTrue(readonly("git status && ls -la"));
        assertTrue(readonly("git log | grep execpolicy"));
        assertTrue(readonly("cat a.txt || cat b.txt"));
        assertTrue(readonly("git status; ls"));
        assertTrue(readonly("git diff && git log | wc -l"));
        assertFalse(readonly("git status && npm install"), "one write segment sinks the line");
        assertFalse(readonly("npm install && git status"));
        assertFalse(readonly("git log | sh"), "a pipe into a shell executes");
        assertFalse(readonly("git status; rm notes.txt"));
        assertFalse(readonly("git status;"), "an empty trailing segment is refused");
    }

    // ── redirection, substitution, backgrounding ───────────────────────────────────────

    @Test
    void redirectionsAreNeverReadonly() {
        assertFalse(readonly("echo hi > file.txt"));
        assertFalse(readonly("git log >> out.txt"));
        assertFalse(readonly("git status 2> errors.txt"));
        assertFalse(readonly("cat x &> all.txt"));
        assertFalse(readonly("git log <> buffer"));
        assertFalse(readonly("cat < input.txt"), "input redirection is refused conservatively");
        assertFalse(readonly("grep pattern file > matches.txt"));
    }

    @Test
    void substitutionExpansionAndBackgroundingAreNeverReadonly() {
        assertFalse(readonly("cat $(whoami)"));
        assertFalse(readonly("ls `pwd`"));
        assertFalse(readonly("echo ${HOME}"));
        assertFalse(readonly("git commit -m \"fix $(date)\""));
        assertFalse(readonly("cat x & ls"), "a lone & backgrounds");
        assertFalse(readonly("git log\nls"), "a newline means a script, not one command");
    }

    // ── quoting and lexing ─────────────────────────────────────────────────────────────

    @Test
    void quotedArgumentsKeepTheirGrouping() {
        assertTrue(readonly("grep 'hello world' file.txt"));
        assertTrue(readonly("grep \"hello world\" file.txt"));
        assertFalse(readonly("git commit -m 'multi word message'"),
                "commit stays non-readonly — the quoted arg only proves lexing");
        assertTrue(readonly("echo \"a;b\""), "a quoted ; is not a chain separator");
        assertTrue(readonly("grep '>' file.txt"), "a quoted > is an argument, not a redirect");
        assertTrue(readonly("grep foo\\ bar file.txt"), "an escaped space is one argument");
    }

    @Test
    void unreadableLinesAreNeverReadonly() {
        assertFalse(readonly("grep 'unclosed"));
        assertFalse(readonly("grep \"unclosed"));
        assertFalse(readonly("cat trailing-backslash\\"));
        assertFalse(readonly(""));
        assertFalse(readonly("   "));
        assertFalse(readonly(null));
    }

    @Test
    void executablesNormalizeByBasenameAndCase() {
        assertTrue(readonly("/usr/bin/git status"));
        assertTrue(readonly("GIT status"));
        assertTrue(readonly("/usr/bin/whoami"));
        assertFalse(readonly("/bin/rm -rf x"));
    }
}

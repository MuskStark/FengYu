package fan.summer.fengyu.ai.tools;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Grammar-level behavior of the lenient apply_patch parser: hunk shapes, @@ anchors,
 * End-of-File anchoring, wrapper stripping (markdown fence, heredoc), and the loud
 * failures — prose inside a hunk and no-op chunks must never pass as a patch.
 */
class ApplyPatchParserTest {

    private static ApplyPatchParser.Patch parse(String patch) {
        return ApplyPatchParser.parse(patch);
    }

    @Test
    void parsesUpdateWithAnchorAndEndOfFile() {
        ApplyPatchParser.Patch patch = parse("""
                *** Begin Patch
                *** Update File: src/app.py
                @@ def example():
                 context
                -removed
                +added
                *** End Patch
                """);
        assertEquals(1, patch.hunks().size());
        ApplyPatchParser.UpdateFile update = assertInstanceOf(ApplyPatchParser.UpdateFile.class,
                patch.hunks().get(0));
        assertEquals("src/app.py", update.path());
        assertEquals(1, update.chunks().size());
        ApplyPatchParser.Chunk chunk = update.chunks().get(0);
        assertEquals("def example():", chunk.anchor());
        assertEquals(List.of("context", "removed"), chunk.oldLines());
        assertEquals(List.of("context", "added"), chunk.newLines());
        assertFalse(chunk.endOfFile());
    }

    @Test
    void parsesAddDeleteAndMove() {
        ApplyPatchParser.Patch patch = parse("""
                *** Begin Patch
                *** Add File: new.txt
                +first
                +
                +third
                *** Delete File: old.txt
                *** Update File: a.py
                *** Move to: b.py
                @@ class X:
                -x = 1
                +x = 2
                *** End Patch
                """);
        assertEquals(3, patch.hunks().size());
        ApplyPatchParser.AddFile add = assertInstanceOf(ApplyPatchParser.AddFile.class,
                patch.hunks().get(0));
        assertEquals(List.of("first", "", "third"), add.lines());
        assertInstanceOf(ApplyPatchParser.DeleteFile.class, patch.hunks().get(1));
        ApplyPatchParser.UpdateFile move = assertInstanceOf(ApplyPatchParser.UpdateFile.class,
                patch.hunks().get(2));
        assertEquals("b.py", move.moveTo());
    }

    @Test
    void multipleAnchorsSplitChunks() {
        ApplyPatchParser.UpdateFile update = (ApplyPatchParser.UpdateFile) parse("""
                *** Begin Patch
                *** Update File: a.py
                @@ def one():
                -a
                +b
                @@ def two():
                -c
                +d
                *** End Patch
                """).hunks().get(0);
        assertEquals(2, update.chunks().size());
        assertEquals("def one():", update.chunks().get(0).anchor());
        assertEquals("def two():", update.chunks().get(1).anchor());
    }

    @Test
    void stripsMarkdownFenceAndHeredocWrappers() {
        ApplyPatchParser.Patch fenced = parse("""
                ```apply_patch
                *** Begin Patch
                *** Delete File: x
                *** End Patch
                ```
                """);
        assertEquals(1, fenced.hunks().size());
        ApplyPatchParser.Patch heredoc = parse("""
                <<EOF
                *** Begin Patch
                *** Delete File: x
                *** End Patch
                EOF
                """);
        assertEquals(1, heredoc.hunks().size());
    }

    @Test
    void toleratesWhitespaceDriftAroundMarkersAndBlankContext() {
        ApplyPatchParser.UpdateFile update = (ApplyPatchParser.UpdateFile) parse("""
                *** Begin Patch                  \s
                  *** Update File: a.py
                @@ def one():
                \s
                -a
                +b
                \s
                *** End Patch
                """).hunks().get(0);
        // The bare blank lines read as empty context lines, not errors.
        assertEquals(List.of("", "a", ""), update.chunks().get(0).oldLines());
        assertEquals(List.of("", "b", ""), update.chunks().get(0).newLines());
    }

    @Test
    void endOfFileMarkerAnchorsTheChunk() {
        ApplyPatchParser.UpdateFile update = (ApplyPatchParser.UpdateFile) parse("""
                *** Begin Patch
                *** Update File: a.py
                -tail
                +new tail
                *** End of File
                *** End Patch
                """).hunks().get(0);
        assertTrue(update.chunks().get(0).endOfFile());
    }

    @Test
    void proseInsideAHunkFailsLoudly() {
        Exception e = assertThrows(ApplyPatchParser.ParseError.class, () -> parse("""
                *** Begin Patch
                *** Update File: a.py
                I will now change the function.
                -a
                +b
                *** End Patch
                """));
        assertTrue(e.getMessage().contains("must start with"));
    }

    @Test
    void malformedPatchesAreRejected() {
        assertThrows(ApplyPatchParser.ParseError.class, () -> parse(null));
        assertThrows(ApplyPatchParser.ParseError.class, () -> parse(""));
        assertThrows(ApplyPatchParser.ParseError.class, () -> parse("no markers at all"));
        assertThrows(ApplyPatchParser.ParseError.class, () -> parse("""
                *** Begin Patch
                *** End Patch
                """));
        // A pure-context chunk changes nothing and must not count as a hunk.
        assertThrows(ApplyPatchParser.ParseError.class, () -> parse("""
                *** Begin Patch
                *** Update File: a.py
                 just context
                *** End Patch
                """));
        assertThrows(ApplyPatchParser.ParseError.class, () -> parse("""
                *** Begin Patch
                *** Add File:
                +x
                *** End Patch
                """));
    }

    @Test
    void moveMarkerAfterChangeLinesIsRejected() {
        Exception e = assertThrows(ApplyPatchParser.ParseError.class, () -> parse("""
                *** Begin Patch
                *** Update File: a.py
                -a
                +b
                *** Move to: c.py
                *** End Patch
                """));
        assertTrue(e.getMessage().contains("directly after"));
    }

    @Test
    void trailingCommentaryAfterEndPatchIsIgnored() {
        ApplyPatchParser.Patch patch = parse("""
                *** Begin Patch
                *** Delete File: x
                *** End Patch
                That was the whole change, please apply it.
                """);
        assertEquals(1, patch.hunks().size());
    }
}

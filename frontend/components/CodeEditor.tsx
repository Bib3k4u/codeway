"use client";
import { useEffect, useRef } from "react";
import type { editor as MonacoNs } from "monaco-editor";
import MonacoEditor, { type BeforeMount, type OnMount } from "@monaco-editor/react";
import { useTheme } from "next-themes";
import type { Diagnostic } from "@/lib/api";
import { monacoLanguage } from "@/lib/lang";
import { registerCompletions, setProjectFiles } from "@/lib/completion";

const beforeMount: BeforeMount = (monaco) => {
  // project-aware suggestions: classes, constructors and methods with tab-stop parameters, generics, library members
  registerCompletions(monaco);
  // Monochrome code with yellow as the only colour, matching the app palette.
  monaco.editor.defineTheme("codeway-light", {
    base: "vs", inherit: true,
    rules: [
      { token: "keyword", foreground: "755D00", fontStyle: "bold" }, { token: "string", foreground: "6B5A12" },
      { token: "number", foreground: "A07F00" }, { token: "comment", foreground: "8F8F86", fontStyle: "italic" },
      { token: "type.identifier", foreground: "1F1E1A", fontStyle: "bold" }, { token: "annotation", foreground: "7A6200" },
      { token: "identifier", foreground: "2A2925" }, { token: "delimiter", foreground: "55554D" },
    ],
    colors: {
      "editor.background": "#f8f7f2", "editor.foreground": "#1f1e1a", "editorLineNumber.foreground": "#aeaba0", "editorLineNumber.activeForeground": "#1f1e1a",
      "editor.lineHighlightBackground": "#f1eddf", "editorGutter.background": "#f8f7f2", "editorCursor.foreground": "#1f1e1a",
      "editor.selectionBackground": "#ecc94b55", "editorIndentGuide.background1": "#e6e3d9",
      "editorBracketHighlight.foreground1": "#7A6200", "editorBracketHighlight.foreground2": "#0A0A0A", "editorBracketHighlight.foreground3": "#55554D",
    },
  });
  monaco.editor.defineTheme("codeway-dark", {
    base: "vs-dark", inherit: true,
    rules: [
      { token: "keyword", foreground: "F6DC3C" }, { token: "string", foreground: "D9D3A3" },
      { token: "number", foreground: "FFE761" }, { token: "comment", foreground: "6F6F6A", fontStyle: "italic" },
      { token: "type.identifier", foreground: "FFFFFF", fontStyle: "bold" }, { token: "annotation", foreground: "F6DC3C" },
      { token: "identifier", foreground: "EDEDE8" }, { token: "delimiter", foreground: "B5B5B0" },
    ],
    colors: {
      "editor.background": "#121212", "editor.foreground": "#ededE8", "editorLineNumber.foreground": "#4a4a46", "editorLineNumber.activeForeground": "#f6dc3c",
      "editor.lineHighlightBackground": "#1a1a17", "editorGutter.background": "#121212", "editorCursor.foreground": "#f6dc3c",
      "editor.selectionBackground": "#f6dc3c33", "editorIndentGuide.background1": "#232323",
      "editorBracketHighlight.foreground1": "#F6DC3C", "editorBracketHighlight.foreground2": "#FFFFFF", "editorBracketHighlight.foreground3": "#B5B5B0",
    },
  });
};

/** Text to mark everywhere it appears in the open file (the project search). */
export type Highlight = { query: string; cs: boolean };
/** Ask the editor to select and scroll to a range of a file; `n` changes with every request so the same one can be repeated. */
/** Where the cursor and scroll were in each file, so coming back to a tab picks up where it was left. */
const viewStates = new Map<string, MonacoNs.ICodeEditorViewState | null>();

/** A new challenge starts from clean editor models: files of the same name from the last one must not show through. */
export function resetEditorModels() {
  viewStates.clear();
  const monaco = (globalThis as { monaco?: { editor: { getModels(): { dispose(): void }[] } } }).monaco;
  try { monaco?.editor.getModels().forEach((m) => m.dispose()); } catch { /* nothing to clear */ }
}

export type Reveal = { path: string; start: number; end: number; n: number };

export default function CodeEditor({ path, value, onChange, diagnostics, files, readOnly, highlight, reveal }: {
  path: string; value: string; onChange: (v: string) => void; diagnostics: Diagnostic[];
  highlight?: Highlight | null; reveal?: Reveal | null;
  /** every file in the project, so suggestions know classes in files that aren't open */
  files?: Record<string, string>;
  readOnly?: boolean;
}) {
  const { resolvedTheme } = useTheme();
  const monacoRef = useRef<Parameters<OnMount>[1] | null>(null);
  const editorRef = useRef<MonacoNs.IStandaloneCodeEditor | null>(null);
  const marks = useRef<MonacoNs.IEditorDecorationsCollection | null>(null);

  const applyMarkers = () => {
    const monaco = monacoRef.current;
    if (!monaco) return;
    for (const model of monaco.editor.getModels()) {
      const name = model.uri.path.replace(/^\//, "");
      const mine = diagnostics.filter((d) => d.file === name && d.line > 0 && d.line <= model.getLineCount());
      monaco.editor.setModelMarkers(model, "codeway", mine.map((d) => ({
        severity: d.severity === "warning" ? monaco.MarkerSeverity.Warning : monaco.MarkerSeverity.Error,
        message: d.message, startLineNumber: d.line, endLineNumber: d.line,
        startColumn: 1, endColumn: model.getLineMaxColumn(d.line),
      })));
    }
  };
  useEffect(applyMarkers);
  useEffect(() => { if (files) setProjectFiles(files); }, [files]);

  // project search: every match in this file is marked
  const q = highlight?.query ?? "", cs = highlight?.cs ?? false;
  useEffect(() => {
    const ed = editorRef.current, model = ed?.getModel();
    if (!ed || !model) return;
    marks.current ??= ed.createDecorationsCollection();
    marks.current.set(q ? model.findMatches(q, false, false, cs, null, false).map((m) => ({ range: m.range, options: { inlineClassName: "cw-find-match", overviewRuler: { color: "#d4b106", position: 4 } } })) : []);
  }, [q, cs, value, path]);

  // select and scroll to a match; the editor may still be switching to this file, so try again for a moment
  const done = useRef(0);
  useEffect(() => {
    if (!reveal || reveal.n === done.current) return;
    let tries = 0;
    const go = () => {
      const ed = editorRef.current, model = ed?.getModel();
      if (ed && model && model.uri.path.replace(/^\//, "") === reveal.path) {
        const a = model.getPositionAt(reveal.start), b = model.getPositionAt(reveal.end);
        const range = { startLineNumber: a.lineNumber, startColumn: a.column, endLineNumber: b.lineNumber, endColumn: b.column };
        ed.setSelection(range); ed.revealRangeInCenterIfOutsideViewport(range); ed.focus();
        done.current = reveal.n;
      } else if (tries++ < 12) timer = setTimeout(go, 60);
    };
    let timer = setTimeout(go, 0);
    return () => clearTimeout(timer);
  }, [reveal, path]);

  return (
    <MonacoEditor
      height="100%" language={monacoLanguage(path)} path={path} value={value}
      theme={resolvedTheme === "dark" ? "codeway-dark" : "codeway-light"}
      beforeMount={beforeMount}
      // two columns can show the same file, so closing one must not dispose the model the other still uses
      keepCurrentModel
      onMount={(editor, monaco) => {
        monacoRef.current = monaco; editorRef.current = editor; marks.current = null; applyMarkers();
        const saved = viewStates.get(path);
        if (saved) editor.restoreViewState(saved);
        const remember = () => viewStates.set(path, editor.saveViewState());
        editor.onDidChangeCursorPosition(remember); editor.onDidScrollChange(remember);
        // a model kept from an earlier challenge's file of the same name must not show its old text
        if (editor.getValue() !== value) editor.getModel()?.setValue(value);
      }}
      onChange={(v) => onChange(v ?? "")}
      loading={<div className="skeleton m-6 h-4 w-48" />}
      options={{
        fontFamily: '"SF Mono", ui-monospace, Menlo, Monaco, monospace', fontSize: 13.5, lineHeight: 21,
        minimap: { enabled: false }, scrollBeyondLastLine: false, padding: { top: 14, bottom: 14 },
        readOnly: !!readOnly, domReadOnly: !!readOnly, automaticLayout: true, tabSize: 4, renderLineHighlight: "gutter", smoothScrolling: true,
        cursorSmoothCaretAnimation: "on", overviewRulerLanes: 0, fontLigatures: true, bracketPairColorization: { enabled: true },
        // project suggestions first; tab stops in inserted calls; keep word suggestions as a fallback
        suggest: { snippetsPreventQuickSuggestions: false, showWords: true, localityBonus: true, preview: true },
        quickSuggestions: { other: true, comments: false, strings: false }, tabCompletion: "on", suggestSelection: "first",
      }}
    />
  );
}

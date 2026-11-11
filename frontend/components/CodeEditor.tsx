"use client";
import { useEffect, useRef } from "react";
import MonacoEditor, { type BeforeMount, type OnMount } from "@monaco-editor/react";
import { useTheme } from "next-themes";
import type { Diagnostic } from "@/lib/api";

const beforeMount: BeforeMount = (monaco) => {
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

export default function CodeEditor({ path, value, onChange, diagnostics }: {
  path: string; value: string; onChange: (v: string) => void; diagnostics: Diagnostic[];
}) {
  const { resolvedTheme } = useTheme();
  const monacoRef = useRef<Parameters<OnMount>[1] | null>(null);

  const applyMarkers = () => {
    const monaco = monacoRef.current;
    if (!monaco) return;
    for (const model of monaco.editor.getModels()) {
      const name = model.uri.path.replace(/^\//, "");
      const mine = diagnostics.filter((d) => d.file === name && d.line > 0 && d.line <= model.getLineCount());
      monaco.editor.setModelMarkers(model, "javac", mine.map((d) => ({
        severity: d.severity === "warning" ? monaco.MarkerSeverity.Warning : monaco.MarkerSeverity.Error,
        message: d.message, startLineNumber: d.line, endLineNumber: d.line,
        startColumn: 1, endColumn: model.getLineMaxColumn(d.line),
      })));
    }
  };
  useEffect(applyMarkers);

  return (
    <MonacoEditor
      height="100%" language="java" path={path} value={value}
      theme={resolvedTheme === "dark" ? "codeway-dark" : "codeway-light"}
      beforeMount={beforeMount}
      onMount={(_, monaco) => { monacoRef.current = monaco; applyMarkers(); }}
      onChange={(v) => onChange(v ?? "")}
      loading={<div className="skeleton m-6 h-4 w-48" />}
      options={{
        fontFamily: '"SF Mono", ui-monospace, Menlo, Monaco, monospace', fontSize: 13.5, lineHeight: 21,
        minimap: { enabled: false }, scrollBeyondLastLine: false, padding: { top: 14, bottom: 14 },
        automaticLayout: true, tabSize: 4, renderLineHighlight: "gutter", smoothScrolling: true,
        cursorSmoothCaretAnimation: "on", overviewRulerLanes: 0, fontLigatures: true, bracketPairColorization: { enabled: true },
      }}
    />
  );
}

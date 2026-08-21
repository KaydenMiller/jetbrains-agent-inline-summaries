# plugin.xml registration for W-7

W-7 does not edit `src/main/resources/META-INF/plugin.xml` — W-5c owns that file
concurrently. This is the exact change the orchestrator applies. It is the **only**
step left; no other wiring is needed.

## The element

Add one `<listener>` **inside the existing `<projectListeners>` block**:

```xml
<listener class="why.editor.WhyEditorGutterListener"
          topic="com.intellij.openapi.fileEditor.FileEditorManagerListener"/>
```

## Why

- `WhyEditorGutterListener` (`why/editor/GutterPainter.kt`) is the editor lifecycle
  hook for R7.1: `fileOpened` attaches the gutter highlighters for a file, `fileClosed`
  detaches them when the last editor on that file is gone.
- Declared under `projectListeners` rather than `applicationListeners` because
  `FileEditorManagerListener.FILE_EDITOR_MANAGER` is published on the project bus and
  everything downstream (the model service, the `.why/` root, the markup model) is
  per project. It is also the reason `EditorFactoryListener` was not used — see the
  file header in `GutterPainter.kt`.
- `WhyGutterService` needs **no** element of its own: `@Service(Service.Level.PROJECT)`
  makes it a light service, created on the first `project.service<WhyGutterService>()`
  call from the listener. Its `WHY_MODEL_CHANGED` subscription is set up in its `init`.
- No `<extensions>` entry, no action, no icon declaration. The icon is loaded from
  `/icons/whyNote.svg` on the plugin classloader via `IconLoader.getIcon`.

## Resulting block

For clarity, the `<projectListeners>` block after the change (W-5's listener plus this
one):

```xml
<projectListeners>
    <listener class="why.store.WhyTasksVfsListener"
              topic="com.intellij.openapi.vfs.newvfs.BulkFileListener"/>
    <listener class="why.editor.WhyEditorGutterListener"
              topic="com.intellij.openapi.fileEditor.FileEditorManagerListener"/>
</projectListeners>
```

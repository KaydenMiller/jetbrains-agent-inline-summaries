package why.store

import com.intellij.openapi.application.readAction
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import java.nio.file.Path

/**
 * W-5c — what has to happen at project open for the rest of the plugin to work.
 *
 * Two things, both established by sandbox measurement rather than by reading the
 * platform:
 *
 *  1. **The model has to be folded once.** Nothing else does it: W-5's watcher
 *     only reacts to change events, so a project opened on an existing corpus
 *     showed no notes at all until a file happened to change. W-7's gutter icons
 *     need a populated model at open.
 *
 *  2. **The tasks directory has to have a virtual-file-system record.** A
 *     `BulkFileListener` is only handed events for paths the virtual file system
 *     already knows, and at project open it does not know this one: in a sandbox
 *     Rider 261, a `postStartupActivity` running 6.7 s after launch found the
 *     project directory cached, `.why` cached with its children not loaded, and
 *     `.why/tasks` absent from the cache entirely. The platform's own
 *     `InitialVfsRefreshService` walked the content root about a second later and
 *     did make it known, so on that project the corpus became observable without
 *     our help — but only after an unordered, project-model-dependent step that
 *     the plugin should not depend on, and only for a `.why/` that is inside a
 *     content root. [findWhyRoot] deliberately allows one that is not (R8.1).
 *
 * So: refresh the directory into the virtual file system, and watch it. Both are
 * one call. Neither is a poll.
 *
 * The refresh itself creates the records it discovers, which the listener sees as
 * changes, so a freshly opened project logs `why: initial load` and then one
 * `why: reloaded` for the same content — two publications of an identical model,
 * about 130 ms apart in the sandbox run. Left alone: suppressing it would mean
 * tracking which events our own refresh caused, and a subscriber that cannot cope
 * with the same model twice cannot cope with the watcher either.
 */
internal class WhyProjectOpenActivity : ProjectActivity {

    override suspend fun execute(project: Project) {
        val lfs = LocalFileSystem.getInstance()
        for (root in whyRoots(project)) {
            val tasks = tasksDir(root)
            // Gives the directory and its task files a record, so an external append
            // to one of them is an event this plugin's listener can be handed.
            lfs.refreshAndFindFileByNioFile(tasks)?.children
            // A watch root of our own, because a `.why/` above or beside the content
            // roots is covered by nothing the project model registers. Removed with
            // the project so a closed project stops paying for it.
            lfs.addRootToWatch(tasks.toString(), true)?.let { request ->
                Disposer.register(project) { lfs.removeWatchedRoot(request) }
            }
            project.service<WhyModelService>().initialLoad(root)
        }
    }

    /**
     * The distinct `.why/` roots this project can see: the nearest one above the
     * project directory, plus the nearest one above each content root, since R8.1
     * allows several in one window and a nested root to shadow its ancestor.
     *
     * ponytail: a `.why/` in a subdirectory that is not a content root is not
     * found here, so its notes appear only once one of its files changes. Walk
     * downwards from the content roots if a layout like that turns up.
     */
    private suspend fun whyRoots(project: Project): Set<Path> {
        val starts = readAction {
            ProjectRootManager.getInstance(project).contentRoots.map { Path.of(it.path) }
        } + listOfNotNull(project.basePath?.let { Path.of(it) })
        return starts.mapNotNullTo(LinkedHashSet(), ::findWhyRoot)
    }
}

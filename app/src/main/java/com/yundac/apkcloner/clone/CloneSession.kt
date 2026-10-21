package com.yundac.apkcloner.clone

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.yundac.apkcloner.data.CloneRecord
import com.yundac.apkcloner.data.CloneStore
import com.yundac.apkcloner.core.clone.CloneOptions
import com.yundac.apkcloner.core.clone.ClonePipeline
import java.io.File
import java.util.concurrent.Executors

/**
 * Runs one clone at a time on a worker thread and publishes progress to the UI.
 *
 * Deliberately a plain singleton instead of a foreground service: cloning a
 * normal APK takes a few seconds, and keeping it in-process makes cancellation
 * and progress delivery exact. The trade-off -- the clone stops if the app is
 * killed -- is documented in docs/LIMITATIONS.md.
 */
object CloneSession {

    data class Request(
        val sourceApk: File,
        val sourcePackage: String,
        val sourceLabel: String,
        val targetPackage: String,
        val targetLabel: String,
        val permissionsToRemove: Set<String>,
        val disableBackup: Boolean,
        val installedSplits: List<String>,
    )

    data class State(
        val running: Boolean = false,
        val percent: Int = 0,
        val message: String = "",
        val report: ClonePipeline.Report? = null,
        val error: String? = null,
        val targetPackage: String? = null,
    )

    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "clone-worker").apply { isDaemon = true }
    }
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var state = State()

    private var listener: ((State) -> Unit)? = null

    fun snapshot(): State = state

    fun observe(listener: ((State) -> Unit)?) {
        this.listener = listener
        listener?.invoke(state)
    }

    fun start(context: Context, request: Request) {
        if (state.running) return
        publish(State(running = true, percent = 0, message = "Preparing", targetPackage = request.targetPackage))

        val appContext = context.applicationContext
        executor.execute {
            val workingDirectory = File(appContext.cacheDir, "clone-work")
            workingDirectory.deleteRecursively()
            workingDirectory.mkdirs()
            val output = File(CloneStore.directory(appContext), "${request.targetPackage}.apk")
            try {
                val signingKey = SigningKeys.get(appContext)
                val pipeline = ClonePipeline(signingKey)
                val report = pipeline.clone(
                    CloneOptions(
                        sourceApk = request.sourceApk,
                        outputApk = output,
                        workingDirectory = workingDirectory,
                        newPackageName = request.targetPackage,
                        newLabel = request.targetLabel,
                        permissionsToRemove = request.permissionsToRemove,
                        disableBackup = request.disableBackup,
                        installedSplits = request.installedSplits,
                    ),
                    deviceSdkInt = Build.VERSION.SDK_INT,
                ) { progress ->
                    publish(
                        State(
                            running = true,
                            percent = progress.percent,
                            message = progress.message,
                            targetPackage = request.targetPackage,
                        )
                    )
                }

                CloneStore.add(
                    appContext,
                    CloneRecord(
                        packageName = report.changes.newPackage,
                        label = report.changes.label,
                        sourcePackage = report.changes.sourcePackage,
                        apkPath = output.absolutePath,
                        createdAt = System.currentTimeMillis(),
                        sizeBytes = output.length(),
                    )
                )
                publish(
                    State(
                        running = false,
                        percent = 100,
                        message = "Clone ready",
                        report = report,
                        targetPackage = request.targetPackage,
                    )
                )
            } catch (t: Throwable) {
                output.delete()
                publish(
                    State(
                        running = false,
                        percent = 0,
                        message = "Clone failed",
                        error = t.message ?: t.javaClass.simpleName,
                        targetPackage = request.targetPackage,
                    )
                )
            } finally {
                workingDirectory.deleteRecursively()
            }
        }
    }

    fun dismiss() {
        if (!state.running) {
            state = State()
            listener?.invoke(state)
        }
    }

    private fun publish(next: State) {
        state = next
        mainHandler.post { listener?.invoke(next) }
    }
}

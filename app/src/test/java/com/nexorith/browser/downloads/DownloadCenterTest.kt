package com.nexorith.browser.downloads

import com.nexorith.browser.fakes.FakeDownloadRepository
import com.nexorith.browser.fakes.FakeDownloadStorage
import com.nexorith.browser.fakes.FakeDownloader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DownloadCenterTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var repo: FakeDownloadRepository
    private lateinit var storage: FakeDownloadStorage
    private lateinit var downloader: FakeDownloader
    private lateinit var scope: CoroutineScope
    private lateinit var center: DownloadCenter
    private var now = 1_000L
    private var connectionSetting = 4

    @Before
    fun setUp() {
        repo = FakeDownloadRepository()
        storage = FakeDownloadStorage(tmp.root)
        downloader = FakeDownloader()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        center = DownloadCenter(
            repository = repo,
            downloader = downloader,
            storage = storage,
            scope = scope,
            clock = { now++ },
            maxParallel = 2,
            ioDispatcher = Dispatchers.Unconfined,
            connections = { connectionSetting },
        )
    }

    @After
    fun tearDown() = scope.cancel()

    private fun request(
        url: String = "https://example.com/files/data.zip",
        isPrivate: Boolean = false,
        cookies: String? = null,
        disposition: String? = null,
    ) = DownloadRequest(url, "UA", disposition, "application/zip", -1, "https://example.com/", cookies, isPrivate)

    private fun enqueue(vararg requests: DownloadRequest): List<Long> {
        requests.forEach { assertTrue(center.enqueue(it)) }
        return center.items.value.map { it.id }.reversed() // oldest first
    }

    private fun item(id: Long) = center.find(id)!!

    private fun progress(
        downloaded: Long,
        total: Long = -1,
        name: String? = null,
        segmentState: String? = null,
        connections: Int = 1,
    ) = DownloadProgress(
        downloadedBytes = downloaded, totalBytes = total, speedBps = 2048, etaSeconds = 10,
        acceptsRanges = true, validator = "\"v1\"", suggestedName = name, mimeType = null, activeMillis = 500,
        segmentState = segmentState, connections = connections, segmented = segmentState != null,
    )

    @Test
    fun enqueueStartsTheDownloadAndHandsOverTheRequestContext() {
        val id = enqueue(request(cookies = "a=b")).single()

        assertEquals(DownloadStatus.RUNNING, item(id).status)
        assertEquals("data.zip", item(id).fileName)
        val call = downloader.calls.single()
        assertEquals("https://example.com/files/data.zip", call.spec.url)
        assertEquals("a=b", call.spec.cookies)
        assertEquals("UA", call.spec.userAgent)
        assertEquals(storage.partFile(id), call.file)
        assertEquals(1, repo.rows.size)
    }

    @Test
    fun linksThatAreNotHttpAreRejected() {
        assertFalse(center.enqueue(request(url = "blob:https://example.com/abc")))
        assertFalse(center.enqueue(request(url = "data:text/plain;base64,AAAA")))
        assertFalse(center.enqueue(request(url = "ftp://example.com/file")))
        assertTrue(center.items.value.isEmpty())
    }

    @Test
    fun progressUpdatesTheItem() {
        val id = enqueue(request()).single()
        downloader.calls.single().onProgress(progress(downloaded = 500, total = 2_000))

        val updated = item(id)
        assertEquals(500L, updated.downloadedBytes)
        assertEquals(2_000L, updated.totalBytes)
        assertEquals(2048L, updated.speedBps)
        assertEquals(10L, updated.etaSeconds)
        assertEquals(0.25f, updated.fraction!!, 0.0001f)
        assertEquals("\"v1\"", updated.validator)
    }

    @Test
    fun completionPublishesTheFileAndRemovesTheParts() {
        val id = enqueue(request()).single()
        val call = downloader.calls.single()
        call.file.writeBytes(ByteArray(1_000) { 7 })
        call.gate.complete(DownloadResult.Completed(1_000))

        val done = item(id)
        assertEquals(DownloadStatus.COMPLETED, done.status)
        assertEquals("content://fake/data.zip", done.savedUri)
        assertEquals(1_000L, done.downloadedBytes)
        assertEquals(1_000L, done.totalBytes)
        assertArrayEquals(ByteArray(1_000) { 7 }, storage.published["data.zip"])
        assertFalse(call.file.exists())
        assertEquals(DownloadStatus.COMPLETED, repo.rows.getValue(id).status)
    }

    @Test
    fun aFailedPublishFailsTheDownloadButKeepsTheBytes() {
        storage.publishFails = true
        val id = enqueue(request()).single()
        val call = downloader.calls.single()
        call.file.writeBytes(ByteArray(10))
        call.gate.complete(DownloadResult.Completed(10))

        assertEquals(DownloadStatus.FAILED, item(id).status)
        assertEquals(FailureKind.STORAGE, item(id).failure?.kind)
        assertTrue(call.file.exists())
    }

    @Test
    fun pauseStopsTheTransferKeepsThePartialFileAndResumeContinuesIt() {
        val id = enqueue(request()).single()
        val first = downloader.calls.single()
        first.file.writeBytes(ByteArray(400))
        first.onProgress(progress(downloaded = 400, total = 1_000))

        center.pause(id)
        assertEquals(DownloadStatus.PAUSED, item(id).status)
        assertEquals(400L, item(id).downloadedBytes)
        assertEquals(0L, item(id).speedBps)
        assertTrue(first.file.exists())
        assertTrue(first.gate.isCancelled || !first.gate.isCompleted)

        center.resume(id)
        assertEquals(DownloadStatus.RUNNING, item(id).status)
        assertEquals(2, downloader.calls.size)
        assertEquals(first.file, downloader.calls[1].file)
        assertEquals("\"v1\"", downloader.calls[1].spec.validator)
        assertEquals(500L, downloader.calls[1].spec.activeMillisBefore)
    }

    @Test
    fun cancelDeletesThePartialFileAndRetryStartsOver() {
        val id = enqueue(request()).single()
        val call = downloader.calls.single()
        call.file.writeBytes(ByteArray(300))

        center.cancel(id)
        assertEquals(DownloadStatus.CANCELED, item(id).status)
        assertEquals(0L, item(id).downloadedBytes)
        assertFalse(call.file.exists())

        center.retry(id)
        assertEquals(DownloadStatus.RUNNING, item(id).status)
        assertEquals(2, downloader.calls.size)
    }

    @Test
    fun aFailedDownloadKeepsItsBytesAndRetryResumesThem() {
        val id = enqueue(request()).single()
        val call = downloader.calls.single()
        call.file.writeBytes(ByteArray(250))
        call.gate.complete(DownloadResult.Failed(DownloadFailure(FailureKind.NETWORK)))

        assertEquals(DownloadStatus.FAILED, item(id).status)
        assertEquals(FailureKind.NETWORK, item(id).failure?.kind)
        assertEquals(250L, item(id).downloadedBytes)

        center.retry(id)
        assertEquals(DownloadStatus.RUNNING, item(id).status)
        assertNull(item(id).failure)
        assertEquals(250L, downloader.calls[1].file.length())
    }

    @Test
    fun onlyMaxParallelDownloadsRunAndTheRestWait() {
        val ids = enqueue(request("https://e.com/1.zip"), request("https://e.com/2.zip"), request("https://e.com/3.zip"))

        assertEquals(DownloadStatus.RUNNING, item(ids[0]).status)
        assertEquals(DownloadStatus.RUNNING, item(ids[1]).status)
        assertEquals(DownloadStatus.QUEUED, item(ids[2]).status)
        assertEquals(2, downloader.calls.size)

        downloader.calls[0].file.writeBytes(ByteArray(1))
        downloader.calls[0].gate.complete(DownloadResult.Completed(1))

        assertEquals(DownloadStatus.COMPLETED, item(ids[0]).status)
        assertEquals(DownloadStatus.RUNNING, item(ids[2]).status)
        assertEquals(3, downloader.calls.size)
    }

    @Test
    fun pausingARunningDownloadLetsAWaitingOneStart() {
        val ids = enqueue(request("https://e.com/1.zip"), request("https://e.com/2.zip"), request("https://e.com/3.zip"))
        center.pause(ids[0])

        assertEquals(DownloadStatus.PAUSED, item(ids[0]).status)
        assertEquals(DownloadStatus.RUNNING, item(ids[2]).status)
    }

    @Test
    fun pauseAllPausesEverythingActive() {
        val ids = enqueue(request("https://e.com/1.zip"), request("https://e.com/2.zip"), request("https://e.com/3.zip"))
        center.pauseAll()

        ids.forEach { assertEquals(DownloadStatus.PAUSED, item(it).status) }
    }

    @Test
    fun removeDeletesTheRecordAndOptionallyTheSavedFile() {
        val id = enqueue(request()).single()
        val call = downloader.calls.single()
        call.file.writeBytes(ByteArray(5))
        call.gate.complete(DownloadResult.Completed(5))

        center.remove(id, deleteFile = true)

        assertNull(center.find(id))
        assertTrue(repo.rows.isEmpty())
        assertEquals(listOf("content://fake/data.zip"), storage.deletedPublished)
    }

    @Test
    fun removingARunningDownloadStopsItAndDeletesItsPartialFile() {
        val id = enqueue(request()).single()
        val call = downloader.calls.single()
        call.file.writeBytes(ByteArray(5))

        center.remove(id)

        assertNull(center.find(id))
        assertFalse(call.file.exists())
        assertTrue(repo.rows.isEmpty())
    }

    @Test
    fun clearFinishedKeepsActiveDownloads() {
        val ids = enqueue(request("https://e.com/1.zip"), request("https://e.com/2.zip"))
        downloader.calls[0].file.writeBytes(ByteArray(1))
        downloader.calls[0].gate.complete(DownloadResult.Completed(1))

        center.clearFinished()

        assertNull(center.find(ids[0]))
        assertEquals(DownloadStatus.RUNNING, item(ids[1]).status)
    }

    @Test
    fun privateDownloadsAreNeverWrittenToTheDatabase() {
        val id = enqueue(request(isPrivate = true)).single()
        assertTrue(id < 0)
        assertTrue(item(id).isPrivate)
        downloader.calls.single().onProgress(progress(downloaded = 10, total = 100))
        downloader.calls.single().file.writeBytes(ByteArray(10))
        center.pause(id)

        assertEquals(0, repo.inserts)
        assertTrue(repo.rows.isEmpty())
    }

    @Test
    fun theServerNameReplacesAGuessedNameButNeverAnExplicitOne() {
        val guessed = enqueue(request(url = "https://e.com/api/file/XYZ")).single()
        downloader.calls[0].onProgress(progress(1, name = "Holiday Photos.zip"))
        assertEquals("Holiday Photos.zip", item(guessed).fileName)

        val explicit = enqueue(request(url = "https://e.com/api/file/ABC", disposition = "attachment; filename=\"mine.zip\"")).last()
        downloader.calls[1].onProgress(progress(1, name = "other.zip"))
        assertEquals("mine.zip", item(explicit).fileName)
    }

    @Test
    fun loadBringsBackSavedDownloadsAsPausedAndCleansOrphanParts() = runBlocking {
        repo.insert(DownloadItem(id = 0, url = "https://e.com/a.zip", fileName = "a.zip", status = DownloadStatus.RUNNING, downloadedBytes = 10, createdAt = 5))
        repo.insert(DownloadItem(id = 0, url = "https://e.com/b.zip", fileName = "b.zip", status = DownloadStatus.COMPLETED, createdAt = 6))

        center.load()

        val items = center.items.value
        assertEquals(2, items.size)
        assertEquals(DownloadStatus.COMPLETED, items.first { it.fileName == "b.zip" }.status)
        assertEquals(DownloadStatus.PAUSED, items.first { it.fileName == "a.zip" }.status)
        assertEquals(DownloadStatus.PAUSED, repo.rows.getValue(1).status)
        assertEquals(setOf(1L, 2L), storage.retainedIds)
        assertTrue(downloader.calls.isEmpty()) // nothing restarts by itself
    }

    @Test
    fun loadCanResumeInterruptedDownloadsByItself() = runBlocking {
        repo.insert(DownloadItem(id = 0, url = "https://e.com/a.zip", fileName = "a.zip", status = DownloadStatus.RUNNING, downloadedBytes = 10, createdAt = 5))
        assertFalse(center.loaded.value)

        center.load(resumeInterrupted = true)

        assertTrue(center.loaded.value)
        assertEquals(DownloadStatus.RUNNING, center.items.value.single().status)
        assertEquals("https://e.com/a.zip", downloader.calls.single().spec.url)
    }

    @Test
    fun averageSpeedIsBytesOverTimeSpentTransferring() {
        val item = DownloadItem(id = 1, url = "u", fileName = "f", downloadedBytes = 10_000_000, activeMillis = 5_000)
        assertEquals(2_000_000L, item.averageSpeedBps)
        assertEquals(0L, item.copy(activeMillis = 0).averageSpeedBps)
    }

    // ---- multi-connection downloads ----

    @Test
    fun theConnectionSettingAndTheSavedChunkMapReachTheDownloader() {
        connectionSetting = 6
        val id = enqueue(request()).single()
        assertEquals(6, downloader.calls.single().spec.maxConnections)
        assertNull(downloader.calls.single().spec.segmentState)

        downloader.calls.single().onProgress(progress(downloaded = 100, total = 1_000, segmentState = "1|1000|100|0", connections = 3))
        assertEquals(3, item(id).connections)
        assertTrue(item(id).segmented)

        center.pause(id)
        connectionSetting = 99 // never above eight
        center.resume(id)
        val second = downloader.calls[1].spec
        assertEquals(8, second.maxConnections)
        assertEquals("1|1000|100|0", second.segmentState)
    }

    @Test
    fun pausingAMultiConnectionDownloadKeepsItsProgressNotTheFileLength() {
        val id = enqueue(request()).single()
        val call = downloader.calls.single()
        call.file.writeBytes(ByteArray(1_000)) // preallocated: as long as the whole file
        call.onProgress(progress(downloaded = 300, total = 1_000, segmentState = "1|1000|100|0-2"))

        center.pause(id)
        assertEquals(DownloadStatus.PAUSED, item(id).status)
        assertEquals(300L, item(id).downloadedBytes)

        // The transfer reports once more while it is being stopped; speed stays zero.
        call.onProgress(progress(downloaded = 450, total = 1_000, segmentState = "1|1000|100|0-3,4:50"))
        assertEquals(450L, item(id).downloadedBytes)
        assertEquals("1|1000|100|0-3,4:50", item(id).segmentState)
        assertEquals(0L, item(id).speedBps)
    }

    @Test
    fun aFailedMultiConnectionDownloadKeepsItsProgressAndChunkMap() {
        val id = enqueue(request()).single()
        val call = downloader.calls.single()
        call.file.writeBytes(ByteArray(1_000))
        call.onProgress(progress(downloaded = 700, total = 1_000, segmentState = "1|1000|100|0-6"))
        call.gate.complete(DownloadResult.Failed(DownloadFailure(FailureKind.NETWORK)))

        assertEquals(DownloadStatus.FAILED, item(id).status)
        assertEquals(700L, item(id).downloadedBytes)
        assertEquals("1|1000|100|0-6", item(id).segmentState)
    }

    @Test
    fun aChecksumFailureForgetsTheChunkMapAndProgress() {
        val id = enqueue(request()).single()
        val call = downloader.calls.single()
        call.onProgress(progress(downloaded = 1_000, total = 1_000, segmentState = "1|1000|100|0-9"))
        call.gate.complete(DownloadResult.Failed(DownloadFailure(FailureKind.CHECKSUM)))

        assertEquals(DownloadStatus.FAILED, item(id).status)
        assertEquals(FailureKind.CHECKSUM, item(id).failure?.kind)
        assertEquals(0L, item(id).downloadedBytes)
        assertNull(item(id).segmentState)
        assertNull(item(id).validator)
    }

    @Test
    fun cancelAndCompletionForgetTheChunkMap() {
        val first = enqueue(request("https://e.com/1.zip")).single()
        downloader.calls[0].onProgress(progress(downloaded = 10, total = 100, segmentState = "1|100|10|0"))
        center.cancel(first)
        assertNull(item(first).segmentState)
        assertEquals(0L, item(first).downloadedBytes)

        val second = enqueue(request("https://e.com/2.zip")).last()
        val call = downloader.calls[1]
        call.file.writeBytes(ByteArray(100))
        call.onProgress(progress(downloaded = 100, total = 100, segmentState = "1|100|10|0-9"))
        call.gate.complete(DownloadResult.Completed(100))
        assertEquals(DownloadStatus.COMPLETED, item(second).status)
        assertNull(item(second).segmentState)
        assertEquals(0, item(second).connections)
        assertFalse(item(second).segmented)
    }
}

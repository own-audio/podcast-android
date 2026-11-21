package audio.own.kit.ownaudio

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class IdentifyTest {

    @Test
    fun `a run starts with its scope as snake_case JSON and decodes`() = runBlocking {
        StubServer {
            json("""{"id":"r1","status":"queued","error":null,"scope":{"kind":"tracks","id":null,"label":"Two"},"tracks":0,"groups_total":0,"groups_done":0,"counts":{"sure":0,"review":0,"none":0,"pending":0,"accepted":0,"rejected":0},"created_at":"2026-10-12T05:01:38.047961Z","started_at":null,"finished_at":null}""", 202)
        }.use { stub ->
            val run = stub.client().startIdentify(IdentifyScope("tracks", trackIds = listOf("t1", "t2"), label = "Two"))
            assertTrue(run.isActive)
            val sent = stub.requests.single()
            assertEquals("/api/v2/identify/runs", sent.url.encodedPath)
            assertEquals("""{"scope":{"kind":"tracks","label":"Two","track_ids":["t1","t2"]}}""", sent.text())
        }
    }

    @Test
    fun `a proposal decodes, its class and match included`() = runBlocking {
        StubServer {
            json("""{"items":[{"id":"p1","group":{"id":"g1","kind":"album","label":"Load"},"track":{"id":"t1","title":"X 4","artist":"Band","album":"Load","disc_number":null,"track_number":2,"duration_secs":140},"class":"review","reasons":["artist_differs","album_differs"],"proposal":{"mb_recording_id":"m1","title":"X 4","artist":"Someone","album":"Load","album_artist":null,"mb_release_id":"rel1","track_number":7,"disc_number":1,"year":1978,"length_ms":140000,"score":91},"keep_album":true,"decision":"pending"}]}""")
        }.use { stub ->
            val page = stub.client().identifyProposals("r1", proposalClass = "review", decision = "pending")
            assertEquals("review", stub.requests.single().url.queryParameter("class"))
            val p = page.items.single()
            assertEquals("review", p.proposalClass)
            assertEquals(listOf("artist_differs", "album_differs"), p.reasons)
            assertEquals("m1", p.proposal?.mbRecordingId)
            assertTrue(p.keepAlbum)
            assertFalse(p.chosenByOwner)
        }
    }

    @Test
    fun `accepting the sure matches says which class`() = runBlocking {
        StubServer { json("""{"accepted":16}""") }.use { stub ->
            assertEquals(16, stub.client().acceptSureMatches("r1"))
            assertEquals("""{"class":"sure"}""", stub.requests.single().text())
        }
    }

    @Test
    fun `a short credit is a problem with its code`() = runBlocking {
        StubServer { problem("payment_required", 402, ""","detail":"identifying these tracks can cost up to ${'$'}0.032"""") }.use { stub ->
            try {
                stub.client().startIdentify(IdentifyScope.library())
                fail("expected a problem")
            } catch (p: OwnAudioException.Problem) {
                assertEquals("payment_required", p.code)
                assertEquals(402, p.status)
            }
        }
    }

    @Test
    fun `library folders come from v2, music ones picked out`() = runBlocking {
        StubServer { json(answer("library_folders")) }.use { stub ->
            val folders = stub.client().musicFolders()
            assertEquals(listOf("/music", "/private-music"), folders.map { it.path })
            assertEquals("/api/v2/library/folders", stub.requests.single().url.encodedPath)
            assertEquals("Bearer a", stub.requests.single().headers["Authorization"])
        }
    }

    @Test
    fun `a search in the owner's words sends only what was typed and decodes candidates`() = runBlocking {
        StubServer {
            json("""{"items":[{"mb_recording_id":"m1","title":"Yesterday","artist":"The Beatles","mb_artist_id":null,"album":"Help!","mb_release_id":"rel1","duration_ms":125000,"genre":null,"track_number":13,"year":1965,"cover_art_url":null,"score":100}]}""")
        }.use { stub ->
            val found = stub.client().searchRecordings("t1", RecordingSearch(title = "Yesterday", artist = "The Beatles"))
            assertEquals("Help!", found.single().album)
            assertEquals("m1/rel1", found.single().key)
            val sent = stub.requests.single()
            assertEquals("/api/v2/tracks/t1/identity/search", sent.url.encodedPath)
            assertEquals("""{"artist":"The Beatles","title":"Yesterday"}""", sent.text())
        }
    }

    @Test
    fun `choosing a recording for a proposal sends the link and whether to accept`() = runBlocking {
        StubServer {
            json("""{"id":"p1","group":{"id":"g1","kind":"track","label":"Track 02"},"track":{"id":"t1","title":"Track 02","artist":null,"album":null,"disc_number":null,"track_number":2,"duration_secs":125},"class":"review","reasons":["chosen"],"proposal":{"mb_recording_id":"m1","title":"Yesterday","artist":"The Beatles","album":"Help!","album_artist":null,"mb_release_id":"rel1","track_number":13,"disc_number":null,"year":null,"length_ms":null,"score":100},"keep_album":false,"decision":"accepted","chosen_by_owner":true,"alternatives":3}""")
        }.use { stub ->
            val p = stub.client().chooseRecording("p1", "https://musicbrainz.org/recording/m1", accept = true)
            assertTrue(p.chosenByOwner)
            assertEquals(3, p.alternatives)
            val sent = stub.requests.single()
            assertEquals("PUT", sent.method)
            assertEquals("/api/v2/identify/proposals/p1/recording", sent.url.encodedPath)
            assertEquals("""{"accept":true,"recording":"https://musicbrainz.org/recording/m1"}""", sent.text())
        }
    }

    @Test
    fun `undo puts a song back and answers it`() = runBlocking {
        StubServer { json(TRACK) }.use { stub ->
            assertEquals("t1", stub.client().undoIdentify("t1").id)
            assertEquals(listOf("DELETE /api/v2/tracks/t1/identity"), stub.paths())
        }
    }

    @Test
    fun `cover art archive links, release first`() {
        assertEquals(
            listOf("https://coverartarchive.org/release/r/front-250", "https://coverartarchive.org/release-group/g/front-250"),
            CoverArtArchive.fronts("r", "g"),
        )
    }
}
